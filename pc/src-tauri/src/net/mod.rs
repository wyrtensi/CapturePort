pub(crate) const VPN_NAME_NEEDLES: &[&str] = &[
    "tun",
    "tap",
    "vpn",
    "wg",
    "tailscale",
    "zerotier",
    "ppp",
    "utun",
    "nordlynx",
    "warp",
    "secure",
];

/// Checks if the active default route goes through a VPN or virtual tunnel interface.
pub(crate) fn is_vpn_default_route() -> bool {
    let addrs = match if_addrs::get_if_addrs() {
        Ok(v) => v,
        Err(_) => return false,
    };
    addrs.iter().any(|iface| {
        if iface.is_loopback() {
            return false;
        }
        let name = iface.name.to_ascii_lowercase();
        let looks_like_vpn =
            iface.is_p2p || VPN_NAME_NEEDLES.iter().any(|needle| name.contains(needle));

        looks_like_vpn && is_default_route_for(&iface.name, iface.index)
    })
}

#[cfg(target_os = "windows")]
fn is_default_route_for(_ifname: &str, ifindex: Option<u32>) -> bool {
    if let Some(target_idx) = ifindex {
        use windows_sys::Win32::NetworkManagement::IpHelper::GetBestInterface;
        let dest_addr: u32 = 0x08080808; // 8.8.8.8
        let mut best_if_index: u32 = 0;
        let res = unsafe { GetBestInterface(dest_addr, &mut best_if_index) };
        if res == 0 {
            return target_idx == best_if_index;
        }
    }
    false
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn is_default_route_for(ifname: &str, _ifindex: Option<u32>) -> bool {
    if let Ok(content) = std::fs::read_to_string("/proc/net/route") {
        for line in content.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() > 3 {
                let iface = parts[0];
                let dest = parts[1];
                // Destination "00000000" represents the default route (0.0.0.0).
                if dest == "00000000" && iface == ifname {
                    return true;
                }
            }
        }
    }
    false
}

#[cfg(target_os = "macos")]
fn is_default_route_for(ifname: &str, _ifindex: Option<u32>) -> bool {
    let out = std::process::Command::new("route")
        .args(["-n", "get", "default"])
        .output()
        .ok();
    if let Some(o) = out {
        let s = String::from_utf8_lossy(&o.stdout);
        for line in s.lines() {
            let line_trimmed = line.trim();
            if line_trimmed.starts_with("interface:") {
                let parts: Vec<&str> = line_trimmed.split_whitespace().collect();
                if parts.len() > 1 && parts[1] == ifname {
                    return true;
                }
            }
        }
    }
    false
}

#[cfg(not(any(
    target_os = "windows",
    target_os = "linux",
    target_os = "android",
    target_os = "macos"
)))]
fn is_default_route_for(_ifname: &str, _ifindex: Option<u32>) -> bool {
    false
}

