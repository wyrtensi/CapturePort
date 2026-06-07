use anyhow::{Context, Result};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

pub struct MdnsAdvertiser {
    daemon: ServiceDaemon,
    service_infos: Vec<ServiceInfo>,
    mcp_service_infos: Vec<ServiceInfo>,
}

impl MdnsAdvertiser {
    pub fn start(port: u16, hosts: Vec<String>) -> Result<Self> {
        Self::start_with_mcp(port, 0, hosts)
    }

    pub fn start_with_mcp(ws_port: u16, mcp_port: u16, hosts: Vec<String>) -> Result<Self> {
        let daemon = ServiceDaemon::new().context("Failed to initialize mDNS Service Daemon")?;

        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "Desktop-Machine".to_string());

        let sanitized_hostname = hostname
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '-')
            .collect::<String>();

        let mut service_infos = Vec::new();
        let mut mcp_service_infos = Vec::new();

        // Register main CapturePort service (_captureport._tcp.local.)
        let service_type = "_captureport._tcp.local.";
        for (idx, host_ip) in hosts.iter().enumerate() {
            let instance_name = format!("CapturePort-{}-{}", sanitized_hostname, idx);
            let host_name = format!("{}-{}.local.", sanitized_hostname, idx);
            let mut properties = HashMap::new();
            properties.insert("v".to_string(), "1".to_string());
            properties.insert("name".to_string(), hostname.clone());

            if let Ok(service_info) = ServiceInfo::new(
                service_type,
                &instance_name,
                &host_name,
                host_ip,
                ws_port,
                Some(properties),
            ) {
                if daemon.register(service_info.clone()).is_ok() {
                    service_infos.push(service_info);
                    tracing::info!(
                        "mDNS advertiser registered on interface IP: {} (port {})",
                        host_ip,
                        ws_port
                    );
                }
            }
        }

        // Register MCP HTTP service (_captureport-mcp._tcp.local.) if port > 0
        if mcp_port > 0 {
            let mcp_service_type = "_captureport-mcp._tcp.local.";
            for (idx, host_ip) in hosts.iter().enumerate() {
                let instance_name = format!("CapturePort-MCP-{}-{}", sanitized_hostname, idx);
                let host_name = format!("{}-{}.local.", sanitized_hostname, idx);
                let mut properties = HashMap::new();
                properties.insert("v".to_string(), "1".to_string());
                properties.insert("transport".to_string(), "streamable-http".to_string());
                properties.insert("path".to_string(), "/mcp".to_string());
                properties.insert("name".to_string(), hostname.clone());
                let settings = crate::AppSettings::load();
                let caps = if settings.mcp_media_index_enabled()
                    && settings.mcp_resource_reads_enabled()
                {
                    "tools,resources"
                } else {
                    "tools"
                };
                properties.insert("caps".to_string(), caps.to_string());
                properties.insert("mode".to_string(), "adaptive".to_string());
                properties.insert(
                    "auth".to_string(),
                    if settings.mcp_http_auth_enabled() {
                        "bearer"
                    } else {
                        "none"
                    }
                    .to_string(),
                );
                properties.insert("version".to_string(), env!("CARGO_PKG_VERSION").to_string());

                if let Ok(service_info) = ServiceInfo::new(
                    mcp_service_type,
                    &instance_name,
                    &host_name,
                    host_ip,
                    mcp_port,
                    Some(properties),
                ) {
                    if daemon.register(service_info.clone()).is_ok() {
                        mcp_service_infos.push(service_info);
                        tracing::info!(
                            "mDNS MCP advertiser registered on interface IP: {} (port {})",
                            host_ip,
                            mcp_port
                        );
                    }
                }
            }
        }

        Ok(Self {
            daemon,
            service_infos,
            mcp_service_infos,
        })
    }

    pub fn stop(&self) -> Result<()> {
        for info in &self.service_infos {
            if let Ok(receiver) = self.daemon.unregister(info.get_fullname()) {
                let _ = receiver.recv_timeout(std::time::Duration::from_millis(100));
            }
        }
        for info in &self.mcp_service_infos {
            if let Ok(receiver) = self.daemon.unregister(info.get_fullname()) {
                let _ = receiver.recv_timeout(std::time::Duration::from_millis(100));
            }
        }
        if let Ok(receiver) = self.daemon.shutdown() {
            let _ = receiver.recv_timeout(std::time::Duration::from_millis(200));
        }
        Ok(())
    }
}

impl Drop for MdnsAdvertiser {
    fn drop(&mut self) {
        if let Err(e) = self.stop() {
            tracing::error!("Failed to stop mDNS advertiser on drop: {:?}", e);
        }
    }
}
