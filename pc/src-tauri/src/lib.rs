pub mod state;
pub mod tray;
pub mod pairing {
    pub mod keys;
    pub mod qr;
}
pub mod clipboard;
pub mod mcp;
pub mod mdns;
pub mod media;
pub mod net;
pub mod ws;

use crate::mcp::http_server::McpHttpServer;
use crate::pairing::qr::{EndpointMode, PairingEndpoints, QrGenerator};
use crate::state::AppState;
use serde_json::json;
use std::str::FromStr;
use std::sync::atomic::Ordering;
use tauri::{Emitter, Manager};

#[tauri::command]
async fn get_pairing_info(
    state: tauri::State<'_, AppState>,
    endpoint_mode: Option<String>,
) -> Result<serde_json::Value, String> {
    let mut inner = state.inner.lock().unwrap();
    let settings = AppSettings::load();
    let endpoints = pairing_endpoints(&settings, endpoint_mode.as_deref());
    let mcp_port = settings.advertised_mcp_http_port();
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &inner.pc_public_key,
        &inner.pc_private_key,
        endpoints.clone(),
        settings.device_name.clone(),
        mcp_port,
    )
    .map_err(|e| e.to_string())?;

    inner.active_pairing_nonce = Some(nonce);

    Ok(json!({
        "url": pair_url,
        "fingerprint": fingerprint,
        "qr_svg": qr_svg,
        "hosts": endpoints.advertised_hosts(),
        "local_hosts": endpoints.local_hosts,
        "local_port": endpoints.local_port,
        "internet_host": endpoints.internet_host,
        "internet_port": endpoints.internet_port,
        "endpoint_mode": endpoints.mode.as_str(),
        "mcp_port": mcp_port
    }))
}

#[derive(serde::Serialize)]
pub struct PairedDeviceResponse {
    pub id: String,
    pub name: String,
    pub alias: String,
    pub os: String,
    pub online: bool,
    pub last_seen_ms: u64,
    pub exposed_to_mcp: bool,
    pub ip: Option<String>,
    pub channel: Option<String>,
}

#[tauri::command]
async fn get_paired_devices(
    state: tauri::State<'_, AppState>,
) -> Result<Vec<PairedDeviceResponse>, String> {
    let inner = state.inner.lock().unwrap();
    let mut list = Vec::new();
    for (id, dev) in &inner.paired_devices {
        let session = inner.active_sessions.get(id);
        let online = session.is_some();
        let ip = session.map(|s| s.ip.clone());
        let channel = session.map(|s| s.channel.clone());
        list.push(PairedDeviceResponse {
            id: id.clone(),
            name: dev.name.clone(),
            alias: dev.alias.clone(),
            os: dev.os.clone(),
            online,
            last_seen_ms: dev.last_seen_ms,
            exposed_to_mcp: dev.exposed_to_mcp,
            ip,
            channel,
        });
    }
    list.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(list)
}

#[tauri::command]
async fn rename_paired_device(
    state: tauri::State<'_, AppState>,
    app_handle: tauri::AppHandle,
    id: String,
    alias: String,
) -> Result<(), String> {
    {
        let mut inner = state.inner.lock().unwrap();
        if let Some(dev) = inner.paired_devices.get_mut(&id) {
            dev.alias = alias.trim().to_string();
        } else {
            return Err("Device not found".to_string());
        }
    }
    state.save_paired_devices();
    let _ = app_handle.emit("devices-changed", ());
    Ok(())
}

#[tauri::command]
async fn set_device_mcp_exposure(
    state: tauri::State<'_, AppState>,
    app_handle: tauri::AppHandle,
    id: String,
    exposed: bool,
) -> Result<(), String> {
    {
        let mut inner = state.inner.lock().unwrap();
        if let Some(dev) = inner.paired_devices.get_mut(&id) {
            dev.exposed_to_mcp = exposed;
        } else {
            return Err("Device not found".to_string());
        }
    }
    state.save_paired_devices();
    let _ = app_handle.emit("devices-changed", ());
    Ok(())
}

#[tauri::command]
async fn unpair_device(
    state: tauri::State<'_, AppState>,
    app_handle: tauri::AppHandle,
    id: String,
) -> Result<(), String> {
    let session = {
        let mut inner = state.inner.lock().unwrap();
        inner.paired_devices.remove(&id);
        inner.active_sessions.remove(&id)
    };

    if let Some(session) = session {
        let _ = session
            .tx
            .send(axum::extract::ws::Message::Close(None))
            .await;
    }

    state.save_paired_devices();
    let _ = app_handle.emit("devices-changed", ());
    Ok(())
}

