use axum::extract::ws::{Message, WebSocket};
use futures_util::{StreamExt, SinkExt};
use std::time::SystemTime;
use base64::prelude::*;
use ed25519_dalek::{VerifyingKey, Signature, Verifier};
use serde_json::json;
use crate::state::{AppState, DeviceInfo, MediaItem};
use crate::ws::envelope::{Envelope, BinaryFrame};
use crate::clipboard::{get_platform_sink, ClipboardSink};
use tauri::Emitter;

fn is_valid_request_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_alphanumeric() || c == '_' || c == '-')
}

struct IntermediatePairing {
    pubkey_phone: [u8; 32],
    nonce_pc: [u8; 32],
    device_name: String,
    os: String,
}

pub struct SocketHandler;

impl SocketHandler {
    // Top-level WS handler spawned per-client
    pub async fn handle_socket(socket: WebSocket, state: AppState, app_handle: Option<tauri::AppHandle>) {
        let (mut sender, mut receiver) = socket.split();
        let (tx, mut rx) = tokio::sync::mpsc::channel::<Message>(100);

        // Spawn a background task to write outgoing messages from our channel to the WebSocket
        let sender_task = tokio::spawn(async move {
            while let Some(msg) = rx.recv().await {
                if sender.send(msg).await.is_err() {
                    break;
                }
            }
        });

        let mut authenticated_device_id: Option<String> = None;
        let mut pairing_state: Option<IntermediatePairing> = None;

        // Reading WebSocket messages loop
        while let Some(Ok(msg)) = receiver.next().await {
            #[allow(clippy::collapsible_match)]
            match msg {
                Message::Text(text) => {
                    let envelope: Envelope = match serde_json::from_str(&text) {
                        Ok(env) => env,
                        Err(e) => {
                            tracing::error!("Failed to parse JSON text envelope: {:?}", e);
                            continue;
                        }
                    };

                    // Execute authorization flow
                    if authenticated_device_id.is_none() {
                        if envelope.t == "req" && envelope.method.as_deref() == Some("hello") {
                            let params = envelope.params.clone().unwrap_or(serde_json::Value::Null);
                            
                            // Check if regular steady-state login
                            if let (Some(device_id), Some(token)) = (
                                params.get("device_id").and_then(|v| v.as_str()),
                                params.get("token").and_then(|v| v.as_str())
                            ) {
                                let valid = {
                                    let inner = state.inner.lock().unwrap();
                                    inner.paired_devices.get(device_id)
                                        .map(|d| d.token == token)
                                        .unwrap_or(false)
                                };

                                if valid {
                                    authenticated_device_id = Some(device_id.to_string());
                                    
                                    // Register active session
                                    let device_name = {
                                        let inner = state.inner.lock().unwrap();
                                        inner.paired_devices.get(device_id).unwrap().name.clone()
                                    };
                                    state.register_session(device_id.to_string(), device_name.clone(), tx.clone());

                                    // Reply auth ok
                                    let ack = Envelope::new_response(envelope.id, json!({
                                        "status": "authorized",
                                        "session_id": ulid::Ulid::new().to_string()
                                    }));
                                    let _ = tx.send(Message::Text(serde_json::to_string(&ack).unwrap())).await;
                                    tracing::info!("Device authorized successfully: {} ({})", device_name, device_id);
                                } else {
                                    let err = Envelope::new_error(envelope.id, 3, "Unauthorized: Unknown token".to_string());
                                    let _ = tx.send(Message::Text(serde_json::to_string(&err).unwrap())).await;
                                }
                            }
                            // Start PAIRING challenge-response with two-way cryptographic verification
                            else if let (Some(pubkey_str), Some(name), Some(os), Some(qr_nonce_str), Some(qr_sig_str)) = (
                                params.get("pubkey_phone").and_then(|v| v.as_str()),
                                params.get("device_name").and_then(|v| v.as_str()),
                                params.get("os").and_then(|v| v.as_str()),
                                params.get("qr_nonce").and_then(|v| v.as_str()),
                                params.get("qr_sig").and_then(|v| v.as_str())
                            ) {
                                if let (Ok(pubkey_bytes), Ok(qr_nonce_bytes), Ok(qr_sig_bytes)) = (
                                    BASE64_URL_SAFE_NO_PAD.decode(pubkey_str),
                                    BASE64_URL_SAFE_NO_PAD.decode(qr_nonce_str),
                                    BASE64_URL_SAFE_NO_PAD.decode(qr_sig_str)
                                ) {
                                    if pubkey_bytes.len() == 32 && qr_nonce_bytes.len() == 32 {
                                        // 1. Verify active pairing nonce matching
                                        let is_active_nonce = {
                                            let inner = state.inner.lock().unwrap();
                                            inner.active_pairing_nonce.map(|n| n == qr_nonce_bytes.as_slice()).unwrap_or(false)
                                        };

                                        if !is_active_nonce {
                                            let err = Envelope::new_error(envelope.id, 4, "Invalid or expired pairing nonce".to_string());
                                            let _ = tx.send(Message::Text(serde_json::to_string(&err).unwrap())).await;
                                            return;
                                        }

                                        // 2. Verify signature under PC's public key (proving the phone physically scanned the QR code)
                                        let pc_pub_key = {
                                            let inner = state.inner.lock().unwrap();
                                            inner.pc_public_key
                                        };
                                        let verifying_key = VerifyingKey::from_bytes(&pc_pub_key);
                                        let signature = Signature::from_slice(&qr_sig_bytes);

                                        let mut qr_sig_valid = false;
                                        if let (Ok(key), Ok(sig)) = (verifying_key, signature) {
                                            if key.verify(&qr_nonce_bytes, &sig).is_ok() {
                                                qr_sig_valid = true;
                                            }
                                        }

                                        if !qr_sig_valid {
                                            let err = Envelope::new_error(envelope.id, 5, "Invalid pairing signature".to_string());
                                            let _ = tx.send(Message::Text(serde_json::to_string(&err).unwrap())).await;
                                            return;
                                        }

                                        let mut nonce_pc = [0u8; 32];
                                        use rand::Rng;
                                        rand::thread_rng().fill(&mut nonce_pc);

                                        let pubkey_array: [u8; 32] = pubkey_bytes.try_into().unwrap();

                                        pairing_state = Some(IntermediatePairing {
                                            pubkey_phone: pubkey_array,
                                            nonce_pc,
                                            device_name: name.to_string(),
                                            os: os.to_string(),
                                        });

                                        // Emit progress state to UI
                                        if let Some(h) = &app_handle {
                                            let _ = h.emit("pairing-status", "Scanning complete. Verifying signature...");
                                        }

                                        let challenge = Envelope::new_request(
                                            envelope.id,
                                            "challenge".to_string(),
                                            json!({ "nonce": BASE64_URL_SAFE_NO_PAD.encode(nonce_pc) }),
                                            None
                                        );
                                        let _ = tx.send(Message::Text(serde_json::to_string(&challenge).unwrap())).await;
                                    }
                                }
                            }
                        } else if envelope.t == "resp" && pairing_state.is_some() {
                            // Check challenge response signature
                            let state_pair = pairing_state.take().unwrap();
                            let sig_str = envelope.result.as_ref()
                                .and_then(|r| r.get("sig"))
                                .and_then(|s| s.as_str());

                            let verified = if let Some(s) = sig_str {
                                if let Ok(sig_bytes) = BASE64_URL_SAFE_NO_PAD.decode(s) {
                                    if sig_bytes.len() == 64 {
                                        let signature = Signature::from_slice(&sig_bytes);
                                        let verifying_key = VerifyingKey::from_bytes(&state_pair.pubkey_phone);
                                        
                                        if let (Ok(sig), Ok(key)) = (signature, verifying_key) {
                                            key.verify(&state_pair.nonce_pc, &sig).is_ok()
                                        } else {
                                            false
                                        }
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            } else {
                                false
                            };

                            if verified {
                                let device_id = ulid::Ulid::new().to_string();
                                let token = BASE64_URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>());

                                // Calculate fingerprint
                                use ring::digest::{digest, SHA256};
                                let hash = digest(&SHA256, &state_pair.pubkey_phone);
                                let hash_bytes = hash.as_ref();
                                let fingerprint = format!(
                                    "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
                                    hash_bytes[0], hash_bytes[1], hash_bytes[2], hash_bytes[3],
                                    hash_bytes[4], hash_bytes[5], hash_bytes[6], hash_bytes[7]
                                );

                                let device_info = DeviceInfo {
                                    id: device_id.clone(),
                                    name: state_pair.device_name.clone(),
                                    os: state_pair.os.clone(),
                                    host: "".to_string(), // Filled in on broadcast
                                    port: 0,
                                    token: token.clone(),
                                    public_key: state_pair.pubkey_phone,
                                    last_seen_ms: SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis() as u64,
                                    pinned: false,
                                };

                                {
                                    let mut inner = state.inner.lock().unwrap();
                                    inner.paired_devices.insert(device_id.clone(), device_info);
                                }
                                state.save_paired_devices();

                                authenticated_device_id = Some(device_id.clone());
                                state.register_session(device_id.clone(), state_pair.device_name.clone(), tx.clone());

                                if let Some(h) = &app_handle {
                                    let _ = h.emit("pairing-status", "Fingerprint verified. Paired!");
                                }

                                let paired_resp = Envelope::new_response(envelope.id, json!({
                                    "status": "paired",
                                    "device_id": device_id,
                                    "token": token,
                                    "fingerprint_phone": fingerprint
                                }));
                                let _ = tx.send(Message::Text(serde_json::to_string(&paired_resp).unwrap())).await;
                            } else {
                                if let Some(h) = &app_handle {
                                    let _ = h.emit("pairing-status", "Fingerprint mismatch. Pairing rejected.");
                                }
                                let err = Envelope::new_error(envelope.id, 3, "Signature verification failed".to_string());
                                let _ = tx.send(Message::Text(serde_json::to_string(&err).unwrap())).await;
                            }
                        }
                    } else {
                        // STEADY STATE TEXT MESSAGES
                        if envelope.t == "resp" {
                            // Forward response to pending MCP oneshot queues
                            let result_val = if let Some(res) = envelope.result {
                                Ok(res)
                            } else if let Some(err) = envelope.error {
                                Err(err.message)
                            } else {
                                Err("Unknown socket response structure".to_string())
                            };
                            state.complete_request(&envelope.id, result_val);
                        }
                    }
                }
                Message::Binary(bytes) => {
                    // PROCESS BINARY TRANSMISSION
                    if authenticated_device_id.is_some() {
                        if let Ok(frame) = BinaryFrame::decode(&bytes) {
                            match frame.stream_id {
                                0 => {
                                    // PHOTO STREAM (JPEG metadata photo)
                                    let timestamp = SystemTime::now()
                                        .duration_since(SystemTime::UNIX_EPOCH)
                                        .unwrap_or_default()
                                        .as_millis() as u64;

                                    let file_name = format!("CP_{}_{}.jpg", timestamp, frame.frame_seq);
                                    let pictures_dir = dirs::picture_dir()
                                        .unwrap_or_else(|| std::path::PathBuf::from("."))
                                        .join("CapturePort");
                                    
                                    let _ = std::fs::create_dir_all(&pictures_dir);
                                    let file_path = pictures_dir.join(file_name);

                                    // Save file to disk
                                    if std::fs::write(&file_path, &frame.payload).is_ok() {
                                        // Copy to OS Clipboard
                                        let sink = get_platform_sink();
                                        if let Err(e) = sink.put_image(&frame.payload) {
                                            tracing::error!("Failed to copy image to clipboard: {:?}", e);
                                        }

                                        // Encode base64 thumbnail for UI
                                        let b64_data = BASE64_STANDARD.encode(&frame.payload);

                                        let media_item = MediaItem {
                                            id: ulid::Ulid::new().to_string(),
                                            kind: "photo".to_string(),
                                            path: file_path.to_string_lossy().to_string(),
                                            timestamp,
                                            size_bytes: frame.payload.len() as u64,
                                            width: 1920, // Approximate standard downscale
                                            height: 1080,
                                            base64_data: Some(b64_data),
                                        };

                                        {
                                            let mut inner = state.inner.lock().unwrap();
                                            inner.media_history.insert(0, media_item.clone());
                                            if inner.media_history.len() > 20 {
                                                inner.media_history.truncate(20);
                                            }
                                        }

                                        // Emit event to UI
                                        if let Some(h) = &app_handle {
                                            let _ = h.emit("media-received", media_item);
                                        }
                                    }
                                }
                                1 => {
                                    // VIDEO CHUNK TRANSMISSION ASSEMBLY
                                     let request_id = frame.meta.get("request_id")
                                         .and_then(|r| r.as_str())
                                         .unwrap_or("default_video");

                                     if !is_valid_request_id(request_id) {
                                         tracing::error!("Invalid request_id detected in video stream: {:?}", request_id);
                                         break;
                                     }

                                    let pictures_dir = dirs::picture_dir()
                                        .unwrap_or_else(|| std::path::PathBuf::from("."))
                                        .join("CapturePort");
                                    
                                    let temp_dir = pictures_dir.join("temp");
                                    let _ = std::fs::create_dir_all(&temp_dir);

                                    let temp_file_path = temp_dir.join(format!("{}.mp4.part", request_id));

                                    // Append chunk payload to target partial file
                                    use std::io::Write;
                                    let write_res = std::fs::OpenOptions::new()
                                        .create(true)
                                        .append(true)
                                        .open(&temp_file_path)
                                        .and_then(|mut f| f.write_all(&frame.payload));

                                    if let Err(e) = write_res {
                                        tracing::error!("Failed to write video chunk payload: {:?}", e);
                                        continue;
                                    }

                                    // Verify last chunk flag
                                    if (frame.flags & 0x1) != 0 {
                                        let timestamp = SystemTime::now()
                                            .duration_since(SystemTime::UNIX_EPOCH)
                                            .unwrap_or_default()
                                            .as_millis() as u64;

                                        let final_file_name = format!("CP_{}_{}.mp4", timestamp, request_id);
                                        let final_file_path = pictures_dir.join(final_file_name);

                                        // Move part file to final destination
                                        if std::fs::rename(&temp_file_path, &final_file_path).is_ok() {
                                            // Copy to OS Clipboard (virtual file)
                                            let sink = get_platform_sink();
                                            if let Err(e) = sink.put_file(&final_file_path) {
                                                tracing::error!("Failed to copy video to system clipboard: {:?}", e);
                                            }

                                            let size_bytes = final_file_path.metadata().map(|m| m.len()).unwrap_or(0);

                                            let media_item = MediaItem {
                                                id: ulid::Ulid::new().to_string(),
                                                kind: "video".to_string(),
                                                path: final_file_path.to_string_lossy().to_string(),
                                                timestamp,
                                                size_bytes,
                                                width: 1280,
                                                height: 720,
                                                base64_data: None, // Video files don't require inline base64 previews
                                            };

                                            {
                                                let mut inner = state.inner.lock().unwrap();
                                                inner.media_history.insert(0, media_item.clone());
                                                if inner.media_history.len() > 20 {
                                                    inner.media_history.truncate(20);
                                                }
                                            }

                                            if let Some(h) = &app_handle {
                                                let _ = h.emit("media-received", media_item);
                                            }
                                        }
                                    }
                                }
                                2 => {
                                    // MCP PHOTO CAPTURE RESULT BYPASSING CLIPBOARD
                                     let request_id = frame.meta.get("request_id")
                                         .and_then(|r| r.as_str());
                                     
                                     if let Some(req_id) = request_id {
                                         if !is_valid_request_id(req_id) {
                                             tracing::error!("Invalid request_id in photo frame: {:?}", req_id);
                                             break;
                                         }
                                        // Complete oneshot directly returning raw JPEG payload values in base64
                                        let b64_data = BASE64_STANDARD.encode(&frame.payload);
                                        state.complete_request(req_id, Ok(json!({
                                            "status": "success",
                                            "base64_data": b64_data,
                                            "mime_type": "image/jpeg",
                                            "size_bytes": frame.payload.len()
                                        })));
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                }
                _ => {}
            }
        }

        // Clean up connections on socket drops
        if let Some(device_id) = authenticated_device_id {
            state.unregister_session(&device_id);
            tracing::info!("WebSocket connection closed for device: {}", device_id);
        }

        sender_task.abort();
    }
}

pub mod dirs {
    use std::path::PathBuf;
    pub fn picture_dir() -> Option<PathBuf> {
        #[allow(deprecated)]
        std::env::home_dir().map(|h| h.join("Pictures"))
    }
}
