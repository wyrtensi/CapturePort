pub mod envelope;
pub mod handler;

use crate::state::AppState;
use crate::ws::handler::SocketHandler;
use anyhow::{Context, Result};
use axum::{
    extract::{ws::WebSocketUpgrade, ConnectInfo, State},
    response::IntoResponse,
    routing::get,
    Router,
};
use std::net::SocketAddr;

pub struct WsServer;

impl WsServer {
    // Starts the axum web server running in the background on LAN
    pub async fn start(
        state: AppState,
        app_handle: Option<tauri::AppHandle>,
        port: u16,
    ) -> Result<()> {
        let app = Router::new()
            .route("/ws", get(ws_handler))
            .with_state((state, app_handle));

        let addr = SocketAddr::from(([0, 0, 0, 0], port));

        let listener = tokio::net::TcpListener::bind(addr)
            .await
            .context(format!("Failed to bind TcpListener on port {}", port))?;

        tracing::info!("Axum WebSocket server listening on local network: {}", addr);

        // Run axum server in a spawned Tokio background context
        tokio::spawn(async move {
            if let Err(e) = axum::serve(
                listener,
                app.into_make_service_with_connect_info::<SocketAddr>(),
            ).await {
                tracing::error!("Axum server runtime error: {:?}", e);
            }
        });

        Ok(())
    }
}

async fn ws_handler(
    ws: WebSocketUpgrade,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State((state, app_handle)): State<(AppState, Option<tauri::AppHandle>)>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| SocketHandler::handle_socket(socket, addr, state, app_handle))
}
