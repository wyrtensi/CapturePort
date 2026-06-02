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
                                        "device": {
                                            "type": "string",
                                            "description": "Optional device UUID, name, or substring. Defaults to the first active device."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "capture_screenshot",
                                "description": "Trigger the camera on a paired phone to capture a photo/screenshot and return the image data directly.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "device": {
                                            "type": "string",
                                            "description": "Optional device UUID, name, or substring. Defaults to the first active device."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "record_video",
                                "description": "Record video from the camera of the specified mobile device.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "device": {
                                            "type": "string",
                                            "description": "Optional device UUID, name, or substring. Defaults to the first active device."
                                        },
                                        "duration_seconds": {
                                            "type": "integer",
                                            "description": "Optional recording duration in seconds (default 10)."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "get_device_clipboard",
                                "description": "Retrieve the current clipboard content from the target mobile device.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "device": {
                                            "type": "string",
                                            "description": "Optional device UUID, name, or substring. Defaults to the first active device."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "set_device_clipboard",
                                "description": "Set the clipboard content on the target mobile device.",
                                "inputSchema": {
                                    "type": "object",
                                    "required": ["text"],
                                    "properties": {
                                        "device": {
                                            "type": "string",
                                            "description": "Optional device UUID, name, or substring. Defaults to the first active device."
                                        },
                                        "text": {
                                            "type": "string",
                                            "description": "The text content to copy to the device clipboard."
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
                    "capture_screenshot" => Self::tool_capture_screenshot(state, args).await,
                    "record_video" => Self::tool_record_video(state, args).await,
                    "get_device_clipboard" => Self::tool_get_device_clipboard(state, args).await,
                    "set_device_clipboard" => Self::tool_set_device_clipboard(state, args).await,
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

    fn resolve_active_device(state: &AppState, device_param: Option<&str>) -> Result<String, String> {
        let inner = state.inner.lock().unwrap();
        if inner.active_sessions.is_empty() {
            return Err("No active mobile devices are online. Please open the CapturePort app on your phone.".to_string());
        }

        let mut active_mcp_sessions: Vec<(&String, &crate::state::WsSession)> = inner.active_sessions.iter()
            .filter(|(id, _)| {
                inner.paired_devices.get(*id)
                    .map(|d| d.exposed_to_mcp)
                    .unwrap_or(false)
            })
            .collect();

        if active_mcp_sessions.is_empty() {
            return Err("No active mobile devices are online and exposed to MCP.".to_string());
        }

        // Deterministic fallback: pinned devices first, then by last_seen_ms descending
        active_mcp_sessions.sort_by(|(id_a, _), (id_b, _)| {
            let dev_a = inner.paired_devices.get(*id_a);
            let dev_b = inner.paired_devices.get(*id_b);
            match (dev_a, dev_b) {
                (Some(a), Some(b)) => {
                    b.pinned.cmp(&a.pinned)
                        .then_with(|| b.last_seen_ms.cmp(&a.last_seen_ms))
                }
                _ => std::cmp::Ordering::Equal,
            }
        });

        let param = match device_param {
            Some(p) if !p.trim().is_empty() => p.trim(),
            _ => return Ok(active_mcp_sessions[0].0.to_string()),
        };

        // Match exact UUID
        if let Some((id, _)) = active_mcp_sessions.iter().find(|(id, _)| id.as_str() == param) {
            return Ok(id.to_string());
        }

        // Match exact name
        if let Some((id, _)) = active_mcp_sessions.iter().find(|(_, s)| s.name == param) {
            return Ok(id.to_string());
        }

        // Case-insensitive substring match (name or UUID)
        let query_lower = param.to_lowercase();
        let matches: Vec<String> = active_mcp_sessions.iter()
            .filter(|(id, s)| {
                s.name.to_lowercase().contains(&query_lower) ||
                id.to_lowercase().contains(&query_lower)
            })
            .map(|(id, _)| id.to_string())
            .collect();

        if matches.len() == 1 {
            Ok(matches[0].clone())
        } else if matches.len() > 1 {
            Err(format!("Ambiguous device query '{}' matched multiple devices. Please specify a more precise name or UUID.", param))
        } else {
            Err(format!("No online, MCP-exposed device matches query '{}'.", param))
        }
    }

    async fn tool_list_devices(state: &AppState) -> Result<Value, String> {
        let inner = state.inner.lock().unwrap();
        let mut devices_list = Vec::new();
        
        for (id, dev) in &inner.paired_devices {
            if dev.exposed_to_mcp {
                let online = inner.active_sessions.contains_key(id);
                devices_list.push(json!({
                    "id": id,
                    "name": dev.name,
                    "os": dev.os,
                    "online": online,
                    "last_seen": dev.last_seen_ms
                }));
            }
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
        let device_param = args.get("device").and_then(|v| v.as_str());
        let device_id = Self::resolve_active_device(state, device_param)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }.ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(12));

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

        match timeout(Duration::from_secs(12), oneshot_rx).await {
            Ok(Ok(Ok(result))) => {
                let base64_jpeg = result.get("base64_data").and_then(|b| b.as_str()).unwrap_or("");
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
            Err(_) => Err("Camera capture timed out. The phone did not return the image in 12 seconds.".to_string())
        }
    }

    async fn tool_capture_screenshot(state: &AppState, args: &Value) -> Result<Value, String> {
        let device_param = args.get("device").and_then(|v| v.as_str());
        let device_id = Self::resolve_active_device(state, device_param)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }.ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(12));

        let req_env = Envelope::new_request(
            request_id,
            "capture_screenshot".to_string(),
            json!({ "timeout_ms": 12000 }),
            None
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx.send(axum::extract::ws::Message::Text(payload_str)).await.is_err() {
            return Err("Failed to push capture command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(12), oneshot_rx).await {
            Ok(Ok(Ok(result))) => {
                let base64_jpeg = result.get("base64_data").and_then(|b| b.as_str()).unwrap_or("");
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
            Err(_) => Err("Camera capture timed out. The phone did not return the image in 12 seconds.".to_string())
        }
    }

    async fn tool_record_video(state: &AppState, args: &Value) -> Result<Value, String> {
        let device_param = args.get("device").and_then(|v| v.as_str());
        let device_id = Self::resolve_active_device(state, device_param)?;
        let duration_seconds = args.get("duration_seconds").and_then(|v| v.as_i64()).unwrap_or(10);

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }.ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs((duration_seconds + 5) as u64));

        let req_env = Envelope::new_request(
            request_id,
            "record_video".to_string(),
            json!({ "duration_seconds": duration_seconds }),
            None
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx.send(axum::extract::ws::Message::Text(payload_str)).await.is_err() {
            return Err("Failed to push record command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs((duration_seconds + 8) as u64), oneshot_rx).await {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Video recording timed out.".to_string())
        }
    }

    async fn tool_get_device_clipboard(state: &AppState, args: &Value) -> Result<Value, String> {
        let device_param = args.get("device").and_then(|v| v.as_str());
        let device_id = Self::resolve_active_device(state, device_param)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }.ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(5));

        let req_env = Envelope::new_request(
            request_id,
            "get_device_clipboard".to_string(),
            Value::Null,
            None
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx.send(axum::extract::ws::Message::Text(payload_str)).await.is_err() {
            return Err("Failed to push clipboard command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(5), oneshot_rx).await {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Clipboard retrieve timed out.".to_string())
        }
    }

    async fn tool_set_device_clipboard(state: &AppState, args: &Value) -> Result<Value, String> {
        let device_param = args.get("device").and_then(|v| v.as_str());
        let device_id = Self::resolve_active_device(state, device_param)?;
        let text = args.get("text").and_then(|v| v.as_str()).ok_or_else(|| "Missing required 'text' argument".to_string())?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }.ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(5));

        let req_env = Envelope::new_request(
            request_id,
            "set_device_clipboard".to_string(),
            json!({ "text": text }),
            None
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx.send(axum::extract::ws::Message::Text(payload_str)).await.is_err() {
            return Err("Failed to push clipboard command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(5), oneshot_rx).await {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Clipboard set timed out.".to_string())
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
