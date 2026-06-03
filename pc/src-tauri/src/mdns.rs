use anyhow::{Context, Result};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

pub struct MdnsAdvertiser {
    daemon: ServiceDaemon,
    service_infos: Vec<ServiceInfo>,
}

impl MdnsAdvertiser {
    pub fn start(port: u16, hosts: Vec<String>) -> Result<Self> {
        let daemon = ServiceDaemon::new().context("Failed to initialize mDNS Service Daemon")?;

        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "Desktop-Machine".to_string());

        let sanitized_hostname = hostname
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '-')
            .collect::<String>();

        let service_type = "_captureport._tcp.local.";

        let mut service_infos = Vec::new();

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
                port,
                Some(properties),
            ) {
                if daemon.register(service_info.clone()).is_ok() {
                    service_infos.push(service_info);
                    tracing::info!("mDNS advertiser registered on interface IP: {}", host_ip);
                }
            }
        }

        Ok(Self {
            daemon,
            service_infos,
        })
    }

    pub fn stop(&self) -> Result<()> {
        for info in &self.service_infos {
            if let Ok(receiver) = self.daemon.unregister(info.get_fullname()) {
                // Wait briefly for unregistration to complete
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
