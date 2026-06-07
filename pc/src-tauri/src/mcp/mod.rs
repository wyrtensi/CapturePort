use crate::state::{AppState, MediaItem};
use crate::ws::envelope::Envelope;
use serde_json::{json, Value};
use std::time::Duration;
use tokio::time::timeout;

pub mod http_server;
pub mod tools;

const MEDIA_INDEX_URI: &str = "captureport://media-index";
const LATEST_CAMERA_URI: &str = "camera://latest";

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
                let settings = crate::AppSettings::load();
                let mut capabilities = json!({
                    "tools": {}
                });
                if settings.mcp_media_index_enabled() && settings.mcp_resource_reads_enabled() {
                    capabilities["resources"] = json!({});
                }
                json!({
                    "jsonrpc": "2.0",
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": capabilities,
                        "serverInfo": {
                            "name": "CapturePort MCP Server",
                            "version": env!("CARGO_PKG_VERSION")
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
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
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
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
                                        },
                                        "use_flash": {
                                            "type": "boolean",
                                            "description": "Temporarily enable the phone torch before taking the photo, then restore the previous torch state."
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
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
                                        },
                                        "duration_seconds": {
                                            "type": "integer",
                                            "description": "Optional recording duration in seconds (default 10)."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "set_flashlight",
                                "description": "Turn the selected phone torch on or off for continuous lighting.",
                                "inputSchema": {
                                    "type": "object",
                                    "required": ["enabled"],
                                    "properties": {
                                        "device": {
                                            "type": "string",
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
                                        },
                                        "enabled": {
                                            "type": "boolean",
                                            "description": "true turns the torch on, false turns it off."
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
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
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
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
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
                            },
                            {
                                "name": "look_camera",
                                "description": "Capture a fresh camera image from the selected phone and return image content plus agent-readable metadata.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "device": {
                                            "type": "string",
                                            "description": "Deprecated alias for target_device_id or legacy device name/substring."
                                        },
                                        "target_device_id": {
                                            "type": "string",
                                            "description": "Exact device id from list_devices. Prefer this when more than one phone is online."
                                        },
                                        "use_flash": {
                                            "type": "boolean",
                                            "description": "Temporarily enable the phone torch before taking the photo, then restore the previous torch state."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "list_media",
                                "description": "List recent CapturePort photos and videos with labels, timestamps, URIs, paths, and device metadata.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "limit": {
                                            "type": "integer",
                                            "description": "Maximum number of media items to return, 1-20. Defaults to 10."
                                        },
                                        "offset": {
                                            "type": "integer",
                                            "description": "Number of matching media items to skip."
                                        },
                                        "kind": {
                                            "type": "string",
                                            "description": "Optional filter: photo or video."
                                        },
                                        "device": {
                                            "type": "string",
                                            "description": "Optional case-insensitive device id or device name filter."
                                        },
                                        "query": {
                                            "type": "string",
                                            "description": "Optional case-insensitive search across label, path, URI, device, and request id."
                                        },
                                        "since_unix_ms": {
                                            "type": "integer",
                                            "description": "Optional inclusive lower timestamp bound."
                                        },
                                        "until_unix_ms": {
                                            "type": "integer",
                                            "description": "Optional inclusive upper timestamp bound."
                                        }
                                    }
                                }
                            },
                            {
                                "name": "get_media",
                                "description": "Return one media item by id, URI, or latest=true. Photos include MCP image content when available.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "id": { "type": "string" },
                                        "uri": { "type": "string" },
                                        "latest": { "type": "boolean" }
                                    }
                                }
                            },
                            {
                                "name": "search_media",
                                "description": "Search indexed CapturePort photos and videos by kind, device, query, and timestamp bounds.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "limit": { "type": "integer" },
                                        "offset": { "type": "integer" },
                                        "kind": { "type": "string" },
                                        "device": { "type": "string" },
                                        "target_device_id": { "type": "string" },
                                        "query": { "type": "string" },
                                        "since_unix_ms": { "type": "integer" },
                                        "until_unix_ms": { "type": "integer" }
                                    }
                                }
                            },
                            {
                                "name": "get_mcp_settings",
                                "description": "Read CapturePort MCP transport and agent-mode settings.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                }
                            },
                            {
                                "name": "camera_status",
                                "description": "Return online/exposed device status plus latest capture metadata for agents.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                }
                            },
                            {
                                "name": "watch_camera",
                                "description": "Capture a short sequence of fresh camera frames from the selected phone.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "device": { "type": "string" },
                                        "max_frames": { "type": "integer" },
                                        "interval_ms": { "type": "integer" }
                                    }
                                }
                            },
                            {
                                "name": "compare_media",
                                "description": "Compare two indexed CapturePort media items by id or URI using metadata and byte equality.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "left": { "type": "string" },
                                        "right": { "type": "string" }
                                    }
                                }
                            },
                            {
                                "name": "list_agent_presets",
                                "description": "List ready-made MCP access presets for privacy, local agent, LAN agent, and vision-heavy workflows.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                }
                            },
                            {
                                "name": "apply_agent_preset",
                                "description": "Apply one ready-made MCP access preset. Transport changes may require restart.",
                                "inputSchema": {
                                    "type": "object",
                                    "required": ["preset"],
                                    "properties": {
                                        "preset": { "type": "string" }
                                    }
                                }
                            },
                            {
                                "name": "set_mcp_settings",
                                "description": "Update CapturePort MCP settings. Transport changes may require restarting the desktop app.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "mcp_enabled": { "type": "boolean" },
                                        "mcp_http_enabled": { "type": "boolean" },
                                        "mcp_http_discovery_enabled": { "type": "boolean" },
                                        "mcp_http_port": { "type": "integer" },
                                        "mcp_http_bind_mode": { "type": "string" },
                                        "mcp_agent_mode": { "type": "string" },
                                        "mcp_agent_preset": { "type": "string" },
                                        "mcp_stream_enabled": { "type": "boolean" },
                                        "mcp_media_index_enabled": { "type": "boolean" },
                                        "mcp_resource_reads_enabled": { "type": "boolean" },
                                        "mcp_inline_images_enabled": { "type": "boolean" },
                                        "mcp_allowed_hosts": {
                                            "type": "array",
                                            "items": { "type": "string" }
                                        },
                                        "mcp_allowed_origins": {
                                            "type": "array",
                                            "items": { "type": "string" }
                                        },
                                        "mcp_http_auth_token": { "type": "string" }
                                    }
                                }
                            }
                        ]
                    },
                    "id": id
                })
            }
            "resources/list" => {
                json!({
                    "jsonrpc": "2.0",
                    "result": {
                        "resources": Self::resource_list(state)
                    },
                    "id": id
                })
            }
            "resources/templates/list" => {
                let settings = crate::AppSettings::load();
                let templates = if settings.mcp_media_index_enabled()
                    && settings.mcp_resource_reads_enabled()
                {
                    Self::resource_templates()
                } else {
                    Vec::new()
                };
                json!({
                    "jsonrpc": "2.0",
                    "result": {
                        "resourceTemplates": templates
                    },
                    "id": id
                })
            }
            "resources/read" => {
                let uri = req
                    .get("params")
                    .and_then(|params| params.get("uri"))
                    .and_then(|uri| uri.as_str());
                match uri {
                    Some(uri) => match Self::read_resource_contents(state, uri) {
                        Ok(contents) => json!({
                            "jsonrpc": "2.0",
                            "result": {
                                "contents": contents
                            },
                            "id": id
                        }),
                        Err(message) => json!({
                            "jsonrpc": "2.0",
                            "error": {
                                "code": -32002,
                                "message": message
                            },
                            "id": id
                        }),
                    },
                    None => json!({
                        "jsonrpc": "2.0",
                        "error": {
                            "code": -32602,
                            "message": "resources/read requires params.uri"
                        },
                        "id": id
                    }),
                }
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
                    "set_flashlight" => Self::tool_set_flashlight(state, args).await,
                    "get_device_clipboard" => Self::tool_get_device_clipboard(state, args).await,
                    "set_device_clipboard" => Self::tool_set_device_clipboard(state, args).await,
                    "snap_frame" => Self::tool_snap_frame(state).await,
                    "look_camera" => Self::tool_capture_photo(state, args).await,
                    "list_media" => Self::tool_list_media(state, args).await,
                    "get_media" => Self::tool_get_media(state, args).await,
                    "search_media" => Self::tool_list_media(state, args).await,
                    "get_mcp_settings" => Self::tool_get_mcp_settings().await,
                    "camera_status" => Self::tool_camera_status(state).await,
                    "watch_camera" => Self::tool_watch_camera(state, args).await,
                    "compare_media" => Self::tool_compare_media(state, args).await,
                    "list_agent_presets" => Self::tool_list_agent_presets().await,
                    "apply_agent_preset" => Self::tool_apply_agent_preset(args).await,
                    "set_mcp_settings" => Self::tool_set_mcp_settings(args).await,
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
                            "result": {
                                "content": [
                                    {
                                        "type": "text",
                                        "text": err_msg
                                    }
                                ],
                                "isError": true
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

    pub(crate) fn resource_list(state: &AppState) -> Vec<Value> {
        let settings = crate::AppSettings::load();
        if !settings.mcp_media_index_enabled() || !settings.mcp_resource_reads_enabled() {
            return Vec::new();
        }

        let search = crate::media::MediaSearch {
            limit: 50,
            offset: 0,
            kind: None,
            device: None,
            query: None,
            since_unix_ms: None,
            until_unix_ms: None,
            include_missing: false,
            include_thumbnails: false,
        };
        let mut resources = vec![json!({
            "uri": MEDIA_INDEX_URI,
            "name": "CapturePort media index",
            "title": "CapturePort media index",
            "description": "JSON index of recently captured and previously saved CapturePort media.",
            "mimeType": "application/json"
        })];

        if crate::media::latest_media(state, Some("photo")).is_some() {
            resources.push(json!({
                "uri": LATEST_CAMERA_URI,
                "name": "Latest camera photo",
                "title": "Latest camera photo",
                "description": "Most recent readable CapturePort photo.",
                "mimeType": "image/jpeg"
            }));
        }

        for item in crate::media::search_media(state, &search) {
            resources.push(Self::media_resource_descriptor(&item));
            if !item.thumbnail_uri.is_empty() {
                resources.push(json!({
                    "uri": item.thumbnail_uri,
                    "name": format!("{} thumbnail", item.label),
                    "title": format!("{} thumbnail", item.label),
                    "description": format!("JPEG thumbnail preview for {}.", item.label),
                    "mimeType": "image/jpeg",
                    "size": 0
                }));
            }
        }
        resources
    }

    pub(crate) fn resource_templates() -> Vec<Value> {
        vec![
            json!({
                "uriTemplate": "captureport://media/{id}",
                "name": "CapturePort media item",
                "title": "CapturePort media item",
                "description": "Read one indexed CapturePort photo or video by id.",
                "mimeType": "application/json"
            }),
            json!({
                "uriTemplate": "captureport://media/{id}/thumbnail",
                "name": "CapturePort media thumbnail",
                "title": "CapturePort media thumbnail",
                "description": "Read a lightweight JPEG thumbnail preview for one indexed photo.",
                "mimeType": "image/jpeg"
            }),
            json!({
                "uriTemplate": LATEST_CAMERA_URI,
                "name": "Latest camera photo",
                "title": "Latest camera photo",
                "description": "Read the newest CapturePort photo without taking a new one.",
                "mimeType": "image/jpeg"
            }),
        ]
    }

    pub(crate) fn read_resource_contents(
        state: &AppState,
        uri: &str,
    ) -> Result<Vec<Value>, String> {
        let settings = crate::AppSettings::load();
        if !settings.mcp_resource_reads_enabled() {
            return Err("MCP resource reads are disabled in CapturePort settings.".to_string());
        }
        if !settings.mcp_media_index_enabled() {
            return Err("MCP media index is disabled in CapturePort settings.".to_string());
        }

        if uri == MEDIA_INDEX_URI {
            let search = crate::media::MediaSearch {
                limit: 100,
                offset: 0,
                kind: None,
                device: None,
                query: None,
                since_unix_ms: None,
                until_unix_ms: None,
                include_missing: false,
                include_thumbnails: false,
            };
            let items: Vec<Value> = crate::media::search_media(state, &search)
                .into_iter()
                .map(|mut item| {
                    crate::media::normalize_media_item(&mut item);
                    crate::media::media_summary(&item)
                })
                .collect();
            return Ok(vec![json!({
                "uri": MEDIA_INDEX_URI,
                "mimeType": "application/json",
                "text": serde_json::to_string_pretty(&json!({ "items": items })).unwrap_or_default()
            })]);
        }

        if let Some(id) = uri
            .strip_prefix("captureport://media/")
            .and_then(|suffix| suffix.strip_suffix("/thumbnail"))
        {
            let mut item = crate::media::find_media(state, Some(id), None)
                .ok_or_else(|| format!("No CapturePort thumbnail found for URI '{}'.", uri))?;
            crate::media::normalize_media_item(&mut item);
            let data = crate::media::read_thumbnail_base64(&item)
                .ok_or_else(|| format!("Thumbnail bytes are not readable for URI '{}'.", uri))?;
            return Ok(vec![json!({
                "uri": uri,
                "mimeType": "image/jpeg",
                "blob": data
            })]);
        }

        let mut item = if uri == LATEST_CAMERA_URI {
            crate::media::latest_media(state, Some("photo"))
        } else if let Some(id) = uri.strip_prefix("captureport://media/") {
            crate::media::find_media(state, Some(id), Some(uri))
        } else {
            crate::media::find_media(state, None, Some(uri))
        }
        .ok_or_else(|| format!("No CapturePort resource found for URI '{}'.", uri))?;

        crate::media::normalize_media_item(&mut item);
        Ok(Self::resource_contents_for_item(
            &item,
            settings.mcp_inline_images_enabled(),
            uri,
        ))
    }

    fn media_resource_descriptor(item: &MediaItem) -> Value {
        let mut item = item.clone();
        crate::media::normalize_media_item(&mut item);
        let name = if item.label.is_empty() {
            item.id.clone()
        } else {
            item.label.clone()
        };
        json!({
            "uri": item.uri,
            "name": name,
            "title": name,
            "description": item.notes,
            "mimeType": item.mime_type,
            "size": item.size_bytes.min(u32::MAX as u64) as u32
        })
    }

    fn resource_contents_for_item(
        item: &MediaItem,
        include_inline_image: bool,
        resource_uri: &str,
    ) -> Vec<Value> {
        let summary = crate::media::media_summary(item);
        let mut contents = vec![json!({
            "uri": resource_uri,
            "mimeType": "application/json",
            "text": serde_json::to_string_pretty(&summary).unwrap_or_default()
        })];

        if include_inline_image && item.kind == "photo" {
            if let Some(data) = crate::media::read_photo_base64(item) {
                contents.push(json!({
                    "uri": resource_uri,
                    "mimeType": item.mime_type,
                    "blob": data
                }));
            }
        }

        contents
    }

    fn resolve_active_device(
        state: &AppState,
        device_param: Option<&str>,
    ) -> Result<String, String> {
        let inner = state.inner.lock().unwrap();
        if inner.active_sessions.is_empty() {
            return Err("No active mobile devices are online. Please open the CapturePort app on your phone.".to_string());
        }

        let mut active_mcp_sessions: Vec<(&String, &crate::state::WsSession)> = inner
            .active_sessions
            .iter()
            .filter(|(id, _)| {
                inner
                    .paired_devices
                    .get(*id)
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
                (Some(a), Some(b)) => b
                    .pinned
                    .cmp(&a.pinned)
                    .then_with(|| b.last_seen_ms.cmp(&a.last_seen_ms)),
                _ => std::cmp::Ordering::Equal,
            }
        });

        let param = match device_param {
            Some(p) if !p.trim().is_empty() => p.trim(),
            _ => return Ok(active_mcp_sessions[0].0.to_string()),
        };

        // Match exact UUID
        if let Some((id, _)) = active_mcp_sessions
            .iter()
            .find(|(id, _)| id.as_str() == param)
        {
            return Ok(id.to_string());
        }

        // Match exact name
        if let Some((id, _)) = active_mcp_sessions.iter().find(|(_, s)| s.name == param) {
            return Ok(id.to_string());
        }

        // Case-insensitive substring match (name or UUID)
        let query_lower = param.to_lowercase();
        let matches: Vec<String> = active_mcp_sessions
            .iter()
            .filter(|(id, s)| {
                s.name.to_lowercase().contains(&query_lower)
                    || id.to_lowercase().contains(&query_lower)
            })
            .map(|(id, _)| id.to_string())
            .collect();

        if matches.len() == 1 {
            Ok(matches[0].clone())
        } else if matches.len() > 1 {
            Err(format!("Ambiguous device query '{}' matched multiple devices. Please specify a more precise name or UUID.", param))
        } else {
            Err(format!(
                "No online, MCP-exposed device matches query '{}'.",
                param
            ))
        }
    }

    fn resolve_exact_target_device(
        state: &AppState,
        target_device_id: &str,
    ) -> Result<String, String> {
        let target_device_id = target_device_id.trim();
        if target_device_id.is_empty() {
            return Err("target_device_id must not be empty.".to_string());
        }

        let inner = state.inner.lock().unwrap();
        let device = inner.paired_devices.get(target_device_id).ok_or_else(|| {
            format!(
                "No paired device has target_device_id '{}'.",
                target_device_id
            )
        })?;
        if !device.exposed_to_mcp {
            return Err(format!(
                "Device '{}' is paired but hidden from MCP.",
                target_device_id
            ));
        }
        if !inner.active_sessions.contains_key(target_device_id) {
            return Err(format!(
                "Device '{}' is paired and exposed to MCP, but it is not currently online.",
                target_device_id
            ));
        }
        Ok(target_device_id.to_string())
    }

    fn resolve_tool_device(state: &AppState, args: &Value) -> Result<String, String> {
        if let Some(target_device_id) = args.get("target_device_id").and_then(|v| v.as_str()) {
            return Self::resolve_exact_target_device(state, target_device_id);
        }
        let device_param = args.get("device").and_then(|v| v.as_str());
        Self::resolve_active_device(state, device_param)
    }

    pub(crate) async fn tool_list_devices(state: &AppState) -> Result<Value, String> {
        let default_device_id = Self::resolve_active_device(state, None).ok();
        let inner = state.inner.lock().unwrap();
        let mut devices_list = Vec::new();

        for (id, dev) in &inner.paired_devices {
            if dev.exposed_to_mcp {
                let online = inner.active_sessions.contains_key(id);
                devices_list.push(json!({
                    "id": id,
                    "target_device_id": id,
                    "name": dev.name,
                    "alias": dev.alias,
                    "os": dev.os,
                    "online": online,
                    "exposed_to_mcp": dev.exposed_to_mcp,
                    "pinned": dev.pinned,
                    "is_default": default_device_id.as_deref() == Some(id.as_str()),
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
            ],
            "devices": devices_list
        }))
    }

    pub(crate) async fn tool_capture_photo(
        state: &AppState,
        args: &Value,
    ) -> Result<Value, String> {
        let device_id = Self::resolve_tool_device(state, args)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }
        .ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(12));
        let use_flash = Self::photo_use_flash(args);

        let req_env = Envelope::new_request(
            request_id,
            "capture_photo".to_string(),
            json!({ "timeout_ms": 12000, "use_flash": use_flash }),
            None,
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx
            .send(axum::extract::ws::Message::Text(payload_str))
            .await
            .is_err()
        {
            return Err("Failed to push capture command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(12), oneshot_rx).await {
            Ok(Ok(Ok(result))) => {
                let base64_jpeg = result
                    .get("base64_data")
                    .and_then(|b| b.as_str())
                    .unwrap_or("");
                let media = result.get("media").cloned().unwrap_or(Value::Null);
                let mut content = vec![json!({
                    "type": "text",
                    "text": serde_json::to_string_pretty(&media).unwrap_or_default()
                })];
                if crate::AppSettings::load().mcp_inline_images_enabled() {
                    content.insert(
                        0,
                        json!({
                            "type": "image",
                            "data": base64_jpeg,
                            "mimeType": "image/jpeg"
                        }),
                    );
                }
                Ok(json!({
                    "content": content,
                    "media": media
                }))
            }
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err(
                "Camera capture timed out. The phone did not return the image in 12 seconds."
                    .to_string(),
            ),
        }
    }

    pub(crate) async fn tool_capture_screenshot(
        state: &AppState,
        args: &Value,
    ) -> Result<Value, String> {
        let device_id = Self::resolve_tool_device(state, args)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }
        .ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(12));
        let use_flash = Self::photo_use_flash(args);

        let req_env = Envelope::new_request(
            request_id,
            "capture_screenshot".to_string(),
            json!({ "timeout_ms": 12000, "use_flash": use_flash }),
            None,
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx
            .send(axum::extract::ws::Message::Text(payload_str))
            .await
            .is_err()
        {
            return Err("Failed to push capture command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(12), oneshot_rx).await {
            Ok(Ok(Ok(result))) => {
                let base64_jpeg = result
                    .get("base64_data")
                    .and_then(|b| b.as_str())
                    .unwrap_or("");
                let media = result.get("media").cloned().unwrap_or(Value::Null);
                let mut content = vec![json!({
                    "type": "text",
                    "text": serde_json::to_string_pretty(&media).unwrap_or_default()
                })];
                if crate::AppSettings::load().mcp_inline_images_enabled() {
                    content.insert(
                        0,
                        json!({
                            "type": "image",
                            "data": base64_jpeg,
                            "mimeType": "image/jpeg"
                        }),
                    );
                }
                Ok(json!({
                    "content": content,
                    "media": media
                }))
            }
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err(
                "Camera capture timed out. The phone did not return the image in 12 seconds."
                    .to_string(),
            ),
        }
    }

    pub(crate) async fn tool_record_video(state: &AppState, args: &Value) -> Result<Value, String> {
        let device_id = Self::resolve_tool_device(state, args)?;
        let duration_seconds = Self::record_duration_seconds(args)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }
        .ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(
            request_id.clone(),
            oneshot_tx,
            Duration::from_secs((duration_seconds + 5) as u64),
        );

        let req_env = Envelope::new_request(
            request_id,
            "record_video".to_string(),
            json!({ "duration_seconds": duration_seconds }),
            None,
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx
            .send(axum::extract::ws::Message::Text(payload_str))
            .await
            .is_err()
        {
            return Err("Failed to push record command onto WebSocket channel".to_string());
        }

        match timeout(
            Duration::from_secs((duration_seconds + 8) as u64),
            oneshot_rx,
        )
        .await
        {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Video recording timed out.".to_string()),
        }
    }

    pub(crate) async fn tool_set_flashlight(
        state: &AppState,
        args: &Value,
    ) -> Result<Value, String> {
        let device_id = Self::resolve_tool_device(state, args)?;
        let enabled = args
            .get("enabled")
            .and_then(|value| value.as_bool())
            .ok_or_else(|| "Missing required boolean 'enabled' argument".to_string())?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }
        .ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(5));

        let req_env = Envelope::new_request(
            request_id,
            "set_flashlight".to_string(),
            json!({ "enabled": enabled }),
            None,
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx
            .send(axum::extract::ws::Message::Text(payload_str))
            .await
            .is_err()
        {
            return Err("Failed to push flashlight command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(5), oneshot_rx).await {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Flashlight command timed out.".to_string()),
        }
    }

    pub(crate) async fn tool_get_device_clipboard(
        state: &AppState,
        args: &Value,
    ) -> Result<Value, String> {
        let device_id = Self::resolve_tool_device(state, args)?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }
        .ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(5));

        let req_env = Envelope::new_request(
            request_id,
            "get_device_clipboard".to_string(),
            Value::Null,
            None,
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx
            .send(axum::extract::ws::Message::Text(payload_str))
            .await
            .is_err()
        {
            return Err("Failed to push clipboard command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(5), oneshot_rx).await {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Clipboard retrieve timed out.".to_string()),
        }
    }

    pub(crate) async fn tool_set_device_clipboard(
        state: &AppState,
        args: &Value,
    ) -> Result<Value, String> {
        let device_id = Self::resolve_tool_device(state, args)?;
        let text = args
            .get("text")
            .and_then(|v| v.as_str())
            .ok_or_else(|| "Missing required 'text' argument".to_string())?;

        let tx = {
            let inner = state.inner.lock().unwrap();
            inner.active_sessions.get(&device_id).map(|s| s.tx.clone())
        }
        .ok_or_else(|| "Failed to retrieve active socket channel".to_string())?;

        let (oneshot_tx, oneshot_rx) = tokio::sync::oneshot::channel::<Result<Value, String>>();
        let request_id = ulid::Ulid::new().to_string();

        state.register_request(request_id.clone(), oneshot_tx, Duration::from_secs(5));

        let req_env = Envelope::new_request(
            request_id,
            "set_device_clipboard".to_string(),
            json!({ "text": text }),
            None,
        );

        let payload_str = serde_json::to_string(&req_env).unwrap();
        if tx
            .send(axum::extract::ws::Message::Text(payload_str))
            .await
            .is_err()
        {
            return Err("Failed to push clipboard command onto WebSocket channel".to_string());
        }

        match timeout(Duration::from_secs(5), oneshot_rx).await {
            Ok(Ok(Ok(result))) => Ok(result),
            Ok(Ok(Err(err))) => Err(err),
            Ok(Err(_)) => Err("Correlation channel dropped unexpectedly".to_string()),
            Err(_) => Err("Clipboard set timed out.".to_string()),
        }
    }

    pub(crate) async fn tool_snap_frame(state: &AppState) -> Result<Value, String> {
        let settings = crate::AppSettings::load();
        let item = crate::media::latest_media(state, Some("photo"));

        match item {
            Some(mut media) => {
                crate::media::normalize_media_item(&mut media);
                let summary = crate::media::media_summary(&media);
                let mut content = vec![json!({
                    "type": "text",
                    "text": serde_json::to_string_pretty(&summary).unwrap_or_default()
                })];
                if settings.mcp_inline_images_enabled() {
                    let image_data = crate::media::read_photo_base64(&media).ok_or_else(|| {
                        "Latest photo exists but its image bytes are no longer readable.".to_string()
                    })?;
                    content.insert(
                        0,
                        json!({
                            "type": "image",
                            "data": image_data,
                            "mimeType": "image/jpeg"
                        }),
                    );
                }
                Ok(json!({
                    "content": content,
                    "media": summary
                }))
            }
            None => Err("No photos are currently cached in memory history logs. Take at least one photo first.".to_string()),
        }
    }

    pub(crate) fn record_duration_seconds(args: &Value) -> Result<i64, String> {
        let duration = args
            .get("duration_seconds")
            .and_then(|v| v.as_i64())
            .unwrap_or(10);
        if !(1..=120).contains(&duration) {
            return Err("duration_seconds must be between 1 and 120.".to_string());
        }
        Ok(duration)
    }

    pub(crate) fn photo_use_flash(args: &Value) -> bool {
        args.get("use_flash")
            .or_else(|| args.get("flash"))
            .and_then(|value| value.as_bool())
            .unwrap_or(false)
    }

    pub(crate) fn watch_camera_plan(args: &Value) -> Result<(usize, u64), String> {
        let max_frames = args.get("max_frames").and_then(|v| v.as_u64()).unwrap_or(3);
        if !(1..=10).contains(&max_frames) {
            return Err("max_frames must be between 1 and 10.".to_string());
        }

        let interval_ms = args
            .get("interval_ms")
            .and_then(|v| v.as_u64())
            .unwrap_or(1000);
        if !(500..=30000).contains(&interval_ms) {
            return Err("interval_ms must be between 500 and 30000.".to_string());
        }

        Ok((max_frames as usize, interval_ms))
    }

    pub(crate) async fn tool_list_media(state: &AppState, args: &Value) -> Result<Value, String> {
        let settings = crate::AppSettings::load();
        if !settings.mcp_media_index_enabled() {
            return Err("MCP media index is disabled in CapturePort settings.".to_string());
        }
        let limit = args
            .get("limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(10)
            .clamp(1, 20) as usize;
        let search = crate::media::MediaSearch {
            limit,
            offset: args.get("offset").and_then(|v| v.as_u64()).unwrap_or(0) as usize,
            kind: args
                .get("kind")
                .and_then(|v| v.as_str())
                .map(ToString::to_string),
            device: args
                .get("device")
                .and_then(|v| v.as_str())
                .map(ToString::to_string),
            query: args
                .get("query")
                .or_else(|| args.get("label_contains"))
                .and_then(|v| v.as_str())
                .map(ToString::to_string),
            since_unix_ms: args
                .get("since_unix_ms")
                .or_else(|| args.get("created_after"))
                .and_then(|v| v.as_u64()),
            until_unix_ms: args
                .get("until_unix_ms")
                .or_else(|| args.get("created_before"))
                .and_then(|v| v.as_u64()),
            include_missing: args
                .get("include_missing")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            include_thumbnails: args
                .get("include_thumbnails")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
        };
        let mut items = crate::media::search_media(state, &search);
        for item in &mut items {
            crate::media::normalize_media_item(item);
        }
        let summaries: Vec<Value> = items
            .into_iter()
            .map(|item| crate::media::media_summary(&item))
            .collect();

        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&summaries).unwrap_or_default()
                }
            ],
            "items": summaries
        }))
    }

    pub(crate) async fn tool_get_media(state: &AppState, args: &Value) -> Result<Value, String> {
        let selector_id = args.get("id").and_then(|v| v.as_str());
        let selector_uri = args.get("uri").and_then(|v| v.as_str());
        let latest = args
            .get("latest")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let mut item = if latest {
            crate::media::latest_media(state, None)
        } else {
            crate::media::find_media(state, selector_id, selector_uri)
        }
        .ok_or_else(|| {
            "No matching media item found. Provide id, uri, or latest=true.".to_string()
        })?;

        crate::media::normalize_media_item(&mut item);
        let summary = crate::media::media_summary(&item);
        let settings = crate::AppSettings::load();
        let mut content = vec![json!({
            "type": "text",
            "text": serde_json::to_string_pretty(&summary).unwrap_or_default()
        })];
        if settings.mcp_inline_images_enabled() && item.kind == "photo" {
            let image_data = crate::media::read_photo_base64(&item);
            if let Some(data) = image_data {
                content.insert(
                    0,
                    json!({
                        "type": "image",
                        "data": data,
                        "mimeType": item.mime_type
                    }),
                );
            }
        }

        Ok(json!({
            "content": content,
            "media": summary
        }))
    }

    pub(crate) async fn tool_get_mcp_settings() -> Result<Value, String> {
        let settings = crate::AppSettings::load();
        let summary = settings.mcp_settings_summary();
        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&summary).unwrap_or_default()
                }
            ],
            "settings": summary
        }))
    }

    pub(crate) async fn tool_camera_status(state: &AppState) -> Result<Value, String> {
        let latest_photo = crate::media::latest_media(state, Some("photo"))
            .map(|mut item| {
                crate::media::normalize_media_item(&mut item);
                crate::media::media_summary(&item)
            })
            .unwrap_or(Value::Null);
        let latest_media = crate::media::latest_media(state, None)
            .map(|mut item| {
                crate::media::normalize_media_item(&mut item);
                crate::media::media_summary(&item)
            })
            .unwrap_or(Value::Null);
        let status = {
            let inner = state.inner.lock().unwrap();
            let devices: Vec<Value> = inner
                .paired_devices
                .iter()
                .map(|(id, device)| {
                    let session = inner.active_sessions.get(id);
                    json!({
                        "id": id,
                        "name": device.name,
                        "alias": device.alias,
                        "os": device.os,
                        "online": session.is_some(),
                        "exposed_to_mcp": device.exposed_to_mcp,
                        "last_seen_ms": device.last_seen_ms,
                        "ip": session.map(|s| s.ip.clone()),
                        "channel": session.map(|s| s.channel.clone())
                    })
                })
                .collect();
            json!({
                "devices": devices,
                "online_count": inner.active_sessions.len(),
                "pending_request_count": inner.pending_requests.len(),
                "media_count": inner.media_history.len(),
                "latest_photo": latest_photo,
                "latest_media": latest_media
            })
        };
        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&status).unwrap_or_default()
                }
            ],
            "status": status
        }))
    }

    pub(crate) async fn tool_watch_camera(state: &AppState, args: &Value) -> Result<Value, String> {
        let (max_frames, interval_ms) = Self::watch_camera_plan(args)?;
        let mut frames = Vec::new();
        let mut content = Vec::new();

        for frame_index in 0..max_frames {
            if frame_index > 0 {
                tokio::time::sleep(Duration::from_millis(interval_ms)).await;
            }
            let frame = Self::tool_capture_photo(state, args).await?;
            if let Some(media) = frame.get("media").cloned() {
                frames.push(media.clone());
                content.push(json!({
                    "type": "text",
                    "text": serde_json::to_string_pretty(&json!({
                        "frame": frame_index + 1,
                        "media": media
                    })).unwrap_or_default()
                }));
            }
            if crate::AppSettings::load().mcp_inline_images_enabled() {
                if let Some(items) = frame.get("content").and_then(|value| value.as_array()) {
                    content.extend(
                        items
                            .iter()
                            .filter(|item| {
                                item.get("type").and_then(|value| value.as_str()) == Some("image")
                            })
                            .cloned(),
                    );
                }
            }
        }

        let summary = json!({
            "frame_count": frames.len(),
            "interval_ms": interval_ms,
            "frames": frames
        });
        content.insert(
            0,
            json!({
                "type": "text",
                "text": serde_json::to_string_pretty(&summary).unwrap_or_default()
            }),
        );

        Ok(json!({
            "content": content,
            "watch": summary
        }))
    }

    pub(crate) async fn tool_compare_media(
        state: &AppState,
        args: &Value,
    ) -> Result<Value, String> {
        let left_selector = args.get("left").and_then(|v| v.as_str());
        let right_selector = args.get("right").and_then(|v| v.as_str());
        let (mut left, mut right) = if left_selector.is_none() && right_selector.is_none() {
            let search = crate::media::MediaSearch {
                limit: 2,
                offset: 0,
                kind: None,
                device: None,
                query: None,
                since_unix_ms: None,
                until_unix_ms: None,
                include_missing: false,
                include_thumbnails: false,
            };
            let items = crate::media::search_media(state, &search);
            if items.len() < 2 {
                return Err("compare_media needs two media items, or at least two indexed items when selectors are omitted.".to_string());
            }
            (items[0].clone(), items[1].clone())
        } else {
            let left = left_selector
                .and_then(|selector| {
                    crate::media::find_media(state, Some(selector), Some(selector))
                })
                .ok_or_else(|| "compare_media could not find the left media item.".to_string())?;
            let right = right_selector
                .and_then(|selector| {
                    crate::media::find_media(state, Some(selector), Some(selector))
                })
                .ok_or_else(|| "compare_media could not find the right media item.".to_string())?;
            (left, right)
        };

        crate::media::normalize_media_item(&mut left);
        crate::media::normalize_media_item(&mut right);
        let comparison = crate::media::compare_media_items(&left, &right);
        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&comparison).unwrap_or_default()
                }
            ],
            "comparison": comparison
        }))
    }

    pub(crate) async fn tool_list_agent_presets() -> Result<Value, String> {
        let presets = json!({
            "privacy_first": {
                "description": "Loopback-only transport, no mDNS advertisement, no inline images.",
                "settings": {
                    "mcp_agent_preset": "privacy_first",
                    "mcp_http_bind_mode": "loopback",
                    "mcp_http_discovery_enabled": false,
                    "mcp_inline_images_enabled": false,
                    "mcp_resource_reads_enabled": true,
                    "mcp_media_index_enabled": true,
                    "mcp_stream_enabled": false
                }
            },
            "local_agent": {
                "description": "Loopback HTTP for local IDE agents with inline images enabled.",
                "settings": {
                    "mcp_agent_preset": "local_agent",
                    "mcp_http_bind_mode": "loopback",
                    "mcp_http_discovery_enabled": false,
                    "mcp_inline_images_enabled": true,
                    "mcp_resource_reads_enabled": true,
                    "mcp_media_index_enabled": true,
                    "mcp_stream_enabled": false
                }
            },
            "lan_agent": {
                "description": "Default discoverable LAN mode for nearby IDE agents and inspectors.",
                "settings": {
                    "mcp_agent_preset": "lan_agent",
                    "mcp_http_bind_mode": "lan",
                    "mcp_http_discovery_enabled": true,
                    "mcp_inline_images_enabled": true,
                    "mcp_resource_reads_enabled": true,
                    "mcp_media_index_enabled": true,
                    "mcp_stream_enabled": false
                }
            },
            "vision_heavy": {
                "description": "LAN discovery with inline images and stream flag enabled for vision-heavy clients.",
                "settings": {
                    "mcp_agent_preset": "vision_heavy",
                    "mcp_http_bind_mode": "lan",
                    "mcp_http_discovery_enabled": true,
                    "mcp_inline_images_enabled": true,
                    "mcp_resource_reads_enabled": true,
                    "mcp_media_index_enabled": true,
                    "mcp_stream_enabled": true
                }
            }
        });
        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&presets).unwrap_or_default()
                }
            ],
            "presets": presets
        }))
    }

    pub(crate) async fn tool_apply_agent_preset(args: &Value) -> Result<Value, String> {
        let preset = args
            .get("preset")
            .and_then(|value| value.as_str())
            .ok_or_else(|| "apply_agent_preset requires a preset string.".to_string())?;
        let presets = Self::tool_list_agent_presets().await?;
        let settings_patch = presets
            .get("presets")
            .and_then(|value| value.get(preset))
            .and_then(|value| value.get("settings"))
            .cloned()
            .ok_or_else(|| format!("Unknown agent preset '{}'.", preset))?;
        Self::tool_set_mcp_settings(&settings_patch).await
    }

    pub(crate) async fn tool_set_mcp_settings(args: &Value) -> Result<Value, String> {
        let mut settings = crate::AppSettings::load();
        let changed = settings.apply_mcp_patch(args)?;
        settings.save()?;
        let summary = settings.mcp_settings_summary();
        Ok(json!({
            "content": [
                {
                    "type": "text",
                    "text": serde_json::to_string_pretty(&json!({
                        "changed": changed,
                        "settings": summary,
                        "restart_required": true,
                        "restart_reason": "HTTP bind, port, and mDNS advertisement are applied when the desktop app starts."
                    })).unwrap_or_default()
                }
            ],
            "changed": changed,
            "settings": summary,
            "restart_required": true
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;
    use std::fs;

    #[test]
    fn record_duration_rejects_negative_values() {
        let args = json!({ "duration_seconds": -1 });
        assert!(McpServer::record_duration_seconds(&args).is_err());
    }

    #[test]
    fn record_duration_rejects_huge_values() {
        let args = json!({ "duration_seconds": 3600 });
        assert!(McpServer::record_duration_seconds(&args).is_err());
    }

    #[test]
    fn watch_camera_plan_rejects_unbounded_requests() {
        assert!(McpServer::watch_camera_plan(&json!({ "max_frames": 11 })).is_err());
        assert!(McpServer::watch_camera_plan(&json!({ "interval_ms": 100 })).is_err());
        assert_eq!(
            McpServer::watch_camera_plan(&json!({ "max_frames": 2, "interval_ms": 500 })).unwrap(),
            (2, 500)
        );
    }

    #[test]
    fn resolve_active_device_uses_pinned_default_for_multiple_devices() {
        let state = AppState::new([0; 32], [0; 32], true);
        let (phone_a_tx, _phone_a_rx) = tokio::sync::mpsc::channel(1);
        let (phone_b_tx, _phone_b_rx) = tokio::sync::mpsc::channel(1);

        {
            let mut inner = state.inner.lock().unwrap();
            inner.paired_devices.insert(
                "phone-a".to_string(),
                crate::state::DeviceInfo {
                    id: "phone-a".to_string(),
                    name: "Pixel Kitchen".to_string(),
                    alias: String::new(),
                    os: "android".to_string(),
                    host: "192.168.1.10".to_string(),
                    port: 7878,
                    token: "token-a".to_string(),
                    public_key: [0; 32],
                    last_seen_ms: 20,
                    pinned: false,
                    exposed_to_mcp: true,
                },
            );
            inner.paired_devices.insert(
                "phone-b".to_string(),
                crate::state::DeviceInfo {
                    id: "phone-b".to_string(),
                    name: "Pixel Desk".to_string(),
                    alias: String::new(),
                    os: "android".to_string(),
                    host: "192.168.1.11".to_string(),
                    port: 7878,
                    token: "token-b".to_string(),
                    public_key: [0; 32],
                    last_seen_ms: 10,
                    pinned: true,
                    exposed_to_mcp: true,
                },
            );
        }

        state.register_session(
            "session-a".to_string(),
            "phone-a".to_string(),
            "Pixel Kitchen".to_string(),
            phone_a_tx,
            "192.168.1.10".to_string(),
            "ws".to_string(),
        );
        state.register_session(
            "session-b".to_string(),
            "phone-b".to_string(),
            "Pixel Desk".to_string(),
            phone_b_tx,
            "192.168.1.11".to_string(),
            "ws".to_string(),
        );

        assert_eq!(
            McpServer::resolve_active_device(&state, None).unwrap(),
            "phone-b"
        );
    }

    #[test]
    fn resolve_active_device_rejects_ambiguous_device_query() {
        let state = AppState::new([0; 32], [0; 32], true);
        let (phone_a_tx, _phone_a_rx) = tokio::sync::mpsc::channel(1);
        let (phone_b_tx, _phone_b_rx) = tokio::sync::mpsc::channel(1);

        {
            let mut inner = state.inner.lock().unwrap();
            for (id, name, host) in [
                ("phone-a", "Pixel Kitchen", "192.168.1.10"),
                ("phone-b", "Pixel Desk", "192.168.1.11"),
            ] {
                inner.paired_devices.insert(
                    id.to_string(),
                    crate::state::DeviceInfo {
                        id: id.to_string(),
                        name: name.to_string(),
                        alias: String::new(),
                        os: "android".to_string(),
                        host: host.to_string(),
                        port: 7878,
                        token: format!("token-{id}"),
                        public_key: [0; 32],
                        last_seen_ms: 10,
                        pinned: false,
                        exposed_to_mcp: true,
                    },
                );
            }
        }

        state.register_session(
            "session-a".to_string(),
            "phone-a".to_string(),
            "Pixel Kitchen".to_string(),
            phone_a_tx,
            "192.168.1.10".to_string(),
            "ws".to_string(),
        );
        state.register_session(
            "session-b".to_string(),
            "phone-b".to_string(),
            "Pixel Desk".to_string(),
            phone_b_tx,
            "192.168.1.11".to_string(),
            "ws".to_string(),
        );

        let error = McpServer::resolve_active_device(&state, Some("Pixel")).unwrap_err();
        assert!(error.contains("Ambiguous device query"));
    }

    #[test]
    fn resolve_tool_device_uses_exact_target_device_id() {
        let state = AppState::new([0; 32], [0; 32], true);
        let (phone_a_tx, _phone_a_rx) = tokio::sync::mpsc::channel(1);
        let (phone_b_tx, _phone_b_rx) = tokio::sync::mpsc::channel(1);

        {
            let mut inner = state.inner.lock().unwrap();
            for (id, name, pinned) in [
                ("phone-a", "Pixel Kitchen", false),
                ("phone-b", "Pixel Desk", true),
            ] {
                inner.paired_devices.insert(
                    id.to_string(),
                    crate::state::DeviceInfo {
                        id: id.to_string(),
                        name: name.to_string(),
                        alias: String::new(),
                        os: "android".to_string(),
                        host: "192.168.1.10".to_string(),
                        port: 7878,
                        token: format!("token-{id}"),
                        public_key: [0; 32],
                        last_seen_ms: 10,
                        pinned,
                        exposed_to_mcp: true,
                    },
                );
            }
        }

        state.register_session(
            "session-a".to_string(),
            "phone-a".to_string(),
            "Pixel Kitchen".to_string(),
            phone_a_tx,
            "192.168.1.10".to_string(),
            "ws".to_string(),
        );
        state.register_session(
            "session-b".to_string(),
            "phone-b".to_string(),
            "Pixel Desk".to_string(),
            phone_b_tx,
            "192.168.1.11".to_string(),
            "ws".to_string(),
        );

        assert_eq!(
            McpServer::resolve_tool_device(&state, &json!({ "target_device_id": "phone-a" }))
                .unwrap(),
            "phone-a"
        );
        assert_eq!(
            McpServer::resolve_tool_device(&state, &json!({ "device": "Pixel" })).unwrap_err(),
            "Ambiguous device query 'Pixel' matched multiple devices. Please specify a more precise name or UUID."
        );
    }

    #[test]
    fn resolve_tool_device_rejects_hidden_target_device_id() {
        let state = AppState::new([0; 32], [0; 32], true);
        let (tx, _rx) = tokio::sync::mpsc::channel(1);
        {
            let mut inner = state.inner.lock().unwrap();
            inner.paired_devices.insert(
                "phone-a".to_string(),
                crate::state::DeviceInfo {
                    id: "phone-a".to_string(),
                    name: "Pixel".to_string(),
                    alias: String::new(),
                    os: "android".to_string(),
                    host: "192.168.1.10".to_string(),
                    port: 7878,
                    token: "token-a".to_string(),
                    public_key: [0; 32],
                    last_seen_ms: 10,
                    pinned: false,
                    exposed_to_mcp: false,
                },
            );
        }
        state.register_session(
            "session-a".to_string(),
            "phone-a".to_string(),
            "Pixel".to_string(),
            tx,
            "192.168.1.10".to_string(),
            "ws".to_string(),
        );

        let err = McpServer::resolve_tool_device(&state, &json!({ "target_device_id": "phone-a" }))
            .unwrap_err();
        assert!(err.contains("hidden from MCP"));
    }

    #[tokio::test]
    async fn stdio_initialize_advertises_resources() {
        let state = AppState::new([0; 32], [0; 32], true);
        let response = McpServer::handle_mcp_request(
            json!({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {}
            }),
            &state,
        )
        .await;

        assert!(response["result"]["capabilities"]["tools"].is_object());
        assert!(response["result"]["capabilities"]["resources"].is_object());
    }

    #[tokio::test]
    async fn stdio_resources_read_can_return_existing_photo_from_disk() {
        let state = AppState::new([0; 32], [0; 32], true);
        let photo_path = std::env::temp_dir().join(format!(
            "captureport-resource-test-{}.jpg",
            ulid::Ulid::new()
        ));
        fs::write(&photo_path, [0xff, 0xd8, 0xff, 0xd9]).unwrap();

        let mut item = MediaItem {
            id: "resource-photo".to_string(),
            kind: "photo".to_string(),
            path: photo_path.to_string_lossy().to_string(),
            timestamp: 1712345678901,
            size_bytes: 4,
            width: 2,
            height: 2,
            base64_data: None,
            device_id: "device-1".to_string(),
            device_name: "Pixel 8".to_string(),
            label: String::new(),
            uri: String::new(),
            mime_type: String::new(),
            source_request_id: None,
            thumbnail_uri: String::new(),
            thumbnail_path: String::new(),
            notes: String::new(),
            capture_origin: "disk_scan".to_string(),
        };
        crate::media::normalize_media_item(&mut item);
        state.inner.lock().unwrap().media_history = vec![item.clone()];

        let response = McpServer::handle_mcp_request(
            json!({
                "jsonrpc": "2.0",
                "id": 2,
                "method": "resources/read",
                "params": { "uri": item.uri }
            }),
            &state,
        )
        .await;

        assert!(response.get("error").is_none(), "{response}");
        let contents = response["result"]["contents"].as_array().unwrap();
        assert!(contents
            .iter()
            .any(|content| content["mimeType"] == "application/json"));
        assert!(contents.iter().any(|content| {
            content["mimeType"] == "image/jpeg"
                && content["blob"]
                    == base64::prelude::BASE64_STANDARD.encode([0xff, 0xd8, 0xff, 0xd9])
        }));

        let _ = fs::remove_file(photo_path);
    }

    #[tokio::test]
    async fn stdio_resources_read_can_return_photo_thumbnail() {
        let state = AppState::new([0; 32], [0; 32], true);
        let root = std::env::temp_dir().join(format!(
            "captureport-thumbnail-resource-{}",
            ulid::Ulid::new()
        ));
        fs::create_dir_all(&root).unwrap();
        let thumbnail_path = root.join("thumb.jpg");
        fs::write(&thumbnail_path, [0xff, 0xd8, 0xff, 0xd9]).unwrap();

        let mut item = MediaItem {
            id: "resource-photo".to_string(),
            kind: "photo".to_string(),
            path: thumbnail_path.to_string_lossy().to_string(),
            timestamp: 1712345678901,
            size_bytes: 4,
            width: 2,
            height: 2,
            base64_data: None,
            device_id: "device-1".to_string(),
            device_name: "Pixel 8".to_string(),
            label: String::new(),
            uri: String::new(),
            mime_type: String::new(),
            source_request_id: None,
            thumbnail_uri: "captureport://media/resource-photo/thumbnail".to_string(),
            thumbnail_path: thumbnail_path.to_string_lossy().to_string(),
            notes: String::new(),
            capture_origin: "disk_scan".to_string(),
        };
        crate::media::normalize_media_item(&mut item);
        state.inner.lock().unwrap().media_history = vec![item.clone()];

        let response = McpServer::handle_mcp_request(
            json!({
                "jsonrpc": "2.0",
                "id": 3,
                "method": "resources/read",
                "params": { "uri": item.thumbnail_uri }
            }),
            &state,
        )
        .await;

        assert!(response.get("error").is_none(), "{response}");
        let contents = response["result"]["contents"].as_array().unwrap();
        assert_eq!(contents[0]["mimeType"], "image/jpeg");
        assert_eq!(
            contents[0]["blob"],
            base64::prelude::BASE64_STANDARD.encode([0xff, 0xd8, 0xff, 0xd9])
        );

        let _ = fs::remove_dir_all(root);
    }

    #[tokio::test]
    async fn stdio_tool_failure_returns_tool_error_result() {
        let state = AppState::new([0; 32], [0; 32], true);
        let response = McpServer::handle_mcp_request(
            json!({
                "jsonrpc": "2.0",
                "id": 7,
                "method": "tools/call",
                "params": {
                    "name": "capture_photo",
                    "arguments": {}
                }
            }),
            &state,
        )
        .await;

        assert!(response.get("error").is_none());
        assert_eq!(response["result"]["isError"], true);
        assert!(response["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("No active mobile devices"));
    }
}
