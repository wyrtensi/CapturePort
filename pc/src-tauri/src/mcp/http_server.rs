use crate::mcp::tools::CapturePortTools;
use crate::state::AppState;
use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, Request, StatusCode};
use axum::middleware::{from_fn_with_state, Next};
use axum::response::{IntoResponse, Response};
use rmcp::transport::streamable_http_server::{
    session::local::LocalSessionManager, StreamableHttpServerConfig, StreamableHttpService,
};
use std::sync::Arc;
use tokio::net::TcpListener;

pub struct McpHttpServer {
    addr: std::net::SocketAddr,
}

impl McpHttpServer {
    pub async fn start(
        state: AppState,
        _app_handle: Option<tauri::AppHandle>,
        bind_addr: std::net::Ipv4Addr,
        port: u16,
    ) -> anyhow::Result<Self> {
        let addr = std::net::SocketAddr::from((bind_addr, port));

        let tools_factory = move || -> Result<CapturePortTools, std::io::Error> {
            Ok(CapturePortTools::new(state.clone()))
        };

        let settings = crate::AppSettings::load();
        let config = StreamableHttpServerConfig::default()
            .with_allowed_hosts(settings.mcp_http_allowed_hosts())
            .with_allowed_origins(settings.mcp_http_allowed_origins())
            .with_stateful_mode(false)
            .with_json_response(true);

        let session_manager = Arc::new(LocalSessionManager::default());

        let service = StreamableHttpService::new(tools_factory, session_manager, config);

        let listener = TcpListener::bind(addr).await?;
        let auth_token = settings.mcp_http_auth_token();

        tracing::info!("MCP Streamable HTTP server listening on {}", addr);

        let service_clone = service.clone();
        tokio::spawn(async move {
            let router = axum::Router::new()
                .nest_service("/mcp", service_clone)
                .layer(from_fn_with_state(auth_token, require_mcp_bearer_auth));

            if let Err(e) = axum::serve(listener, router).await {
                tracing::error!("MCP HTTP server error: {:?}", e);
            }
        });

        Ok(Self { addr })
    }

    pub fn addr(&self) -> std::net::SocketAddr {
        self.addr
    }
}

async fn require_mcp_bearer_auth(
    State(auth_token): State<Option<String>>,
    headers: HeaderMap,
    request: Request<Body>,
    next: Next,
) -> Response {
    let Some(token) = auth_token else {
        return next.run(request).await;
    };

    let expected = format!("Bearer {token}");
    let authorized = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .map(|value| value == expected)
        .unwrap_or(false);

    if authorized {
        next.run(request).await
    } else {
        (
            StatusCode::UNAUTHORIZED,
            "Missing or invalid MCP bearer token",
        )
            .into_response()
    }
}
