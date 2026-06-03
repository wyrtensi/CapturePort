pub mod state;
pub mod tray;
pub mod pairing {
    pub mod keys;
    pub mod qr;
}
pub mod clipboard;
pub mod mcp;
pub mod mdns;
pub mod net;
pub mod ws;

use crate::pairing::qr::{EndpointMode, PairingEndpoints, QrGenerator};
use crate::state::AppState;
use serde_json::json;
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
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &inner.pc_public_key,
        &inner.pc_private_key,
        endpoints.clone(),
        settings.device_name.clone(),
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
        "endpoint_mode": endpoints.mode.as_str()
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
}

#[tauri::command]
async fn get_paired_devices(
    state: tauri::State<'_, AppState>,
) -> Result<Vec<PairedDeviceResponse>, String> {
    let inner = state.inner.lock().unwrap();
    let mut list = Vec::new();
    for (id, dev) in &inner.paired_devices {
        let online = inner.active_sessions.contains_key(id);
        list.push(PairedDeviceResponse {
            id: id.clone(),
            name: dev.name.clone(),
            alias: dev.alias.clone(),
            os: dev.os.clone(),
            online,
            last_seen_ms: dev.last_seen_ms,
            exposed_to_mcp: dev.exposed_to_mcp,
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
    {
        let mut inner = state.inner.lock().unwrap();
        inner.paired_devices.remove(&id);
        inner.active_sessions.remove(&id);
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
    {
        let mut inner = state.inner.lock().unwrap();
        inner.pc_public_key = pubkey;
        inner.pc_private_key = privkey;
        inner.paired_devices.clear();
        inner.active_sessions.clear();
    }
    state.save_paired_devices();

    // 3. Generate new pairing info
    let settings = AppSettings::load();
    let endpoints = pairing_endpoints(&settings, None);
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &pubkey,
        &privkey,
        endpoints.clone(),
        settings.device_name,
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
        "endpoint_mode": endpoints.mode.as_str()
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

fn default_true_setting() -> bool {
    true
}

fn default_false_setting() -> bool {
    false
}

fn default_local_ip_mode() -> String {
    "auto".to_string()
}

fn default_empty_string() -> String {
    String::new()
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug)]
#[allow(dead_code)]
pub(crate) struct AppSettings {
    #[serde(rename = "deviceName")]
    device_name: String,
    #[serde(default = "default_port")]
    port: u16,
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
    fn load() -> Self {
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
    let mode = EndpointMode::from_str(endpoint_mode.unwrap_or("local-only"));
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
    let path = get_settings_path();
    let content = serde_json::to_string_pretty(&settings).map_err(|e| e.to_string())?;
    std::fs::write(path, content).map_err(|e| e.to_string())?;
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
    let output = if cfg!(target_os = "windows") {
        std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-Command",
                "(Invoke-RestMethod -UseBasicParsing https://api.ipify.org).Trim()",
            ])
            .output()
    } else {
        std::process::Command::new("curl")
            .args(["-fsSL", "https://api.ipify.org"])
            .output()
    }
    .map_err(|e| e.to_string())?;

    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }

    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let args: Vec<String> = std::env::args().collect();
    let is_mcp_stdio = args.contains(&"--mcp-stdio".to_string());

    if is_mcp_stdio {
        // Headless MCP Server Mode over stdio.
        // Direct tracing logs to stderr to prevent breaking the standard stdout JSON-RPC channel.
        tracing_subscriber::fmt()
            .with_writer(std::io::stderr)
            .init();

        let (pubkey, privkey) = crate::pairing::keys::KeystoreManager::get_or_create_keys()
            .expect("Failed to initialize cryptographic pairing keys");

        let settings = AppSettings::load();
        let state = AppState::new(pubkey, privkey, settings.close_to_tray);

        let port = settings.port;
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            // Spawn axum socket server on LAN port in background (headless, no AppHandle)
            if let Err(e) = crate::ws::WsServer::start(state.clone(), None, port).await {
                tracing::error!("axum WebSocket server failed to start: {:?}", e);
                return;
            }

            // Block on standard stdio MCP JSON-RPC reader loop
            crate::mcp::McpServer::run_stdio_loop(state).await;
        });
    } else {
        // Standard Tauri Desktop Tray GUI Mode.
        tracing_subscriber::fmt::init();

        let (pubkey, privkey) = crate::pairing::keys::KeystoreManager::get_or_create_keys()
            .expect("Failed to initialize cryptographic pairing keys");

        let initial_settings = AppSettings::load();
        let state = AppState::new(pubkey, privkey, initial_settings.close_to_tray);
        let ws_state = state.clone();
        let mdns_state = state.clone();
        let reaper_state = state.clone();

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
                let settings = AppSettings::load();
                let port = settings.port;

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
                    match crate::mdns::MdnsAdvertiser::start(port, hosts) {
                        Ok(adv) => {
                            let mut inner = mdns_state_clone.inner.lock().unwrap();
                            inner.mdns_advertiser = Some(adv);
                        }
                        Err(e) => {
                            tracing::error!("mDNS advertiser failed to start: {:?}", e);
                        }
                    }
                });

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
}