pub(crate) fn get_vpn_interfaces() -> Vec<(String, u32)> {
    let mut vpns = Vec::new();
    if let Ok(addrs) = if_addrs::get_if_addrs() {
        for iface in addrs {
            if iface.is_loopback() {
                continue;
            }
            let name = iface.name.to_ascii_lowercase();
            let looks_like_vpn =
                iface.is_p2p || VPN_NAME_NEEDLES.iter().any(|needle| name.contains(needle));
            if looks_like_vpn {
                if let Some(idx) = iface.index {
                    vpns.push((iface.name.clone(), idx));
                }
            }
        }
    }
    vpns.sort_by_key(|a| a.1);
    vpns.dedup_by(|a, b| a.1 == b.1);
    vpns
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

pub(crate) fn get_physical_lan_interfaces() -> Vec<(std::net::Ipv4Addr, Option<std::net::Ipv4Addr>)>
{
    let mut interfaces = Vec::new();
    if let Ok(addrs) = if_addrs::get_if_addrs() {
        for iface in addrs {
            if iface.is_loopback() {
                continue;
            }
            let name = iface.name.to_ascii_lowercase();
            let is_vpn_or_virtual = iface.is_p2p
                || VPN_NAME_NEEDLES.iter().any(|needle| name.contains(needle))
                || [
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
                .any(|needle| name.contains(needle));

            if is_vpn_or_virtual {
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

pub fn start_udp_broadcast(ws_port: u16, pc_public_key: [u8; 32], state: crate::state::AppState) {
    let weak_inner = std::sync::Arc::downgrade(&state.inner);
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

            let inner_arc = match weak_inner.upgrade() {
                Some(arc) => arc,
                None => {
                    tracing::info!("AppState dropped. Stopping UDP broadcast.");
                    break;
                }
            };

            if ticks.is_multiple_of(5)
                || cached_hosts_str.is_empty()
                || cached_device_name.is_empty()
            {
                let settings = crate::AppSettings::load();
                cached_device_name = settings.device_name;

                let tailscale_dns = {
                    let inner = inner_arc.lock().unwrap();
                    inner.tailscale_dns_name.clone()
                };
                let hosts = crate::pairing::qr::QrGenerator::get_pairing_hosts(tailscale_dns);
                cached_hosts_str = hosts.join(",");
            }
            ticks = ticks.wrapping_add(1);

            let payload = serde_json::json!({
                "id": pc_pub_b64,
                "name": cached_device_name,
                "fingerprint": fingerprint,
                "hosts": cached_hosts_str,
                "port": ws_port,
            });

            let payload_str = payload.to_string();
            let payload_bytes = payload_str.as_bytes();

            let physical_interfaces = get_physical_lan_interfaces();
            if physical_interfaces.is_empty() {
                // Fallback to binding to 0.0.0.0
                if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
                    if socket.set_broadcast(true).is_ok() {
                        if let Err(e) = socket.send_to(payload_bytes, "255.255.255.255:5354") {
                            tracing::debug!("Failed to send fallback UDP broadcast: {:?}", e);
                        }
                    }
                }
            } else {
                for (ip, broadcast_ip) in physical_interfaces {
                    if let Ok(socket) = std::net::UdpSocket::bind(std::net::SocketAddr::new(
                        std::net::IpAddr::V4(ip),
                        0,
                    )) {
                        if socket.set_broadcast(true).is_ok() {
                            // Send general broadcast
                            let _ = socket.send_to(payload_bytes, "255.255.255.255:5354");
                            // Also send to interface's subnet broadcast if available
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

use base64::prelude::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vpn_name_detection() {
        let test_cases = [
            ("tun0", true),
            ("wg0", true),
            ("tailscale0", true),
            ("utun3", true),
            ("ppp0", true),
            ("nordlynx", true),
            ("warp-interface", true),
            ("zerotier_one", true),
            ("tap-windows-adapter", true),
            ("eth0", false),
            ("wlan0", false),
            ("Wi-Fi", false),
            ("Ethernet 1", false),
        ];

        for (name, expected) in test_cases {
            let matches = VPN_NAME_NEEDLES
                .iter()
                .any(|needle| name.to_ascii_lowercase().contains(needle));
            assert_eq!(matches, expected, "Failed for name: {}", name);
        }
    }

    #[test]
    fn test_usable_ipv4() {
        assert!(is_usable_ipv4(std::net::Ipv4Addr::new(192, 168, 1, 100)));
        assert!(is_usable_ipv4(std::net::Ipv4Addr::new(10, 0, 0, 1)));
        assert!(!is_usable_ipv4(std::net::Ipv4Addr::new(127, 0, 0, 1)));
        assert!(!is_usable_ipv4(std::net::Ipv4Addr::new(100, 64, 0, 1))); // CGNAT
        assert!(!is_usable_ipv4(std::net::Ipv4Addr::new(169, 254, 1, 1))); // Link local
    }
}
