use mdns_sd::{ServiceDaemon, ServiceInfo, IfKind};
use std::collections::HashMap;
use anyhow::{Result, Context};
use crate::pairing::qr::QrGenerator;

pub struct MdnsAdvertiser {
    daemon: ServiceDaemon,
    service_infos: Vec<ServiceInfo>,
    _shutdown_tx: tokio::sync::oneshot::Sender<()>,
}

impl MdnsAdvertiser {
    pub fn start(port: u16, tailscale_dns: Option<String>) -> Result<Self> {
        let daemon = ServiceDaemon::new()
            .context("Failed to initialize mDNS Service Daemon")?;

        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "Desktop-Machine".to_string());

        let sanitized_hostname = hostname
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '-')
            .collect::<String>();

        let service_type = "_captureport._tcp.local.";
        
        let mut service_infos = Vec::new();
        let hosts = QrGenerator::get_pairing_hosts(tailscale_dns);

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

        // Spawn background task to monitor and disable VPN interfaces in mDNS
        let daemon_clone = daemon.clone();
        let (shutdown_tx, mut shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        tauri::async_runtime::spawn(async move {
            let mut last_vpns = std::collections::HashSet::new();
            let mut interval = tokio::time::interval(std::time::Duration::from_secs(5));
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => {
                        tracing::info!("mDNS VPN monitor background task shutting down");
                        break;
                    }
                    _ = interval.tick() => {
                        let current_vpns = crate::net::get_vpn_interfaces();
                        let current_set: std::collections::HashSet<u32> = current_vpns.iter().map(|(_, idx)| *idx).collect();

                        // Re-enable interfaces that are no longer VPNs
                        for idx in last_vpns.difference(&current_set) {
                            if let Err(e) = daemon_clone.enable_interface(IfKind::IndexV4(*idx)) {
                                tracing::warn!("Failed to re-enable mDNS on interface index {}: {:?}", idx, e);
                            } else {
                                tracing::info!("mDNS: Re-enabled interface index {}", idx);
                            }
                        }

                        // Disable new VPN interfaces
                        for idx in current_set.difference(&last_vpns) {
                            if let Err(e) = daemon_clone.disable_interface(IfKind::IndexV4(*idx)) {
                                tracing::warn!("Failed to disable mDNS on interface index {}: {:?}", idx, e);
                            } else {
                                tracing::info!("mDNS: Disabled interface index {}", idx);
                            }
                        }

                        last_vpns = current_set;
                    }
                }
            }
        });

        Ok(Self {
            daemon,
            service_infos,
            _shutdown_tx: shutdown_tx,
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
