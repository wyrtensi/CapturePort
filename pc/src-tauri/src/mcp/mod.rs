use serde_json::{json, Value};
use tokio::time::timeout;
use std::time::Duration;
use crate::state::AppState;
use crate::ws::envelope::Envelope;

pub struct McpServer;

impl McpServer {
    // Standard JSON-RPC stdio listening loop running in background
    pub async fn run_stdio_loop(state: AppState) {
        use tokio::io::{AsyncBufReadExt, BufReader};
        let stdin = tokio::io::stdin();
        let mut reader = BufReader::new(stdin);
        let mut line = String::new();

        tracing::info!("MCP Server stdio loop started");

        while let Ok(n) = reader.read_line(&mut line).await {
            if n == 0 {
                break; // EOF
            }

            let trimmed = line.trim();
            if !trimmed.is_empty() {
                match serde_json::from_str::<Value>(trimmed) {
                    Ok(request) => {
                        let id = request.get("id");
                        let is_notification = id.is_none() || id.unwrap().is_null();

                        let response = Self::handle_mcp_request(request, &state).await;
                        
                        // JSON-RPC 2.0: No Response object should be returned for a Notification
                        if !is_notification {
                            println!("{}", serde_json::to_string(&response).unwrap_or_default());
                        }
                    }
                    Err(_) => {
                        // Parse Error (-32700)
                        let response = json!({
                            "jsonrpc": "2.0",
                            "error": {
                                "code": -32700,
                                "message": "Parse error"
                            },
                            "id": Value::Null
                        });
                        println!("{}", serde_json::to_string(&response).unwrap_or_default());
                    }
                }
            }

            line.clear();
        }
    }

    // Handles standard MCP JSON-RPC requests (e.g. initialize, tools/list, tools/call)
    async fn handle_mcp_request(req: Value, state: &AppState) -> Value {
        let id = req.get("id").cloned().unwrap_or(Value::Null);
        
        // Structural validation for Invalid Request (-32600)
        if !req.is_object() || (req.get("method").is_none() && req.get("id").is_some()) {
            return json!({
                "jsonrpc": "2.0",
                "error": {
                    "code": -32600,
                    "message": "Invalid Request"
                },
                "id": id
            });
        }

        let method = req.get("method").and_then(|m| m.as_str()).unwrap_or("");
        
        match method {
            "initialize" => {
                json!({
                    "jsonrpc": "2.0",
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {
                            "tools": {}
                        },
                        "serverInfo": {
                            "name": "CapturePort MCP Server",
                            "version": "0.1.0"
                        }
                    },
                    "id": id
                })
            }
            "tools/list" => {
                json!({
                    "jsonrpc": "2.0",
                    "result": {
                        "tools": [
                            {
                                "name": "list_devices",
                                "description": "Retrieve list of paired mobile phones and check if they are currently online.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                }
                            },
                            {
                                "name": "capture_photo",
                                "description": "Trigger the camera on a paired phone to snap a photo and return the image directly to the agent.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "device_id": {
                                            "type": "string",
                                            "description": "Optional device UUID. Defaults to the first active device."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "snap_frame",
                                "description": "Retrieve the most recently captured photo stored in memory without snapping a new one.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                }
                            }
                        ]
                    },
                    "id": id
                })
            }
            "tools/call" => {
                let params = req.get("params").unwrap_or(&Value::Null);
                let tool_name = params.get("name").and_then(|n| n.as_str()).unwrap_or("");
                let args = params.get("arguments").unwrap_or(&Value::Null);

                let result = match tool_name {
                    "list_devices" => Self::tool_list_devices(state).await,
                    "capture_photo" => Self::tool_capture_photo(state, args).await,
                    "snap_frame" => Self::tool_snap_frame(state).await,
                    _ => Err(format!("Unknown tool: {}", tool_name)),
                };

                match result {
                    Ok(data) => {
                        json!({
                            "jsonrpc": "2.0",
                            "result": data,
                            "id": id
                        })
                    }
                    Err(err_msg) => {
                        json!({
                            "jsonrpc": "2.0",
                            "error": {
                                "code": -32603,
                                "message": err_msg
                            },
                            "id": id
                        })
                    }
                }
            }
            _ => {
                json!({
                    "jsonrpc": "2.0",
                    "error": {
                        "code": -32601,
                        "message": format!("Method not found: {}", method)
                    },
                    "id": id
                })
            }
        }
    }

    async fn tool_list_devices(state: &AppState) -> Result<Value, String> {
        let inner = state.inner.lock().unwrap();
        let mut devices_list = Vec::new();
        
        for (id, dev) in &inner.paired_devices {
            let online = inner.active_sessions.contains_key(id);
            devices_list.push(json!({
                "id": id,
                "name": dev.name,
                "os": dev.os,
                "online": online,
                "last_seen": dev.last_seen_ms
            }));
        }

        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&devices_list).unwrap_or_default()
                }
            ]
        }))
    }

    async fn tool_capture_photo(state: &AppState, args: &Value) -> Result<Value, String> {
        // 1. Select target device ID
        let target_device_id = {
            let inner = state.inner.lock().unwrap();
            let specified = args.get("device_id").and_then(|v| v.as_str());
            
            if let Some(id) = specified {
                if inner.active_sessions.contains_key(id) {
                    Some(id.to_string())
                } else {
                    return Err(format!("Specified device ID is offline or unregistered: {}", id));
                }
            } else {
                // Default to the first online session
                inner.active_sessions.keys().next().cloned()
            }
        };

        let device_id = match target_device_id {
            Some(id) => id,
            None => return Err("No active mobile devices are online. Please open the CapturePort app on your phone.".to_string()),
        };

        // 2. Fetch session sender channel
        let ws_tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        };

        let tx = match ws_tx {
            Some(t) => t,
            None => return Err("Failed to retrieve active socket channel".to_string()),
        };

        // 3. Register oneshot RPC correlation
        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(12));

        // 4. Construct and send envelope to phone
        let req_env = Envelope::new_request(
            request_id,
            "capture_photo".to_string(),
            json!({ "timeout_ms": 12000 }),
            None
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        
        if tx.send(axum::extract::ws::Message::Text(payload_str)).await.is_err() {
            return Err("Failed to push capture command onto WebSocket channel".to_string());
        }

        // 5. Await response with timeout
        match timeout(Duration::from_secs(12), oneshot_rx).await {
            Ok(Ok(Ok(result))) => {
                let base64_jpeg = result.get("base64_data")
                    .and_then(|b| b.as_str())
                    .unwrap_or("");
                
                Ok(json!({
                    "content": [
                        {
                            "type": "image",
                            "data": base64_jpeg,
                            "mimeType": "image/jpeg"
                        }
                    ]
                }))
            }
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => {
                // Timeout clean up inside state pending requests is handled automatically by reaper
                Err("Camera capture timed out. The phone did not return the image in 12 seconds.".to_string())
            }
        }
    }

    async fn tool_snap_frame(state: &AppState) -> Result<Value, String> {
        let item = {
            let inner = state.inner.lock().unwrap();
            inner.media_history.iter()
                .find(|m| m.kind == "photo" && m.base64_data.is_some())
                .cloned()
        };

        match item {
            Some(media) => {
                Ok(json!({
                    "content": [
                        {
                            "type": "image",
                            "data": media.base64_data.unwrap(),
                            "mimeType": "image/jpeg"
                        }
                    ]
                }))
            }
            None => Err("No photos are currently cached in memory history logs. Take at least one photo first.".to_string()),
        }
    }
}
