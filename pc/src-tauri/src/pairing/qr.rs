use qrcodegen::{QrCode, QrCodeEcc};
use ed25519_dalek::{SigningKey, Signer};
use base64::prelude::*;
use anyhow::{Result, Context};
use std::net::UdpSocket;

pub struct QrGenerator;

impl QrGenerator {
    // Utility to get the PC's primary LAN IP (by simulating a UDP connect to a public IP)
    pub fn get_local_ip() -> Option<String> {
        let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
        socket.connect("8.8.8.8:80").ok()?;
        socket.local_addr().ok().map(|addr| addr.ip().to_string())
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
