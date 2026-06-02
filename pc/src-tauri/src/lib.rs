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

use tauri::Manager;
use serde_json::json;
use crate::state::AppState;
use crate::pairing::qr::QrGenerator;

#[tauri::command]
async fn get_pairing_info(state: tauri::State<'_, AppState>) -> Result<serde_json::Value, String> {
    let mut inner = state.inner.lock().unwrap();
    let (pair_url, fingerprint, qr_svg, nonce) = QrGenerator::generate_pairing_qr(
        &inner.pc_public_key,
        &inner.pc_private_key,
        7878
    ).map_err(|e| e.to_string())?;
    
    inner.active_pairing_nonce = Some(nonce);
    
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
        "autoStart": false
    }))
}

#[tauri::command]
async fn save_settings(new_settings: serde_json::Value) -> Result<(), String> {
    let path = get_settings_path();
    let content = serde_json::to_string_pretty(&new_settings).map_err(|e| e.to_string())?;
    std::fs::write(path, content).map_err(|e| e.to_string())?;
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

        let state = AppState::new(pubkey, privkey);
        
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            // Spawn axum socket server on LAN port 7878 in background (headless, no AppHandle)
            if let Err(e) = crate::ws::WsServer::start(state.clone(), None, 7878).await {
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

        let state = AppState::new(pubkey, privkey);
        let ws_state = state.clone();
        let mdns_state = state.clone();
        let reaper_state = state.clone();

        tauri::Builder::default()
            .manage(state)
            .plugin(tauri_plugin_opener::init())
            .setup(move |app| {
                let app_handle = app.handle().clone();
                let server_handle = app.handle().clone();
                
                // Spawn axum server (gui mode, pass AppHandle)
                tokio::spawn(async move {
                    if let Err(e) = crate::ws::WsServer::start(ws_state, Some(server_handle), 7878).await {
                        tracing::error!("axum WebSocket server failed to start: {:?}", e);
                    }
                });

                // Spawn mDNS advertiser and store inside AppState to keep it alive
                tokio::spawn(async move {
                    match crate::mdns::MdnsAdvertiser::start(7878) {
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
                tokio::spawn(async move {
                    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));
                    loop {
                        interval.tick().await;
                        reaper_state.reap_expired_requests();
                    }
                });

                // Setup native tray menu
                crate::tray::TrayManager::create_tray(&app_handle).unwrap();
                
                // On Windows/Linux, do not create default visible main window on startup.
                // It runs safely in the background tray menu.
                if let Some(main_win) = app.get_webview_window("main") {
                    let _ = main_win.close();
                }

                Ok(())
            })
            .invoke_handler(tauri::generate_handler![
                get_pairing_info,
                get_media_history,
                open_media_file,
                get_settings,
                save_settings
            ])
            .run(tauri::generate_context!())
            .expect("Error occurred during Tauri runtime");
    }
}
