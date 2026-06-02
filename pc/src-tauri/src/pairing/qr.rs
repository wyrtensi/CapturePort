use qrcodegen::{QrCode, QrCodeEcc};
use ed25519_dalek::{SigningKey, Signer};
use base64::prelude::*;
use anyhow::Result;
use if_addrs::{get_if_addrs, IfAddr};
use std::net::{IpAddr, Ipv4Addr, UdpSocket};

pub struct QrGenerator;

impl QrGenerator {
    // Utility to get the PC's preferred LAN IP for mobile pairing.
    pub fn get_local_ip() -> Option<String> {
        Self::get_pairing_hosts().into_iter().next()
    }

    pub fn get_pairing_hosts() -> Vec<String> {
        let mut ranked_hosts: Vec<(u8, String)> = Vec::new();

        if let Ok(ifaces) = get_if_addrs() {
            for iface in ifaces {
                let ip = match iface.addr {
                    IfAddr::V4(v4) => v4.ip,
                    _ => continue,
                };

                if !Self::is_usable_ipv4(ip) {
                    continue;
                }

                ranked_hosts.push((Self::host_priority(ip, &iface.name), ip.to_string()));
            }
        }

        if let Some(routed_ip) = Self::legacy_routed_ip() {
            ranked_hosts.push((0, routed_ip));
        }

        ranked_hosts.sort_by(|(left_score, left_host), (right_score, right_host)| {
            left_score.cmp(right_score).then_with(|| left_host.cmp(right_host))
        });

        let mut hosts = Vec::new();
        for (_, host) in ranked_hosts {
            if !hosts.contains(&host) {
                hosts.push(host);
            }
        }

        hosts
    }

    fn legacy_routed_ip() -> Option<String> {
        let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
        socket.connect("8.8.8.8:80").ok()?;
        match socket.local_addr().ok()?.ip() {
            IpAddr::V4(ip) if Self::is_usable_ipv4(ip) => Some(ip.to_string()),
            _ => None,
        }
    }

    fn is_usable_ipv4(ip: Ipv4Addr) -> bool {
        let [a, b, c, _] = ip.octets();

        if ip.is_unspecified() || ip.is_loopback() || ip.is_link_local() || ip.is_broadcast() || ip.is_multicast() {
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

    fn is_virtual_interface_name(name: &str) -> bool {
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
            "vpn",
            "tun",
            "tap",
            "tailscale",
            "zerotier",
            "bridge",
        ]
        .iter()
        .any(|needle| lowered.contains(needle))
    }

    fn host_priority(ip: Ipv4Addr, interface_name: &str) -> u8 {
        let is_virtual = Self::is_virtual_interface_name(interface_name);
        let looks_host_only = Self::looks_like_host_only_network(ip);

        match (ip.is_private(), is_virtual || looks_host_only) {
            (true, false) => 0,
            (false, false) => 1,
            (true, true) => 2,
            (false, true) => 3,
        }
    }

    fn looks_like_host_only_network(ip: Ipv4Addr) -> bool {
        let [a, b, c, _] = ip.octets();

        matches!((a, b, c), (192, 168, 56) | (192, 168, 99) | (192, 168, 122))
    }

    // Creates the pairing URL and renders it as an SVG data URL
    pub fn generate_pairing_qr(
        pubkey: &[u8; 32],
        privkey: &[u8; 32],
        port: u16,
    ) -> Result<(String, String, String, [u8; 32])> {
        let hosts = Self::get_pairing_hosts();
        let host = hosts.first().cloned().unwrap_or_else(|| "127.0.0.1".to_string());
        let hosts_param = if hosts.is_empty() {
            host.clone()
        } else {
            hosts.join(",")
        };
        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "Desktop-Machine".to_string());

        let os = if cfg!(target_os = "windows") {
            "windows"
        } else if cfg!(target_os = "macos") {
            "macos"
        } else {
            "linux"
        };

        // 1. Generate single-use nonce
        let mut nonce = [0u8; 32];
        use rand::Rng;
        rand::thread_rng().fill(&mut nonce);

        // 2. Sign nonce using PC's private key
        let signing_key = SigningKey::from_bytes(privkey);
        let signature = signing_key.sign(&nonce);

        // 3. Base64Url encode fields
        let pk_b64 = BASE64_URL_SAFE_NO_PAD.encode(pubkey);
        let nonce_b64 = BASE64_URL_SAFE_NO_PAD.encode(nonce);
        let sig_b64 = BASE64_URL_SAFE_NO_PAD.encode(signature.to_bytes());

        // 4. Construct pairing URL
        let pair_url = format!(
            "captureport://pair?v=1&host={}&hosts={}&port={}&pk={}&name={}&os={}&nonce={}&sig={}",
            host, hosts_param, port, pk_b64, hostname, os, nonce_b64, sig_b64
        );

        // 5. Generate fingerprint: first 8 bytes of sha256(pk) formatted as hex split by colons
        use ring::digest::{digest, SHA256};
        let hash = digest(&SHA256, pubkey);
        let hash_bytes = hash.as_ref();
        let fingerprint = format!(
            "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
            hash_bytes[0], hash_bytes[1], hash_bytes[2], hash_bytes[3],
            hash_bytes[4], hash_bytes[5], hash_bytes[6], hash_bytes[7]
        );

        // 6. Render URL to QR SVG
        let qr = QrCode::encode_text(&pair_url, QrCodeEcc::Medium)
            .map_err(|e| anyhow::anyhow!("QR encode error: {:?}", e))?;
        
        let svg = Self::to_svg_string(&qr, 4);
        let qr_svg_data = format!("data:image/svg+xml;utf8,{}", svg);

        Ok((pair_url, fingerprint, qr_svg_data, nonce))
    }

    // Encodes QR Code modules into a compact vector SVG string
    fn to_svg_string(qr: &QrCode, border: i32) -> String {
        let size = qr.size();
        let total_size = size + border * 2;
        let mut parts = Vec::new();
        
        // Background
        parts.push(format!(
            r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {0} {0}" width="100%" height="100%"><rect width="100%" height="100%" fill="#FFFFFF"/>"##,
            total_size
        ));
        
        // QR Modules path builder
        let mut path = String::new();
        for y in 0..size {
            for x in 0..size {
                if qr.get_module(x, y) {
                    path.push_str(&format!("M{},{}h1v1h-1z ", x + border, y + border));
                }
            }
        }
        
        if !path.is_empty() {
            parts.push(format!(r##"<path d="{}" fill="#101114"/>"##, path.trim_end()));
        }
        parts.push("</svg>".to_string());
        
        parts.join("")
    }
}