#[tauri::command]
async fn regenerate_pc_identity(
    state: tauri::State<'_, AppState>,
    app_handle: tauri::AppHandle,
) -> Result<serde_json::Value, String> {
    // 1. Generate new keys and write them to Keyring
    let (pubkey, privkey) =
        crate::pairing::keys::KeystoreManager::regenerate_keys().map_err(|e| e.to_string())?;

    // 2. Update state keys and clear paired devices
    let sessions = {
        let mut inner = state.inner.lock().unwrap();
        inner.pc_public_key = pubkey;
        inner.pc_private_key = privkey;
        inner.paired_devices.clear();
        std::mem::take(&mut inner.active_sessions)
    };

    for (_, session) in sessions {
        let _ = session
            .tx
            .send(axum::extract::ws::Message::Close(None))
            .await;
    }

    state.save_paired_devices();

    // 3. Generate new pairing info
    let settings = AppSettings::load();
    let endpoints = pairing_endpoints(&settings, None);
    let mcp_port = settings.advertised_mcp_http_port();
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &pubkey,
        &privkey,
        endpoints.clone(),
        settings.device_name,
        mcp_port,
    )
    .map_err(|e| e.to_string())?;

    {
        let mut inner = state.inner.lock().unwrap();
        inner.active_pairing_nonce = Some(nonce);
    }

    // 4. Emit devices-changed event
    let _ = app_handle.emit("devices-changed", ());

    Ok(json!({
        "url": pair_url,
        "fingerprint": fingerprint,
        "qr_svg": qr_svg,
        "hosts": endpoints.advertised_hosts(),
        "local_hosts": endpoints.local_hosts,
        "local_port": endpoints.local_port,
        "internet_host": endpoints.internet_host,
        "internet_port": endpoints.internet_port,
        "endpoint_mode": endpoints.mode.as_str(),
        "mcp_port": mcp_port
    }))
}

#[tauri::command]
async fn get_media_history(
    state: tauri::State<'_, AppState>,
) -> Result<Vec<crate::state::MediaItem>, String> {
    let inner = state.inner.lock().unwrap();
    Ok(inner.media_history.clone())
}

#[tauri::command]
async fn open_media_file(path: String) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(&path)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(&path)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open")
            .arg(&path)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn get_settings_path() -> std::path::PathBuf {
    let base_dir = crate::state::dirs::data_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    let app_dir = base_dir.join("CapturePort");
    let _ = std::fs::create_dir_all(&app_dir);
    app_dir.join("settings.json")
}

fn default_port() -> u16 {
    7878
}

fn default_mcp_port() -> u16 {
    7879
}

fn default_true_setting() -> bool {
    true
}

fn default_false_setting() -> bool {
    false
}

fn default_local_ip_mode() -> String {
    "auto".to_string()
}

fn default_mcp_bind_mode() -> String {
    "lan".to_string()
}

fn default_mcp_agent_mode() -> String {
    "adaptive".to_string()
}

fn default_mcp_agent_preset() -> String {
    "lan_agent".to_string()
}

fn default_empty_string() -> String {
    String::new()
}

fn default_empty_string_vec() -> Vec<String> {
    Vec::new()
}

