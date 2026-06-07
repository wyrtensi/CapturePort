use crate::mcp::McpServer;
use crate::state::AppState;
use rmcp::handler::server::{router::tool::ToolRouter, wrapper::Parameters};
use rmcp::model::{
    AnnotateAble, CallToolResult, Content, ListResourceTemplatesResult, ListResourcesResult,
    PaginatedRequestParams, RawResource, RawResourceTemplate, ReadResourceRequestParams,
    ReadResourceResult, ResourceContents, ServerCapabilities, ServerInfo,
};
use rmcp::schemars::JsonSchema;
use rmcp::service::{RequestContext, RoleServer};
use rmcp::tool;
use rmcp::tool_handler;
use rmcp::tool_router;
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Clone)]
pub struct CapturePortTools {
    pub state: AppState,
    #[allow(dead_code)]
    tool_router: ToolRouter<Self>,
}

impl CapturePortTools {
    pub fn new(state: AppState) -> Self {
        Self {
            state,
            tool_router: Self::tool_router(),
        }
    }
}

#[derive(Deserialize, JsonSchema)]
struct ListDevicesParams {}

#[derive(Deserialize, JsonSchema)]
struct CapturePhotoParams {
    /// Exact device id from list_devices. Prefer this when more than one phone is online.
    #[serde(default)]
    pub target_device_id: Option<String>,
    /// Optional device UUID, name, or substring. Defaults to the first active device.
    #[serde(default)]
    pub device: Option<String>,
    /// Temporarily enable the phone torch before taking the photo.
    #[serde(default)]
    pub use_flash: Option<bool>,
}

#[derive(Deserialize, JsonSchema)]
struct CaptureScreenshotParams {
    /// Exact device id from list_devices. Prefer this when more than one phone is online.
    #[serde(default)]
    pub target_device_id: Option<String>,
    /// Optional device UUID, name, or substring. Defaults to the first active device.
    #[serde(default)]
    pub device: Option<String>,
    /// Temporarily enable the phone torch before taking the image.
    #[serde(default)]
    pub use_flash: Option<bool>,
}

#[derive(Deserialize, JsonSchema)]
struct SetFlashlightParams {
    /// Exact device id from list_devices. Prefer this when more than one phone is online.
    #[serde(default)]
    pub target_device_id: Option<String>,
    /// Optional device UUID, name, or substring. Defaults to the first active device.
    #[serde(default)]
    pub device: Option<String>,
    /// true turns the torch on, false turns it off.
    pub enabled: bool,
}

#[derive(Deserialize, JsonSchema)]
struct RecordVideoParams {
    /// Exact device id from list_devices. Prefer this when more than one phone is online.
    #[serde(default)]
    pub target_device_id: Option<String>,
    /// Optional device UUID, name, or substring. Defaults to the first active device.
    #[serde(default)]
    pub device: Option<String>,
    /// Optional recording duration in seconds (default 10).
    #[serde(default)]
    pub duration_seconds: Option<i64>,
}

#[derive(Deserialize, JsonSchema)]
struct GetDeviceClipboardParams {
    /// Exact device id from list_devices. Prefer this when more than one phone is online.
    #[serde(default)]
    pub target_device_id: Option<String>,
    /// Optional device UUID, name, or substring. Defaults to the first active device.
    #[serde(default)]
    pub device: Option<String>,
}

#[derive(Deserialize, JsonSchema)]
struct SetDeviceClipboardParams {
    /// Exact device id from list_devices. Prefer this when more than one phone is online.
    #[serde(default)]
    pub target_device_id: Option<String>,
    /// Optional device UUID, name, or substring. Defaults to the first active device.
    #[serde(default)]
    pub device: Option<String>,
    /// The text content to copy to the device clipboard.
    pub text: String,
}

