use base64::prelude::*;

fn is_host_only_or_virtual_name(name: &str) -> bool {
    let lowered = name.to_ascii_lowercase();
    [
        "loopback",
        "host-only",
        "vethernet",
        "hyper-v",
        "docker",
        "wsl",
        "podman",
        "vmware",
        "vmnet",
        "virtualbox",
        "bridge",
    ]
    .iter()
    .any(|needle| lowered.contains(needle))
}

pub(crate) fn is_usable_ipv4(ip: std::net::Ipv4Addr) -> bool {
    let octets = ip.octets();
    let a = octets[0];
    let b = octets[1];
    let c = octets[2];

    if ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_link_local()
        || ip.is_broadcast()
        || ip.is_multicast()
    {
        return false;
    }

    if a == 100 && (64..=127).contains(&b) {
        return false;
    }

    if a == 198 && (b == 18 || b == 19) {
        return false;
    }

    if (a, b, c) == (192, 0, 2) || (a, b, c) == (198, 51, 100) || (a, b, c) == (203, 0, 113) {
        return false;
    }

    true
}

pub(crate) fn get_lan_interfaces() -> Vec<(std::net::Ipv4Addr, Option<std::net::Ipv4Addr>)> {
    let mut interfaces = Vec::new();
    if let Ok(addrs) = if_addrs::get_if_addrs() {
        for iface in addrs {
            if iface.is_loopback() || iface.is_p2p || is_host_only_or_virtual_name(&iface.name) {
                continue;
            }

            if let if_addrs::IfAddr::V4(v4) = iface.addr {
                if is_usable_ipv4(v4.ip) {
                    interfaces.push((v4.ip, v4.broadcast));
                }
            }
        }
    }
    interfaces
}

pub fn start_udp_broadcast(ws_port: u16, pc_public_key: [u8; 32]) {
    tauri::async_runtime::spawn(async move {
        let pc_pub_b64 = BASE64_URL_SAFE_NO_PAD.encode(pc_public_key);
        use ring::digest::{digest, SHA256};
        let hash = digest(&SHA256, &pc_public_key);
        let hash_bytes = hash.as_ref();
        let fingerprint = format!(
            "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
            hash_bytes[0],
            hash_bytes[1],
            hash_bytes[2],
            hash_bytes[3],
            hash_bytes[4],
            hash_bytes[5],
            hash_bytes[6],
            hash_bytes[7]
        );

        let mut interval = tokio::time::interval(std::time::Duration::from_secs(2));
        let mut ticks = 0u64;
        let mut cached_hosts_str = String::new();
        let mut cached_device_name = String::new();

        loop {
            interval.tick().await;

            if ticks.is_multiple_of(5)
                || cached_hosts_str.is_empty()
                || cached_device_name.is_empty()
            {
                let settings = crate::AppSettings::load();
                cached_device_name = settings.device_name.clone();
                cached_hosts_str = crate::local_pairing_hosts(&settings).join(",");
            }
            ticks = ticks.wrapping_add(1);

            let settings = crate::AppSettings::load();
            let mcp_port = settings.advertised_mcp_http_port();

            let payload = serde_json::json!({
                "id": pc_pub_b64,
                "name": cached_device_name,
                "fingerprint": fingerprint,
                "hosts": cached_hosts_str,
                "port": ws_port,
                "mcp_port": mcp_port,
                "mcp_transport": "streamable-http",
                "mcp_path": "/mcp",
            });

            let payload_str = payload.to_string();
            let payload_bytes = payload_str.as_bytes();

            let interfaces = get_lan_interfaces();
            if interfaces.is_empty() {
                if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
                    if socket.set_broadcast(true).is_ok() {
                        if let Err(e) = socket.send_to(payload_bytes, "255.255.255.255:5354") {
                            tracing::debug!("Failed to send fallback UDP broadcast: {:?}", e);
                        }
                    }
                }
            } else {
                for (ip, broadcast_ip) in interfaces {
                    if let Ok(socket) = std::net::UdpSocket::bind(std::net::SocketAddr::new(
                        std::net::IpAddr::V4(ip),
                        0,
                    )) {
                        if socket.set_broadcast(true).is_ok() {
                            let _ = socket.send_to(payload_bytes, "255.255.255.255:5354");
                            if let Some(bcast) = broadcast_ip {
                                let _ = socket.send_to(
                                    payload_bytes,
                                    std::net::SocketAddr::new(std::net::IpAddr::V4(bcast), 5354),
                                );
                            }
                        }
                    }
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_usable_ipv4() {
        assert!(is_usable_ipv4(std::net::Ipv4Addr::new(192, 168, 1, 100)));
        assert!(is_usable_ipv4(std::net::Ipv4Addr::new(10, 0, 0, 1)));
        assert!(!is_usable_ipv4(std::net::Ipv4Addr::new(127, 0, 0, 1)));
        assert!(!is_usable_ipv4(std::net::Ipv4Addr::new(100, 64, 0, 1)));
        assert!(!is_usable_ipv4(std::net::Ipv4Addr::new(169, 254, 1, 1)));
    }
}
