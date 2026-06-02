use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use anyhow::{Result, Context};
use crate::pairing::qr::QrGenerator;

pub struct MdnsAdvertiser {
    daemon: ServiceDaemon,
    service_info: ServiceInfo,
}

impl MdnsAdvertiser {
    pub fn start(port: u16) -> Result<Self> {
        let daemon = ServiceDaemon::new()
            .context("Failed to initialize mDNS Service Daemon")?;

        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "Desktop-Machine".to_string());

        // Sanitizing hostname for mDNS local domain (remove spaces and special characters)
        let sanitized_hostname = hostname
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '-')
            .collect::<String>();

        let service_type = "_captureport._tcp.local.";
        let instance_name = format!("CapturePort-{}", sanitized_hostname);
        let host_name = format!("{}.local.", sanitized_hostname);

        // Find current local IP
        let local_ip = QrGenerator::get_local_ip()
            .unwrap_or_else(|| "127.0.0.1".to_string());

        let mut properties = HashMap::new();
        properties.insert("v".to_string(), "1".to_string());
        properties.insert("name".to_string(), hostname);

        let service_info = ServiceInfo::new(
            service_type,
            &instance_name,
            &host_name,
            &local_ip,
            port,
            Some(properties),
        ).context("Failed to construct mDNS ServiceInfo")?;

        daemon.register(service_info.clone())
            .context("Failed to register mDNS service info")?;

        tracing::info!("mDNS advertiser started successfully: {} on {}:{}", instance_name, local_ip, port);

        Ok(Self {
            daemon,
            service_info,
        })
    }

    pub fn stop(&self) -> Result<()> {
        self.daemon.unregister(self.service_info.get_type())
            .context("Failed to unregister mDNS service")?;
        Ok(())
    }
}