#[derive(Deserialize, JsonSchema)]
struct ListMediaParams {
    #[serde(default)]
    pub limit: Option<u64>,
    #[serde(default)]
    pub offset: Option<u64>,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub device: Option<String>,
    #[serde(default)]
    pub query: Option<String>,
    #[serde(default)]
    pub label_contains: Option<String>,
    #[serde(default)]
    pub since_unix_ms: Option<u64>,
    #[serde(default)]
    pub until_unix_ms: Option<u64>,
    #[serde(default)]
    pub created_after: Option<u64>,
    #[serde(default)]
    pub created_before: Option<u64>,
    #[serde(default)]
    pub include_missing: Option<bool>,
    #[serde(default)]
    pub include_thumbnails: Option<bool>,
}

#[derive(Deserialize, JsonSchema)]
struct GetMediaParams {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub uri: Option<String>,
    #[serde(default)]
    pub latest: Option<bool>,
}

#[derive(Deserialize, JsonSchema)]
struct WatchCameraParams {
    #[serde(default)]
    pub target_device_id: Option<String>,
    #[serde(default)]
    pub device: Option<String>,
    #[serde(default)]
    pub max_frames: Option<u64>,
    #[serde(default)]
    pub interval_ms: Option<u64>,
}

#[derive(Deserialize, JsonSchema)]
struct CompareMediaParams {
    #[serde(default)]
    pub left: Option<String>,
    #[serde(default)]
    pub right: Option<String>,
}

#[derive(Deserialize, JsonSchema)]
struct ApplyAgentPresetParams {
    pub preset: String,
}

#[derive(Deserialize, JsonSchema)]
struct SetMcpSettingsParams {
    #[serde(default)]
    pub mcp_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_http_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_http_discovery_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_http_port: Option<u16>,
    #[serde(default)]
    pub mcp_http_bind_mode: Option<String>,
    #[serde(default)]
    pub mcp_agent_mode: Option<String>,
    #[serde(default)]
    pub mcp_agent_preset: Option<String>,
    #[serde(default)]
    pub mcp_stream_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_media_index_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_resource_reads_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_inline_images_enabled: Option<bool>,
    #[serde(default)]
    pub mcp_allowed_hosts: Option<Vec<String>>,
    #[serde(default)]
    pub mcp_allowed_origins: Option<Vec<String>>,
    #[serde(default)]
    pub mcp_http_auth_token: Option<String>,
}

fn value_to_call_tool_result(result: Result<Value, String>) -> CallToolResult {
    match result {
        Ok(value) => {
            let mut content = Vec::new();
            if let Some(items) = value.get("content").and_then(|c| c.as_array()) {
                for item in items {
                    match item.get("type").and_then(|t| t.as_str()) {
                        Some("image") => {
                            if let Some(data) = item.get("data").and_then(|d| d.as_str()) {
                                let mime = item
                                    .get("mimeType")
                                    .or_else(|| item.get("mime_type"))
                                    .and_then(|m| m.as_str())
                                    .unwrap_or("image/jpeg");
                                content.push(Content::image(data.to_string(), mime.to_string()));
                            }
                        }
                        Some("text") => {
                            if let Some(text) = item.get("text").and_then(|t| t.as_str()) {
                                content.push(Content::text(text.to_string()));
                            }
                        }
                        _ => {}
                    }
                }
            }
            if content.is_empty() {
                content.push(Content::text(
                    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string()),
                ));
            }
            let mut tool_result = CallToolResult::success(content);
            tool_result.structured_content = Some(value);
            tool_result
        }
        Err(err) => CallToolResult::error(vec![Content::text(err)]),
    }
}

