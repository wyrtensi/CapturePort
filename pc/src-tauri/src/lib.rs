pub mod state;
pub mod tray;
pub mod pairing {
    pub mod keys;
    pub mod qr;
}
pub mod ws;
pub mod clipboard;
pub mod mcp;
pub mod mdns;

use tauri::{Manager, Emitter};
use serde_json::json;
use std::sync::atomic::Ordering;
use crate::state::AppState;
use crate::pairing::qr::QrGenerator;

#[tauri::command]
async fn get_pairing_info(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, String> {
    let mut inner = state.inner.lock().unwrap();
    let settings = AppSettings::load();
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &inner.pc_public_key,
        &inner.pc_private_key,
        settings.port
    ).map_err(|e| e.to_string())?;
    
    inner.active_pairing_nonce = Some(nonce);
    
    Ok(json!({
        "url": pair_url,
        "fingerprint": fingerprint,
        "qr_svg": qr_svg
    }))
}

#[derive(serde::Serialize)]
pub struct PairedDeviceResponse {
    pub id: String,
    pub name: String,
    pub os: String,
    pub online: bool,
    pub last_seen_ms: u64,
    pub exposed_to_mcp: bool,
}

#[tauri::command]
async fn get_paired_devices(state: tauri::State<'_, AppState>) -> Result<Vec<PairedDeviceResponse>, String> {
    let inner = state.inner.lock().unwrap();
    let mut list = Vec::new();
    for (id, dev) in &inner.paired_devices {
        let online = inner.active_sessions.contains_key(id);
        list.push(PairedDeviceResponse {
            id: id.clone(),
            name: dev.name.clone(),
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
    let (pubkey, privkey) = crate::pairing::keys::KeystoreManager::regenerate_keys()
        .map_err(|e| e.to_string())?;

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
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &pubkey,
        &privkey,
        settings.port
    ).map_err(|e| e.to_string())?;

    {
        let mut inner = state.inner.lock().unwrap();
        inner.active_pairing_nonce = Some(nonce);
    }

    // 4. Emit devices-changed event
    let _ = app_handle.emit("devices-changed", ());

    Ok(json!({
        "url": pair_url,
        "fingerprint": fingerprint,
        "qr_svg": qr_svg
    }))
}

#[tauri::command]
async fn get_media_history(state: tauri::State<'_, AppState>) -> Result<Vec<crate::state::MediaItem>, String> {
    let inner = state.inner.lock().unwrap();
    Ok(inner.media_history.clone())
}

#[tauri::command]
async fn open_media_file(path: String) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer").arg(&path).spawn().map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open").arg(&path).spawn().map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open").arg(&path).spawn().map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn get_settings_path() -> std::path::PathBuf {
    let base_dir = crate::state::dirs::data_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    let app_dir = base_dir.join("CapturePort");
    let _ = std::fs::create_dir_all(&app_dir);
    app_dir.join("settings.json")
}

#[derive(serde::Deserialize)]
#[allow(dead_code)]
struct AppSettings {
    #[serde(rename = "deviceName")]
    device_name: String,
    port: u16,
    #[serde(rename = "mcpEnabled")]
    mcp_enabled: bool,
    #[serde(rename = "autoStart")]
    auto_start: bool,
    #[serde(rename = "closeToTray")]
    close_to_tray: bool,
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
        }
    }
}

#[tauri::command]
async fn get_settings() -> Result<serde_json::Value, String> {
    let path = get_settings_path();
    if path.exists() {
        if let Ok(content) = std::fs::read_to_string(path) {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                return Ok(json);
            }
        }
    }
    
    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "PC-Machine".to_string());
    
    Ok(json!({
        "deviceName": hostname,
        "port": 7878,
        "mcpEnabled": true,
        "autoStart": false,
        "closeToTray": true
    }))
}

#[tauri::command]
async fn save_settings(
    state: tauri::State<'_, AppState>,
    new_settings: serde_json::Value,
) -> Result<(), String> {
    let path = get_settings_path();
    let content = serde_json::to_string_pretty(&new_settings).map_err(|e| e.to_string())?;
    std::fs::write(path, content).map_err(|e| e.to_string())?;
    if let Some(value) = new_settings.get("closeToTray").and_then(|v| v.as_bool()) {
        state.close_to_tray.store(value, Ordering::Relaxed);
    }
    tracing::info!("Settings saved successfully to disk: {:?}", new_settings);
    Ok(())
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
                
                // Spawn axum server (gui mode, pass AppHandle)
                tauri::async_runtime::spawn(async move {
                    if let Err(e) = crate::ws::WsServer::start(ws_state, Some(server_handle), port).await {
                        tracing::error!("axum WebSocket server failed to start: {:?}", e);
                    }
                });

                // Spawn mDNS advertiser and store inside AppState to keep it alive
                tauri::async_runtime::spawn(async move {
                    match crate::mdns::MdnsAdvertiser::start(port) {
                        Ok(adv) => {
                            let mut inner = mdns_state.inner.lock().unwrap();
                            inner.mdns_advertiser = Some(adv);
                        }
                        Err(e) => {
                            tracing::error!("mDNS advertiser failed to start: {:?}", e);
                        }
                    }
                });

                // Spawn periodic correlation map reaper task (every 5 seconds)
                tauri::async_runtime::spawn(async move {
                    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));
                    loop {
                        interval.tick().await;
                        reaper_state.reap_expired_requests();
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
                get_paired_devices,
                set_device_mcp_exposure,
                unpair_device,
                regenerate_pc_identity
            ])
            .run(tauri::generate_context!())
            .expect("Error occurred during Tauri runtime");
    }
}
