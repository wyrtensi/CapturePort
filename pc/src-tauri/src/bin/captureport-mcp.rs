//! CapturePort MCP stdio proxy binary
//!
//! This binary provides the stdio MCP transport for Claude Desktop.
//! It directly uses the library's stdio MCP implementation.

use anyhow::Result;
use tracing::info;

fn main() -> Result<()> {
    // Initialize logging to stderr to avoid breaking stdout JSON-RPC channel
    let _ = tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .try_init();

    info!("Starting CapturePort MCP stdio proxy");

    // Run the stdio MCP server - this is the same as --mcp-stdio mode
    // but as a separate binary for easier Claude Desktop configuration
    captureport_lib::run_mcp_stdio();
    Ok(())
}