#[tool_router]
impl CapturePortTools {
    #[tool(
        description = "Retrieve list of paired mobile phones and check if they are currently online."
    )]
    async fn list_devices(&self, _params: Parameters<ListDevicesParams>) -> CallToolResult {
        value_to_call_tool_result(McpServer::tool_list_devices(&self.state).await)
    }

    #[tool(
        description = "Trigger the camera on a paired phone to snap a photo and return the image directly to the agent."
    )]
    async fn capture_photo(&self, params: Parameters<CapturePhotoParams>) -> CallToolResult {
        let args = serde_json::json!({
            "target_device_id": params.0.target_device_id,
            "device": params.0.device,
            "use_flash": params.0.use_flash
        });
        value_to_call_tool_result(McpServer::tool_capture_photo(&self.state, &args).await)
    }

    #[tool(
        description = "Trigger the camera on a paired phone to capture a photo/screenshot and return the image data directly."
    )]
    async fn capture_screenshot(
        &self,
        params: Parameters<CaptureScreenshotParams>,
    ) -> CallToolResult {
        let args = serde_json::json!({
            "target_device_id": params.0.target_device_id,
            "device": params.0.device,
            "use_flash": params.0.use_flash
        });
        value_to_call_tool_result(McpServer::tool_capture_screenshot(&self.state, &args).await)
    }

    #[tool(description = "Turn the selected phone flashlight/torch on or off for continuous lighting.")]
    async fn set_flashlight(&self, params: Parameters<SetFlashlightParams>) -> CallToolResult {
        let args = serde_json::json!({
            "target_device_id": params.0.target_device_id,
            "device": params.0.device,
            "enabled": params.0.enabled
        });
        value_to_call_tool_result(McpServer::tool_set_flashlight(&self.state, &args).await)
    }

    #[tool(description = "Record video from the camera of the specified mobile device.")]
    async fn record_video(&self, params: Parameters<RecordVideoParams>) -> CallToolResult {
        let args = serde_json::json!({
            "target_device_id": params.0.target_device_id,
            "device": params.0.device,
            "duration_seconds": params.0.duration_seconds
        });
        value_to_call_tool_result(McpServer::tool_record_video(&self.state, &args).await)
    }

    #[tool(description = "Retrieve the current clipboard content from the target mobile device.")]
    async fn get_device_clipboard(
        &self,
        params: Parameters<GetDeviceClipboardParams>,
    ) -> CallToolResult {
        let args = serde_json::json!({ "target_device_id": params.0.target_device_id, "device": params.0.device });
        value_to_call_tool_result(McpServer::tool_get_device_clipboard(&self.state, &args).await)
    }

    #[tool(description = "Set the clipboard content on the target mobile device.")]
    async fn set_device_clipboard(
        &self,
        params: Parameters<SetDeviceClipboardParams>,
    ) -> CallToolResult {
        let args = serde_json::json!({ "target_device_id": params.0.target_device_id, "device": params.0.device, "text": params.0.text });
        value_to_call_tool_result(McpServer::tool_set_device_clipboard(&self.state, &args).await)
    }

    #[tool(
        description = "Retrieve the most recently captured photo stored in memory without snapping a new one."
    )]
    async fn snap_frame(&self, _params: Parameters<ListDevicesParams>) -> CallToolResult {
        value_to_call_tool_result(McpServer::tool_snap_frame(&self.state).await)
    }

    #[tool(
        description = "Look through the selected phone camera now: capture a fresh photo and return image content plus metadata."
    )]
    async fn look_camera(&self, params: Parameters<CapturePhotoParams>) -> CallToolResult {
        let args = serde_json::json!({
            "target_device_id": params.0.target_device_id,
            "device": params.0.device,
            "use_flash": params.0.use_flash
        });
        value_to_call_tool_result(McpServer::tool_capture_photo(&self.state, &args).await)
    }

    #[tool(
        description = "List recent CapturePort media without inline base64, optimized for ordinary AI agents to understand what was captured and when."
    )]
    async fn list_media(&self, params: Parameters<ListMediaParams>) -> CallToolResult {
        let args = json!({
            "limit": params.0.limit,
            "offset": params.0.offset,
            "kind": params.0.kind,
            "device": params.0.device,
            "query": params.0.query,
            "label_contains": params.0.label_contains,
            "since_unix_ms": params.0.since_unix_ms,
            "until_unix_ms": params.0.until_unix_ms,
            "created_after": params.0.created_after,
            "created_before": params.0.created_before,
            "include_missing": params.0.include_missing,
            "include_thumbnails": params.0.include_thumbnails
        });
        value_to_call_tool_result(McpServer::tool_list_media(&self.state, &args).await)
    }

    #[tool(
        description = "Search indexed CapturePort photos and videos by kind, device, query, and timestamp bounds."
    )]
    async fn search_media(&self, params: Parameters<ListMediaParams>) -> CallToolResult {
        self.list_media(params).await
    }

    #[tool(
        description = "Get a media item by id, URI, or latest=true. Photos include image content when available."
    )]
    async fn get_media(&self, params: Parameters<GetMediaParams>) -> CallToolResult {
        let args = json!({
            "id": params.0.id,
            "uri": params.0.uri,
            "latest": params.0.latest
        });
        value_to_call_tool_result(McpServer::tool_get_media(&self.state, &args).await)
    }

    #[tool(
        description = "Read CapturePort MCP transport, discovery, adaptive mode, and stream mode settings."
    )]
    async fn get_mcp_settings(&self, _params: Parameters<ListDevicesParams>) -> CallToolResult {
        value_to_call_tool_result(McpServer::tool_get_mcp_settings().await)
    }

    #[tool(
        description = "Return online/exposed device status plus latest capture metadata for agents."
    )]
    async fn camera_status(&self, _params: Parameters<ListDevicesParams>) -> CallToolResult {
        value_to_call_tool_result(McpServer::tool_camera_status(&self.state).await)
    }

    #[tool(
        description = "Capture a short sequence of fresh camera frames from the selected phone."
    )]
    async fn watch_camera(&self, params: Parameters<WatchCameraParams>) -> CallToolResult {
        let args = json!({
            "target_device_id": params.0.target_device_id,
            "device": params.0.device,
            "max_frames": params.0.max_frames,
            "interval_ms": params.0.interval_ms
        });
        value_to_call_tool_result(McpServer::tool_watch_camera(&self.state, &args).await)
    }

    #[tool(
        description = "Compare two indexed CapturePort media items by id or URI using metadata and byte equality."
    )]
    async fn compare_media(&self, params: Parameters<CompareMediaParams>) -> CallToolResult {
        let args = json!({
            "left": params.0.left,
            "right": params.0.right
        });
        value_to_call_tool_result(McpServer::tool_compare_media(&self.state, &args).await)
    }

    #[tool(
        description = "List ready-made MCP access presets for privacy, local agent, LAN agent, and vision-heavy workflows."
    )]
    async fn list_agent_presets(&self, _params: Parameters<ListDevicesParams>) -> CallToolResult {
        value_to_call_tool_result(McpServer::tool_list_agent_presets().await)
    }

    #[tool(
        description = "Apply one ready-made MCP access preset. Transport changes may require restart."
    )]
    async fn apply_agent_preset(
        &self,
        params: Parameters<ApplyAgentPresetParams>,
    ) -> CallToolResult {
        value_to_call_tool_result(
            McpServer::tool_apply_agent_preset(&json!({ "preset": params.0.preset })).await,
        )
    }

    #[tool(
        description = "Update CapturePort MCP settings. Transport changes may require restarting the desktop app."
    )]
    async fn set_mcp_settings(&self, params: Parameters<SetMcpSettingsParams>) -> CallToolResult {
        let mut args = serde_json::Map::new();
        if let Some(value) = params.0.mcp_enabled {
            args.insert("mcp_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_http_enabled {
            args.insert("mcp_http_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_http_discovery_enabled {
            args.insert("mcp_http_discovery_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_http_port {
            args.insert("mcp_http_port".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_http_bind_mode {
            args.insert("mcp_http_bind_mode".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_agent_mode {
            args.insert("mcp_agent_mode".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_agent_preset {
            args.insert("mcp_agent_preset".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_stream_enabled {
            args.insert("mcp_stream_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_media_index_enabled {
            args.insert("mcp_media_index_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_resource_reads_enabled {
            args.insert("mcp_resource_reads_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_inline_images_enabled {
            args.insert("mcp_inline_images_enabled".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_allowed_hosts {
            args.insert("mcp_allowed_hosts".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_allowed_origins {
            args.insert("mcp_allowed_origins".to_string(), json!(value));
        }
        if let Some(value) = params.0.mcp_http_auth_token {
            args.insert("mcp_http_auth_token".to_string(), json!(value));
        }
        value_to_call_tool_result(McpServer::tool_set_mcp_settings(&Value::Object(args)).await)
    }
}

#[tool_handler]
impl rmcp::handler::server::ServerHandler for CapturePortTools {
    fn get_info(&self) -> ServerInfo {
        let settings = crate::AppSettings::load();
        let capabilities =
            if settings.mcp_media_index_enabled() && settings.mcp_resource_reads_enabled() {
                ServerCapabilities::builder()
                    .enable_tools()
                    .enable_resources()
                    .build()
            } else {
                ServerCapabilities::builder().enable_tools().build()
            };
        ServerInfo::new(capabilities)
            .with_server_info(rmcp::model::Implementation::new(
                "CapturePort MCP Server",
                env!("CARGO_PKG_VERSION"),
            ))
            .with_instructions("CapturePort MCP Server - enables AI tools with vision (real-time camera feeds) and clipboard access on your machine.")
    }

    async fn list_resources(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListResourcesResult, rmcp::ErrorData> {
        let resources = McpServer::resource_list(&self.state)
            .into_iter()
            .map(|value| {
                serde_json::from_value::<RawResource>(value)
                    .map(|resource| resource.no_annotation())
                    .map_err(|e| {
                        rmcp::ErrorData::internal_error(
                            format!("Failed to serialize resource descriptor: {e}"),
                            None,
                        )
                    })
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(ListResourcesResult::with_all_items(resources))
    }

    async fn list_resource_templates(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListResourceTemplatesResult, rmcp::ErrorData> {
        let settings = crate::AppSettings::load();
        if !settings.mcp_media_index_enabled() || !settings.mcp_resource_reads_enabled() {
            return Ok(ListResourceTemplatesResult::with_all_items(Vec::new()));
        }

        let templates = McpServer::resource_templates()
            .into_iter()
            .map(|value| {
                serde_json::from_value::<RawResourceTemplate>(value)
                    .map(|template| template.no_annotation())
                    .map_err(|e| {
                        rmcp::ErrorData::internal_error(
                            format!("Failed to serialize resource template: {e}"),
                            None,
                        )
                    })
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(ListResourceTemplatesResult::with_all_items(templates))
    }

    async fn read_resource(
        &self,
        request: ReadResourceRequestParams,
        _context: RequestContext<RoleServer>,
    ) -> Result<ReadResourceResult, rmcp::ErrorData> {
        let contents = McpServer::read_resource_contents(&self.state, &request.uri)
            .map_err(|message| rmcp::ErrorData::resource_not_found(message, None))?
            .into_iter()
            .map(|value| {
                serde_json::from_value::<ResourceContents>(value).map_err(|e| {
                    rmcp::ErrorData::internal_error(
                        format!("Failed to serialize resource contents: {e}"),
                        None,
                    )
                })
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(ReadResourceResult::new(contents))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rmcp::model::RawContent;

    #[test]
    fn value_to_call_tool_result_preserves_image_content() {
        let result = value_to_call_tool_result(Ok(json!({
            "content": [
                {
                    "type": "image",
                    "data": "abc",
                    "mimeType": "image/jpeg"
                },
                {
                    "type": "text",
                    "text": "{\"label\":\"latest\"}"
                }
            ],
            "media": {
                "label": "latest"
            }
        })));

        assert_eq!(result.is_error, Some(false));
        assert!(
            matches!(&result.content[0].raw, RawContent::Image(image) if image.data == "abc" && image.mime_type == "image/jpeg")
        );
        assert!(
            matches!(&result.content[1].raw, RawContent::Text(text) if text.text.contains("latest"))
        );
        assert!(result.structured_content.is_some());
    }

    #[test]
    fn value_to_call_tool_result_marks_tool_errors() {
        let result = value_to_call_tool_result(Err("camera offline".to_string()));

        assert_eq!(result.is_error, Some(true));
        assert!(
            matches!(&result.content[0].raw, RawContent::Text(text) if text.text == "camera offline")
        );
    }
}