fn default_mcp_http_auth_token() -> String {
    String::new()
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
#[allow(dead_code)]
pub(crate) struct AppSettings {
    #[serde(rename = "deviceName")]
    device_name: String,
    #[serde(default = "default_port")]
    port: u16,
    #[serde(rename = "mcpHttpPort")]
    #[serde(default = "default_mcp_port")]
    mcp_http_port: u16,
    #[serde(rename = "mcpHttpEnabled")]
    #[serde(default = "default_true_setting")]
    mcp_http_enabled: bool,
    #[serde(rename = "mcpHttpDiscoveryEnabled")]
    #[serde(default = "default_true_setting")]
    mcp_http_discovery_enabled: bool,
    #[serde(rename = "mcpHttpBindMode")]
    #[serde(default = "default_mcp_bind_mode")]
    mcp_http_bind_mode: String,
    #[serde(rename = "mcpAgentMode")]
    #[serde(default = "default_mcp_agent_mode")]
    mcp_agent_mode: String,
    #[serde(rename = "mcpAgentPreset")]
    #[serde(default = "default_mcp_agent_preset")]
    mcp_agent_preset: String,
    #[serde(rename = "mcpStreamEnabled")]
    #[serde(default = "default_false_setting")]
    mcp_stream_enabled: bool,
    #[serde(rename = "mcpMediaIndexEnabled")]
    #[serde(default = "default_true_setting")]
    mcp_media_index_enabled: bool,
    #[serde(rename = "mcpResourceReadsEnabled")]
    #[serde(default = "default_true_setting")]
    mcp_resource_reads_enabled: bool,
    #[serde(rename = "mcpInlineImagesEnabled")]
    #[serde(default = "default_true_setting")]
    mcp_inline_images_enabled: bool,
    #[serde(rename = "mcpAllowedHosts")]
    #[serde(default = "default_empty_string_vec")]
    mcp_allowed_hosts: Vec<String>,
    #[serde(rename = "mcpAllowedOrigins")]
    #[serde(default = "default_empty_string_vec")]
    mcp_allowed_origins: Vec<String>,
    #[serde(rename = "mcpHttpAuthToken")]
    #[serde(default = "default_mcp_http_auth_token")]
    mcp_http_auth_token: String,
    #[serde(rename = "mcpEnabled")]
    #[serde(default = "default_true_setting")]
    mcp_enabled: bool,
    #[serde(rename = "autoStart")]
    #[serde(default = "default_false_setting")]
    auto_start: bool,
    #[serde(rename = "closeToTray")]
    #[serde(default = "default_true_setting")]
    close_to_tray: bool,
    #[serde(rename = "localIpMode")]
    #[serde(default = "default_local_ip_mode")]
    local_ip_mode: String,
    #[serde(rename = "customLocalHost")]
    #[serde(default = "default_empty_string")]
    custom_local_host: String,
    #[serde(rename = "externalHost")]
    #[serde(default = "default_empty_string")]
    external_host: String,
    #[serde(rename = "externalPort")]
    #[serde(default = "default_port")]
    external_port: u16,
    #[serde(rename = "externalEnabled")]
    #[serde(default = "default_false_setting")]
    external_enabled: bool,
}

impl AppSettings {
    pub(crate) fn load() -> Self {
        let path = get_settings_path();
        if path.exists() {
            if let Ok(content) = std::fs::read_to_string(path) {
                if let Ok(settings) = serde_json::from_str::<AppSettings>(&content) {
                    return settings;
                }
            }
        }
        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "PC-Machine".to_string());
        AppSettings {
            device_name: hostname,
            port: 7878,
            mcp_http_port: 7879,
            mcp_http_enabled: true,
            mcp_http_discovery_enabled: true,
            mcp_http_bind_mode: default_mcp_bind_mode(),
            mcp_agent_mode: default_mcp_agent_mode(),
            mcp_agent_preset: default_mcp_agent_preset(),
            mcp_stream_enabled: false,
            mcp_media_index_enabled: true,
            mcp_resource_reads_enabled: true,
            mcp_inline_images_enabled: true,
            mcp_allowed_hosts: Vec::new(),
            mcp_allowed_origins: Vec::new(),
            mcp_http_auth_token: String::new(),
            mcp_enabled: true,
            auto_start: false,
            close_to_tray: true,
            local_ip_mode: default_local_ip_mode(),
            custom_local_host: String::new(),
            external_host: String::new(),
            external_port: 7878,
            external_enabled: false,
        }
    }

    pub(crate) fn mcp_http_active(&self) -> bool {
        self.mcp_enabled && self.mcp_http_enabled && self.mcp_http_port > 0
    }

    pub(crate) fn mcp_http_discovery_active(&self) -> bool {
        self.mcp_http_active() && self.mcp_http_discovery_enabled
    }

    pub(crate) fn active_mcp_http_port(&self) -> u16 {
        if self.mcp_http_active() {
            self.mcp_http_port
        } else {
            0
        }
    }

    pub(crate) fn advertised_mcp_http_port(&self) -> u16 {
        if self.mcp_http_discovery_active() {
            self.mcp_http_port
        } else {
            0
        }
    }

    pub(crate) fn mcp_http_bind_addr(&self) -> std::net::Ipv4Addr {
        match self.mcp_http_bind_mode.as_str() {
            "loopback" | "localhost" => std::net::Ipv4Addr::new(127, 0, 0, 1),
            _ => std::net::Ipv4Addr::new(0, 0, 0, 0),
        }
    }

    pub(crate) fn mcp_media_index_enabled(&self) -> bool {
        self.mcp_enabled && self.mcp_media_index_enabled
    }

    pub(crate) fn mcp_resource_reads_enabled(&self) -> bool {
        self.mcp_enabled && self.mcp_resource_reads_enabled
    }

    pub(crate) fn mcp_inline_images_enabled(&self) -> bool {
        self.mcp_enabled && self.mcp_inline_images_enabled
    }

    pub(crate) fn mcp_http_allowed_hosts(&self) -> Vec<String> {
        if !self.mcp_allowed_hosts.is_empty() {
            return self.mcp_allowed_hosts.clone();
        }
        let mut hosts = vec![
            "localhost".to_string(),
            "127.0.0.1".to_string(),
            "::1".to_string(),
        ];
        hosts.extend(local_pairing_hosts(self));
        hosts.sort();
        hosts.dedup();
        hosts
    }

    pub(crate) fn mcp_http_allowed_origins(&self) -> Vec<String> {
        if !self.mcp_allowed_origins.is_empty() {
            return self.mcp_allowed_origins.clone();
        }
        let mut origins = vec![
            "http://localhost".to_string(),
            "http://127.0.0.1".to_string(),
            "http://[::1]".to_string(),
        ];
        origins.extend(
            local_pairing_hosts(self)
                .into_iter()
                .map(|host| format!("http://{}:{}", host, self.mcp_http_port)),
        );
        origins.sort();
        origins.dedup();
        origins
    }

    pub(crate) fn mcp_http_auth_token(&self) -> Option<String> {
        let token = self.mcp_http_auth_token.trim();
        if token.is_empty() {
            None
        } else {
            Some(token.to_string())
        }
    }

    pub(crate) fn mcp_http_auth_enabled(&self) -> bool {
        self.mcp_http_auth_token().is_some()
    }

    pub(crate) fn save(&self) -> Result<(), String> {
        let path = get_settings_path();
        let content = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(path, content).map_err(|e| e.to_string())
    }

    pub(crate) fn apply_mcp_patch(
        &mut self,
        patch: &serde_json::Value,
    ) -> Result<Vec<String>, String> {
        let mut changed = Vec::new();
        if let Some(value) = patch.get("mcp_enabled").or_else(|| patch.get("mcpEnabled")) {
            self.mcp_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_enabled must be boolean".to_string())?;
            changed.push("mcp_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_http_enabled")
            .or_else(|| patch.get("mcpHttpEnabled"))
        {
            self.mcp_http_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_http_enabled must be boolean".to_string())?;
            changed.push("mcp_http_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_http_discovery_enabled")
            .or_else(|| patch.get("mcpHttpDiscoveryEnabled"))
        {
            self.mcp_http_discovery_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_http_discovery_enabled must be boolean".to_string())?;
            changed.push("mcp_http_discovery_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_http_port")
            .or_else(|| patch.get("mcpHttpPort"))
        {
            let port = value
                .as_u64()
                .filter(|port| (1..=65535).contains(port))
                .ok_or_else(|| "mcp_http_port must be 1..65535".to_string())?;
            self.mcp_http_port = port as u16;
            changed.push("mcp_http_port".to_string());
        }
        if let Some(value) = patch
            .get("mcp_http_bind_mode")
            .or_else(|| patch.get("mcpHttpBindMode"))
        {
            let mode = value
                .as_str()
                .ok_or_else(|| "mcp_http_bind_mode must be 'lan' or 'loopback'".to_string())?;
            if !matches!(mode, "lan" | "loopback") {
                return Err("mcp_http_bind_mode must be 'lan' or 'loopback'".to_string());
            }
            self.mcp_http_bind_mode = mode.to_string();
            changed.push("mcp_http_bind_mode".to_string());
        }
        if let Some(value) = patch
            .get("mcp_agent_mode")
            .or_else(|| patch.get("mcpAgentMode"))
        {
            let mode = value
                .as_str()
                .ok_or_else(|| "mcp_agent_mode must be 'adaptive' or 'stream'".to_string())?;
            if !matches!(mode, "adaptive" | "stream") {
                return Err("mcp_agent_mode must be 'adaptive' or 'stream'".to_string());
            }
            self.mcp_agent_mode = mode.to_string();
            changed.push("mcp_agent_mode".to_string());
        }
        if let Some(value) = patch
            .get("mcp_agent_preset")
            .or_else(|| patch.get("mcpAgentPreset"))
        {
            let preset = value
                .as_str()
                .ok_or_else(|| "mcp_agent_preset must be a string".to_string())?;
            if !matches!(
                preset,
                "privacy_first" | "local_agent" | "lan_agent" | "vision_heavy"
            ) {
                return Err("mcp_agent_preset must be privacy_first, local_agent, lan_agent, or vision_heavy".to_string());
            }
            self.mcp_agent_preset = preset.to_string();
            changed.push("mcp_agent_preset".to_string());
        }
        if let Some(value) = patch
            .get("mcp_stream_enabled")
            .or_else(|| patch.get("mcpStreamEnabled"))
        {
            self.mcp_stream_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_stream_enabled must be boolean".to_string())?;
            changed.push("mcp_stream_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_media_index_enabled")
            .or_else(|| patch.get("mcpMediaIndexEnabled"))
        {
            self.mcp_media_index_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_media_index_enabled must be boolean".to_string())?;
            changed.push("mcp_media_index_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_resource_reads_enabled")
            .or_else(|| patch.get("mcpResourceReadsEnabled"))
        {
            self.mcp_resource_reads_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_resource_reads_enabled must be boolean".to_string())?;
            changed.push("mcp_resource_reads_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_inline_images_enabled")
            .or_else(|| patch.get("mcpInlineImagesEnabled"))
        {
            self.mcp_inline_images_enabled = value
                .as_bool()
                .ok_or_else(|| "mcp_inline_images_enabled must be boolean".to_string())?;
            changed.push("mcp_inline_images_enabled".to_string());
        }
        if let Some(value) = patch
            .get("mcp_allowed_hosts")
            .or_else(|| patch.get("mcpAllowedHosts"))
        {
            self.mcp_allowed_hosts = parse_string_array_setting(value, "mcp_allowed_hosts")?;
            changed.push("mcp_allowed_hosts".to_string());
        }
        if let Some(value) = patch
            .get("mcp_allowed_origins")
            .or_else(|| patch.get("mcpAllowedOrigins"))
        {
            self.mcp_allowed_origins = parse_string_array_setting(value, "mcp_allowed_origins")?;
            changed.push("mcp_allowed_origins".to_string());
        }
        if let Some(value) = patch
            .get("mcp_http_auth_token")
            .or_else(|| patch.get("mcpHttpAuthToken"))
        {
            let token = value
                .as_str()
                .ok_or_else(|| "mcp_http_auth_token must be a string".to_string())?
                .trim()
                .to_string();
            if !token.is_empty() && token.len() < 12 {
                return Err(
                    "mcp_http_auth_token must be empty or at least 12 characters".to_string(),
                );
            }
            self.mcp_http_auth_token = token;
            changed.push("mcp_http_auth_token".to_string());
        }
        Ok(changed)
    }

    pub(crate) fn mcp_settings_summary(&self) -> serde_json::Value {
        json!({
            "mcp_enabled": self.mcp_enabled,
            "mcp_http_enabled": self.mcp_http_enabled,
            "mcp_http_active": self.mcp_http_active(),
            "mcp_http_port": self.mcp_http_port,
            "mcp_http_discovery_enabled": self.mcp_http_discovery_enabled,
            "mcp_http_discovery_active": self.mcp_http_discovery_active(),
            "mcp_http_bind_mode": self.mcp_http_bind_mode,
            "mcp_http_bind_addr": self.mcp_http_bind_addr().to_string(),
            "mcp_agent_mode": self.mcp_agent_mode,
            "mcp_agent_preset": self.mcp_agent_preset,
            "mcp_stream_enabled": self.mcp_stream_enabled,
            "mcp_media_index_enabled": self.mcp_media_index_enabled,
            "mcp_resource_reads_enabled": self.mcp_resource_reads_enabled,
            "mcp_inline_images_enabled": self.mcp_inline_images_enabled,
            "mcp_allowed_hosts": self.mcp_http_allowed_hosts(),
            "mcp_allowed_origins": self.mcp_http_allowed_origins(),
            "mcp_http_auth_enabled": self.mcp_http_auth_enabled(),
            "mcp_http_auth_token": if self.mcp_http_auth_enabled() { "redacted" } else { "" },
            "stream_mode_note": "adaptive request/response tools are the primary path; stream mode is exposed as an opt-in setting for clients that support resource updates",
        })
    }
}

fn parse_string_array_setting(
    value: &serde_json::Value,
    name: &str,
) -> Result<Vec<String>, String> {
    value
        .as_array()
        .ok_or_else(|| format!("{name} must be an array of strings"))?
        .iter()
        .map(|item| {
            item.as_str()
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .ok_or_else(|| format!("{name} must contain only non-empty strings"))
        })
        .collect()
}

pub(crate) fn local_pairing_hosts(settings: &AppSettings) -> Vec<String> {
    if settings.local_ip_mode == "custom" {
        let custom = settings.custom_local_host.trim();
        if !custom.is_empty() {
            return vec![custom.to_string()];
        }
    }

    QrGenerator::get_pairing_hosts()
}

fn pairing_endpoints(settings: &AppSettings, endpoint_mode: Option<&str>) -> PairingEndpoints {
    let mode = EndpointMode::from_str(endpoint_mode.unwrap_or("local-only")).unwrap();
    let internet_host = settings
        .external_enabled
        .then(|| settings.external_host.trim().to_string())
        .filter(|host| !host.is_empty());
    let internet_port = internet_host.as_ref().map(|_| settings.external_port);

    PairingEndpoints {
        local_hosts: local_pairing_hosts(settings),
        local_port: settings.port,
        internet_host,
        internet_port,
        mode,
    }
}

#[tauri::command]
async fn get_settings() -> Result<serde_json::Value, String> {
    serde_json::to_value(AppSettings::load()).map_err(|e| e.to_string())
}

#[tauri::command]
async fn save_settings(
    state: tauri::State<'_, AppState>,
    new_settings: serde_json::Value,
) -> Result<(), String> {
    let settings: AppSettings =
        serde_json::from_value(new_settings).map_err(|e| format!("Invalid settings: {e}"))?;
    settings.save()?;
    state
        .close_to_tray
        .store(settings.close_to_tray, Ordering::Relaxed);
    tracing::info!("Settings saved successfully to disk: {:?}", settings);
    Ok(())
}

#[tauri::command]
async fn detect_local_advertised_ip() -> Result<String, String> {
    QrGenerator::get_local_ip().ok_or_else(|| "No local address found".to_string())
}

#[tauri::command]
async fn detect_public_ip() -> Result<String, String> {
    let response = reqwest::get("https://api.ipify.org")
        .await
        .map_err(|e| e.to_string())?;

    if !response.status().is_success() {
        return Err(format!(
            "Public IP lookup failed with HTTP {}",
            response.status()
        ));
    }

    response
        .text()
        .await
        .map(|ip| ip.trim().to_string())
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn open_firewall_port(port: u16) -> Result<String, String> {
    if port == 0 {
        return Err("Invalid port".to_string());
    }

    #[cfg(target_os = "windows")]
    {
        let rule = format!(
            "advfirewall firewall add rule name=\"CapturePort {}\" dir=in action=allow protocol=TCP localport={}",
            port, port
        );
        std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-Command",
                &format!("Start-Process netsh -ArgumentList '{}' -Verb RunAs", rule),
            ])
            .spawn()
            .map_err(|e| e.to_string())?;
        return Ok("Windows Firewall prompt opened.".to_string());
    }

    #[cfg(target_os = "macos")]
    {
        let script = "do shell script \"/usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate on\" with administrator privileges";
        std::process::Command::new("osascript")
            .args(["-e", script])
            .spawn()
            .map_err(|e| e.to_string())?;
        return Ok("macOS firewall authorization prompt opened.".to_string());
    }

    #[cfg(target_os = "linux")]
    {
        let command = format!(
            "if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --add-port={0}/tcp --permanent && firewall-cmd --reload; elif command -v ufw >/dev/null 2>&1; then ufw allow {0}/tcp; else exit 127; fi",
            port
        );
        std::process::Command::new("pkexec")
            .args(["sh", "-c", &command])
            .spawn()
            .map_err(|e| e.to_string())?;
        return Ok("Linux firewall authorization prompt opened.".to_string());
    }

    #[allow(unreachable_code)]
    Err("Automatic firewall setup is not available on this platform.".to_string())
}

async fn is_local_tcp_port_open(port: u16) -> bool {
    tokio::time::timeout(
        std::time::Duration::from_millis(200),
        tokio::net::TcpStream::connect(("127.0.0.1", port)),
    )
    .await
    .map(|result| result.is_ok())
    .unwrap_or(false)
}

fn response_body_to_json_lines(body: &str) -> Vec<String> {
    let trimmed = body.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    if trimmed.starts_with('{') || trimmed.starts_with('[') {
        return vec![trimmed.to_string()];
    }

    trimmed
        .lines()
        .filter_map(|line| line.strip_prefix("data: "))
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .filter(|line| *line != "[DONE]")
        .map(ToString::to_string)
        .collect()
}

fn build_mcp_http_request(
    client: &reqwest::Client,
    url: &str,
    body: String,
    auth_token: Option<String>,
) -> reqwest::RequestBuilder {
    let mut request = client
        .post(url)
        .header(reqwest::header::CONTENT_TYPE, "application/json")
        .header(
            reqwest::header::ACCEPT,
            "application/json, text/event-stream",
        )
        .body(body);
    if let Some(token) = auth_token {
        request = request.bearer_auth(token);
    }
    request
}

async fn proxy_stdio_to_mcp_http(port: u16) -> anyhow::Result<()> {
    use tokio::io::{AsyncBufReadExt, BufReader};

    let client = reqwest::Client::new();
    let url = format!("http://127.0.0.1:{port}/mcp");
    let auth_token = AppSettings::load().mcp_http_auth_token();
    let stdin = tokio::io::stdin();
    let mut reader = BufReader::new(stdin);
    let mut line = String::new();

    while reader.read_line(&mut line).await? > 0 {
        let trimmed = line.trim();
        if !trimmed.is_empty() {
            let request: serde_json::Value = match serde_json::from_str(trimmed) {
                Ok(value) => value,
                Err(_) => {
                    println!(
                        "{}",
                        serde_json::json!({
                            "jsonrpc": "2.0",
                            "error": {
                                "code": -32700,
                                "message": "Parse error"
                            },
                            "id": serde_json::Value::Null
                        })
                    );
                    line.clear();
                    continue;
                }
            };
            let is_notification = request.get("id").is_none();
            let response =
                build_mcp_http_request(&client, &url, trimmed.to_string(), auth_token.clone())
                    .send()
                    .await?;
            let status = response.status();
            let body = response.text().await?;
            if !status.is_success() {
                tracing::error!("MCP HTTP proxy request failed: {} {}", status, body);
            } else if !is_notification {
                for json_line in response_body_to_json_lines(&body) {
                    println!("{json_line}");
                }
            }
        }
        line.clear();
    }

    Ok(())
}

// Public function for the captureport-mcp binary to run stdio MCP
pub fn run_mcp_stdio() {
    // Headless MCP Server Mode over stdio.
    // Direct tracing logs to stderr to prevent breaking the standard stdout JSON-RPC channel.
    let _ = tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .try_init();

    let (pubkey, privkey) = crate::pairing::keys::KeystoreManager::get_or_create_keys()
        .expect("Failed to initialize cryptographic pairing keys");

    let settings = AppSettings::load();
    let state = AppState::new(pubkey, privkey, settings.close_to_tray);

    let port = settings.port;
    let mcp_port = settings.active_mcp_http_port();
    let mcp_bind_addr = settings.mcp_http_bind_addr();
    let mcp_enabled = settings.mcp_http_active();
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        if mcp_enabled && is_local_tcp_port_open(mcp_port).await {
            if let Err(e) = proxy_stdio_to_mcp_http(mcp_port).await {
                tracing::error!("MCP stdio HTTP proxy failed: {:?}", e);
            }
            return;
        }

        // Spawn axum socket server on LAN port in background (headless, no AppHandle)
        if let Err(e) = crate::ws::WsServer::start(state.clone(), None, port).await {
            tracing::error!("axum WebSocket server failed to start: {:?}", e);
            return;
        }

        // Start MCP HTTP server if enabled
        if mcp_enabled {
            if let Err(e) = McpHttpServer::start(state.clone(), None, mcp_bind_addr, mcp_port).await
            {
                tracing::error!("MCP HTTP server failed to start: {:?}", e);
            }
        }

        // Block on standard stdio MCP JSON-RPC reader loop
        crate::mcp::McpServer::run_stdio_loop(state).await;
    });
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let args: Vec<String> = std::env::args().collect();
    let is_mcp_stdio = args.contains(&"--mcp-stdio".to_string());

    if is_mcp_stdio {
        run_mcp_stdio();
        return;
    }

    // Standard Tauri Desktop Tray GUI Mode.
    tracing_subscriber::fmt::init();

    let (pubkey, privkey) = crate::pairing::keys::KeystoreManager::get_or_create_keys()
        .expect("Failed to initialize cryptographic pairing keys");

    let initial_settings = AppSettings::load();
    let state = AppState::new(pubkey, privkey, initial_settings.close_to_tray);
    let ws_state = state.clone();
    let mdns_state = state.clone();
    let reaper_state = state.clone();
    let mcp_http_state = state.clone();

    tauri::Builder::default()
        .manage(state)
        .plugin(tauri_plugin_opener::init())
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let close_to_tray = window
                    .app_handle()
                    .state::<AppState>()
                    .close_to_tray
                    .load(Ordering::Relaxed);
                if close_to_tray {
                    api.prevent_close();
                    let _ = window.hide();
                    let _ = window.set_skip_taskbar(true);
                }
            }
        })
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let server_handle = app.handle().clone();
            let mcp_handle = app.handle().clone();
            let settings = AppSettings::load();
            let port = settings.port;
            let mcp_port = settings.active_mcp_http_port();
            let advertised_mcp_port = settings.advertised_mcp_http_port();
            let mcp_bind_addr = settings.mcp_http_bind_addr();
            let mcp_enabled = settings.mcp_http_active();

            // 1. Spawn axum server (gui mode, pass AppHandle)
            tauri::async_runtime::spawn(async move {
                if let Err(e) =
                    crate::ws::WsServer::start(ws_state, Some(server_handle), port).await
                {
                    tracing::error!("axum WebSocket server failed to start: {:?}", e);
                }
            });

            // 2. Spawn mDNS advertiser and store inside AppState to keep it alive
            let mdns_state_clone = mdns_state.clone();
            tauri::async_runtime::spawn(async move {
                let hosts = crate::local_pairing_hosts(&crate::AppSettings::load());
                match crate::mdns::MdnsAdvertiser::start_with_mcp(port, advertised_mcp_port, hosts)
                {
                    Ok(adv) => {
                        let mut inner = mdns_state_clone.inner.lock().unwrap();
                        inner.mdns_advertiser = Some(adv);
                    }
                    Err(e) => {
                        tracing::error!("mDNS advertiser failed to start: {:?}", e);
                    }
                }
            });

            // 3. Start MCP HTTP server if enabled
            if mcp_enabled {
                tauri::async_runtime::spawn(async move {
                    if let Err(e) = McpHttpServer::start(
                        mcp_http_state.clone(),
                        Some(mcp_handle.clone()),
                        mcp_bind_addr,
                        mcp_port,
                    )
                    .await
                    {
                        tracing::error!("MCP HTTP server failed to start: {:?}", e);
                    }
                });
            }

            // 3. Start UDP Broadcast Emitter
            let pc_public_key = {
                let inner = mdns_state.inner.lock().unwrap();
                inner.pc_public_key
            };
            crate::net::start_udp_broadcast(port, pc_public_key);

            // 4. Spawn periodic correlation map reaper task (every 5 seconds)
            let reaper_weak = std::sync::Arc::downgrade(&reaper_state.inner);
            tauri::async_runtime::spawn(async move {
                let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));
                loop {
                    interval.tick().await;
                    let inner_arc = match reaper_weak.upgrade() {
                        Some(arc) => arc,
                        None => break,
                    };
                    let mut inner = inner_arc.lock().unwrap();
                    let now = std::time::Instant::now();
                    let mut expired_ids = Vec::new();
                    for (id, req) in &inner.pending_requests {
                        if req.deadline < now {
                            expired_ids.push(id.clone());
                        }
                    }
                    for id in expired_ids {
                        if let Some(req) = inner.pending_requests.remove(&id) {
                            let _ = req.sender.send(Err(format!("Request {} timed out", id)));
                        }
                    }
                }
            });

            // Setup native tray menu without crashing the entire app on tray init failure.
            if let Err(error) = crate::tray::TrayManager::create_tray(&app_handle) {
                tracing::error!("Failed to create tray icon: {:?}", error);
            }

            // Only run minimized if standard --minimized argument is passed
            let args: Vec<String> = std::env::args().collect();
            if args.contains(&"--minimized".to_string()) {
                if let Some(main_win) = app.get_webview_window("main") {
                    let _ = main_win.hide();
                    let _ = main_win.set_skip_taskbar(true);
                }
            } else if let Some(main_win) = app.get_webview_window("main") {
                let _ = main_win.show();
                let _ = main_win.set_focus();
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_pairing_info,
            get_media_history,
            open_media_file,
            get_settings,
            save_settings,
            detect_local_advertised_ip,
            detect_public_ip,
            open_firewall_port,
            get_paired_devices,
            rename_paired_device,
            set_device_mcp_exposure,
            unpair_device,
            regenerate_pc_identity
        ])
        .run(tauri::generate_context!())
        .expect("Error occurred during Tauri runtime");
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn mcp_http_respects_legacy_mcp_enabled_flag() {
        let settings: AppSettings = serde_json::from_value(json!({
            "deviceName": "pc",
            "mcpEnabled": false
        }))
        .unwrap();

        assert!(!settings.mcp_http_active());
        assert_eq!(settings.active_mcp_http_port(), 0);
        assert_eq!(settings.advertised_mcp_http_port(), 0);
    }

    #[test]
    fn mcp_http_defaults_are_discoverable_and_adaptive() {
        let settings: AppSettings = serde_json::from_value(json!({
            "deviceName": "pc"
        }))
        .unwrap();

        assert!(settings.mcp_http_active());
        assert!(settings.mcp_http_discovery_active());
        assert_eq!(settings.active_mcp_http_port(), 7879);
        assert_eq!(
            settings.mcp_http_bind_addr(),
            std::net::Ipv4Addr::new(0, 0, 0, 0)
        );
        assert_eq!(settings.mcp_agent_mode, "adaptive");
        assert!(!settings.mcp_stream_enabled);
    }

    #[test]
    fn mcp_http_auth_token_is_redacted_in_summary() {
        let mut settings: AppSettings = serde_json::from_value(json!({
            "deviceName": "pc",
            "mcpHttpAuthToken": "  secret-token-123  "
        }))
        .unwrap();

        assert_eq!(
            settings.mcp_http_auth_token(),
            Some("secret-token-123".to_string())
        );
        assert!(settings.mcp_http_auth_enabled());
        let summary = settings.mcp_settings_summary();
        assert_eq!(summary["mcp_http_auth_enabled"], true);
        assert_eq!(summary["mcp_http_auth_token"], "redacted");

        let changed = settings
            .apply_mcp_patch(&json!({ "mcp_http_auth_token": "" }))
            .unwrap();
        assert!(changed.contains(&"mcp_http_auth_token".to_string()));
        assert_eq!(settings.mcp_http_auth_token(), None);
    }

    #[test]
    fn mcp_http_auth_token_rejects_short_values() {
        let mut settings: AppSettings = serde_json::from_value(json!({
            "deviceName": "pc"
        }))
        .unwrap();

        let err = settings
            .apply_mcp_patch(&json!({ "mcpHttpAuthToken": "short" }))
            .unwrap_err();
        assert!(err.contains("at least 12 characters"));
    }

    #[test]
    fn stdio_proxy_request_adds_bearer_auth_when_configured() {
        let client = reqwest::Client::new();
        let request = build_mcp_http_request(
            &client,
            "http://127.0.0.1:7879/mcp",
            "{}".to_string(),
            Some("secret-token-123".to_string()),
        )
        .build()
        .unwrap();

        assert_eq!(
            request
                .headers()
                .get(reqwest::header::AUTHORIZATION)
                .unwrap()
                .to_str()
                .unwrap(),
            "Bearer secret-token-123"
        );
    }

    #[test]
    fn stdio_proxy_extracts_json_from_sse_response() {
        let lines = response_body_to_json_lines(
            "event: message\n\
             data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n",
        );

        assert_eq!(
            lines,
            vec!["{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"]
        );
    }
}
