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
        let mut private_fallback = None;
        let mut general_fallback = None;

        if let Ok(ifaces) = get_if_addrs() {
            for iface in ifaces {
                let ip = match iface.addr {
                    IfAddr::V4(v4) => v4.ip,
                    _ => continue,
                };

                if !Self::is_usable_ipv4(ip) {
                    continue;
                }

                let ip_string = ip.to_string();
                let is_virtual = Self::is_virtual_interface_name(&iface.name);

                if ip.is_private() && !is_virtual {
                    return Some(ip_string);
                }

                if ip.is_private() && private_fallback.is_none() {
                    private_fallback = Some(ip_string.clone());
                }

                if !is_virtual && general_fallback.is_none() {
                    general_fallback = Some(ip_string);
                }
            }
        }

        private_fallback
            .or(general_fallback)
            .or_else(Self::legacy_routed_ip)
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
            "vethernet",
            "hyper-v",
            "docker",
            "wsl",
            "vmware",
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

    // Creates the pairing URL and renders it as an SVG data URL
    pub fn generate_pairing_qr(
        pubkey: &[u8; 32],
        privkey: &[u8; 32],
        port: u16,
    ) -> Result<(String, String, String, [u8; 32])> {
        let host = Self::get_local_ip().unwrap_or_else(|| "127.0.0.1".to_string());
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
            "captureport://pair?v=1&host={}&port={}&pk={}&name={}&os={}&nonce={}&sig={}",
            host, port, pk_b64, hostname, os, nonce_b64, sig_b64
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
