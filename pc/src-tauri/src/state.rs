use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tokio::sync::oneshot;

fn default_true() -> bool {
    true
}

fn default_empty_string() -> String {
    String::new()
}

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    #[serde(default = "default_empty_string")]
    pub alias: String,
    pub os: String,
    pub host: String,
    pub port: u16,
    pub token: String,
    pub public_key: [u8; 32],
    pub last_seen_ms: u64,
    pub pinned: bool,
    #[serde(default = "default_true")]
    pub exposed_to_mcp: bool,
}

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct MediaItem {
    pub id: String,
    pub kind: String, // "photo" or "video"
    pub path: String,
    pub timestamp: u64,
    pub size_bytes: u64,
    pub width: u32,
    pub height: u32,
    pub base64_data: Option<String>, // Inline data for Svelte view
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
}

// Active websocket transmission session
pub struct WsSession {
    pub device_id: String,
    pub name: String,
    pub tx: tokio::sync::mpsc::Sender<axum::extract::ws::Message>,
}

pub struct PendingRequest {
    pub sender: oneshot::Sender<Result<serde_json::Value, String>>,
    pub deadline: Instant,
}

pub struct AppStateInner {
    // Cryptographic keys for Ed25519 pairing
    pub pc_public_key: [u8; 32],
    pub pc_private_key: [u8; 32],

    // Active connected sockets
    pub active_sessions: HashMap<String, WsSession>,

    // Registered paired devices (persistent)
    pub paired_devices: HashMap<String, DeviceInfo>,

    // In-flight WebSocket request correlations
    pub pending_requests: HashMap<String, PendingRequest>,

    // Cache of the latest 20 media items
    pub media_history: Vec<MediaItem>,

    // Single-use QR pairing nonce
    pub active_pairing_nonce: Option<[u8; 32]>,

    // Keep mDNS advertiser instance alive
    pub mdns_advertiser: Option<crate::mdns::MdnsAdvertiser>,
}

#[derive(Clone)]
pub struct AppState {
    pub inner: Arc<Mutex<AppStateInner>>,
    pub close_to_tray: Arc<AtomicBool>,
}

impl AppState {
    pub fn new(pubkey: [u8; 32], privkey: [u8; 32], close_to_tray: bool) -> Self {
        let state = Self {
            inner: Arc::new(Mutex::new(AppStateInner {
                pc_public_key: pubkey,
                pc_private_key: privkey,
                active_sessions: HashMap::new(),
                paired_devices: HashMap::new(),
                pending_requests: HashMap::new(),
                media_history: Vec::new(),
                active_pairing_nonce: None,
                mdns_advertiser: None,
            })),
            close_to_tray: Arc::new(AtomicBool::new(close_to_tray)),
        };
        state.load_paired_devices();
        state
    }

    pub fn get_storage_path() -> PathBuf {
        let base_dir = dirs::data_dir().unwrap_or_else(|| PathBuf::from("."));
        let app_dir = base_dir.join("CapturePort");
        let _ = fs::create_dir_all(&app_dir);
        app_dir.join("devices.json")
    }

    pub fn load_paired_devices(&self) {
        let path = Self::get_storage_path();
        if path.exists() {
            if let Ok(content) = fs::read_to_string(&path) {
                if let Ok(devices) = serde_json::from_str::<HashMap<String, DeviceInfo>>(&content) {
                    let mut inner = self.inner.lock().unwrap();
                    inner.paired_devices = devices;
                    tracing::info!("Loaded paired devices from disk: {:?}", path);
                }
            }
        }
    }

    pub fn save_paired_devices(&self) {
        let path = Self::get_storage_path();
        let content = {
            let inner = self.inner.lock().unwrap();
            serde_json::to_string_pretty(&inner.paired_devices).ok()
        };
        if let Some(c) = content {
            if let Err(e) = fs::write(&path, c) {
                tracing::error!("Failed to save paired devices to disk: {:?}", e);
            }
        }
    }

    // Register active device session
    pub fn register_session(
        &self,
        device_id: String,
        name: String,
        tx: tokio::sync::mpsc::Sender<axum::extract::ws::Message>,
    ) {
        let mut inner = self.inner.lock().unwrap();
        inner.active_sessions.insert(
            device_id.clone(),
            WsSession {
                device_id,
                name,
                tx,
            },
        );
    }

    // Unregister active device session
    pub fn unregister_session(&self, device_id: &str) {
        let mut inner = self.inner.lock().unwrap();
        inner.active_sessions.remove(device_id);
    }

    // Add in-flight request for correlation
    pub fn register_request(
        &self,
        request_id: String,
        tx: oneshot::Sender<Result<serde_json::Value, String>>,
        timeout_duration: std::time::Duration,
    ) {
        let mut inner = self.inner.lock().unwrap();
        let deadline = Instant::now() + timeout_duration;
        inner.pending_requests.insert(
            request_id,
            PendingRequest {
                sender: tx,
                deadline,
            },
        );
    }

    // Resolve an in-flight request when response arrives
    pub fn complete_request(
        &self,
        request_id: &str,
        result: Result<serde_json::Value, String>,
    ) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if let Some(req) = inner.pending_requests.remove(request_id) {
            let _ = req.sender.send(result);
            true
        } else {
            false
        }
    }

    // Reap expired oneshot correlations
    pub fn reap_expired_requests(&self) {
        let mut inner = self.inner.lock().unwrap();
        let now = Instant::now();

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
}

pub mod dirs {
    use std::path::PathBuf;
    pub fn data_dir() -> Option<PathBuf> {
        #[allow(deprecated)]
        std::env::home_dir().map(|h| {
            if cfg!(target_os = "windows") {
                h.join("AppData").join("Local")
            } else if cfg!(target_os = "macos") {
                h.join("Library").join("Application Support")
            } else {
                h.join(".config")
            }
        })
    }
}
