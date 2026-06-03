use anyhow::Result;
use base64::prelude::*;
use ed25519_dalek::{Signer, SigningKey};
use if_addrs::{get_if_addrs, IfAddr};
use qrcodegen::{QrCode, QrCodeEcc};
use std::net::{IpAddr, Ipv4Addr, UdpSocket};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EndpointMode {
    LocalOnly,
    LocalThenInternet,
    InternetOnly,
}

impl std::str::FromStr for EndpointMode {
    type Err = std::convert::Infallible;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        Ok(match value {
            "internet-only" => Self::InternetOnly,
            "local-then-internet" => Self::LocalThenInternet,
            _ => Self::LocalOnly,
        })
    }
}

impl EndpointMode {

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::LocalOnly => "local-only",
            Self::LocalThenInternet => "local-then-internet",
            Self::InternetOnly => "internet-only",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairingEndpoints {
    pub local_hosts: Vec<String>,
    pub local_port: u16,
    pub internet_host: Option<String>,
    pub internet_port: Option<u16>,
    pub mode: EndpointMode,
}

impl PairingEndpoints {
    pub fn advertised_hosts(&self) -> Vec<String> {
        let mut hosts = Vec::new();
        let internet = self
            .internet_host
            .as_ref()
            .map(|host| host.trim())
            .filter(|host| !host.is_empty());

        match self.mode {
            EndpointMode::LocalOnly => hosts.extend(self.local_hosts.iter().cloned()),
            EndpointMode::LocalThenInternet => {
                hosts.extend(self.local_hosts.iter().cloned());
                if let Some(host) = internet {
                    hosts.push(host.to_string());
                }
            }
            EndpointMode::InternetOnly => {
                if let Some(host) = internet {
                    hosts.push(host.to_string());
                }
            }
        }

        if hosts.is_empty() {
            hosts.extend(self.local_hosts.iter().cloned());
        }
        if hosts.is_empty() {
            if let Some(host) = internet {
                hosts.push(host.to_string());
            }
        }

        let mut unique = Vec::new();
        for host in hosts {
            let trimmed = host.trim();
            if !trimmed.is_empty() && !unique.iter().any(|existing| existing == trimmed) {
                unique.push(trimmed.to_string());
            }
        }
        unique
    }

    pub fn primary_host(&self) -> String {
        self.advertised_hosts()
            .into_iter()
            .next()
            .unwrap_or_else(|| "127.0.0.1".to_string())
    }

    pub fn primary_port(&self) -> u16 {
        match self.mode {
            EndpointMode::InternetOnly => self.internet_port.unwrap_or(self.local_port),
            _ => self.local_port,
        }
    }

    pub fn internet_port_or_default(&self) -> u16 {
        self.internet_port.unwrap_or(self.local_port)
    }
}

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
                let ip = match &iface.addr {
                    IfAddr::V4(v4) => v4.ip,
                    _ => continue,
                };

                if let Some(priority) = Self::host_priority(ip, &iface) {
                    ranked_hosts.push((priority, ip.to_string()));
                }
            }
        }

        if let Some(routed_ip) = Self::legacy_routed_ip() {
            ranked_hosts.push((0, routed_ip));
        }

        ranked_hosts.sort_by(|(left_score, left_host), (right_score, right_host)| {
            left_score.cmp(right_score).then_with(|| {
                match (
                    left_host.parse::<Ipv4Addr>(),
                    right_host.parse::<Ipv4Addr>(),
                ) {
                    (Ok(a), Ok(b)) => a.cmp(&b),
                    (Ok(_), Err(_)) => std::cmp::Ordering::Less,
                    (Err(_), Ok(_)) => std::cmp::Ordering::Greater,
                    (Err(_), Err(_)) => left_host.cmp(right_host),
                }
            })
        });

        let mut hosts = Vec::new();
        for (_, host) in ranked_hosts {
            if !hosts.contains(&host) {
                hosts.push(host);
            }
        }

        hosts
    }

    // Ask the OS which source address it would use for a normal outbound route.
    fn legacy_routed_ip() -> Option<String> {
        let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
        socket.connect("8.8.8.8:80").ok()?;
        match socket.local_addr().ok()?.ip() {
            IpAddr::V4(ip) if crate::net::is_usable_ipv4(ip) => Some(ip.to_string()),
            _ => None,
        }
    }

    fn is_host_only_interface_name(name: &str) -> bool {
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

    fn host_priority(ip: Ipv4Addr, iface: &if_addrs::Interface) -> Option<u8> {
        Self::classify_pairing_host_candidate(ip, &iface.name, iface.is_loopback(), iface.is_p2p)
    }

    fn classify_pairing_host_candidate(
        ip: Ipv4Addr,
        iface_name: &str,
        is_loopback: bool,
        is_p2p: bool,
    ) -> Option<u8> {
        let is_host_only_or_virtual = is_loopback || Self::is_host_only_interface_name(iface_name);
        let looks_host_only = Self::looks_like_host_only_network(ip);

        if is_host_only_or_virtual || looks_host_only {
            return None;
        }

        if is_p2p || !crate::net::is_usable_ipv4(ip) {
            return None;
        }

        match ip.is_private() {
            true => Some(0),
            false => Some(2),
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
        endpoints: PairingEndpoints,
        device_name: String,
    ) -> Result<(String, String, String, [u8; 32])> {
        let hosts = endpoints.advertised_hosts();
        let host = endpoints.primary_host();
        let port = endpoints.primary_port();
        let hosts_param = if hosts.is_empty() {
            host.clone()
        } else {
            hosts.join(",")
        };
        let local_hosts_param = endpoints.local_hosts.join(",");
        let internet_host = endpoints.internet_host.clone().unwrap_or_default();
        let internet_port = endpoints.internet_port_or_default();
        let mode = endpoints.mode.as_str();

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
        let mut pair_url = format!(
            "captureport://pair?v=1&host={}&hosts={}&port={}&pk={}&name={}&os={}&nonce={}&sig={}",
            host, hosts_param, port, pk_b64, device_name, os, nonce_b64, sig_b64
        );
        pair_url.push_str(&format!(
            "&local_hosts={}&local_port={}&internet_host={}&internet_port={}&endpoint_mode={}",
            local_hosts_param, endpoints.local_port, internet_host, internet_port, mode
        ));

        // 5. Generate fingerprint: first 8 bytes of sha256(pk) formatted as hex split by colons
        use ring::digest::{digest, SHA256};
        let hash = digest(&SHA256, pubkey);
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

        // 6. Render URL to QR SVG
        let qr = QrCode::encode_text(&pair_url, QrCodeEcc::Medium)
            .map_err(|e| anyhow::anyhow!("QR encode error: {:?}", e))?;

        let svg = Self::to_svg_string(&qr, 4);
        let base64_svg = BASE64_STANDARD.encode(svg.as_bytes());
        let qr_svg_data = format!("data:image/svg+xml;base64,{}", base64_svg);

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
            parts.push(format!(
                r##"<path d="{}" fill="#101114"/>"##,
                path.trim_end()
            ));
        }
        parts.push("</svg>".to_string());

        parts.join("")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pairing_hosts_sorting() {
        let mut hosts = vec![
            (0, "192.168.1.100".to_string()),
            (0, "192.168.1.2".to_string()),
            (0, "10.0.0.1".to_string()),
            (0, "abc.example.com".to_string()),
            (0, "capture.example.net".to_string()),
        ];

        hosts.sort_by(|(left_score, left_host), (right_score, right_host)| {
            left_score.cmp(right_score).then_with(|| {
                match (
                    left_host.parse::<Ipv4Addr>(),
                    right_host.parse::<Ipv4Addr>(),
                ) {
                    (Ok(a), Ok(b)) => a.cmp(&b),
                    (Ok(_), Err(_)) => std::cmp::Ordering::Less,
                    (Err(_), Ok(_)) => std::cmp::Ordering::Greater,
                    (Err(_), Err(_)) => left_host.cmp(right_host),
                }
            })
        });

        let sorted_names: Vec<String> = hosts.into_iter().map(|(_, name)| name).collect();
        assert_eq!(
            sorted_names,
            vec![
                "10.0.0.1".to_string(),
                "192.168.1.2".to_string(),
                "192.168.1.100".to_string(),
                "abc.example.com".to_string(),
                "capture.example.net".to_string(),
            ]
        );
    }

    #[test]
    fn test_pairing_endpoint_mode_orders_local_before_internet() {
        let endpoints = PairingEndpoints {
            local_hosts: vec!["192.168.0.111".to_string()],
            local_port: 7878,
            internet_host: Some("capture.example.net".to_string()),
            internet_port: Some(9443),
            mode: EndpointMode::LocalThenInternet,
        };

        assert_eq!(
            endpoints.advertised_hosts(),
            vec![
                "192.168.0.111".to_string(),
                "capture.example.net".to_string()
            ]
        );
        assert_eq!(endpoints.primary_host(), "192.168.0.111");
        assert_eq!(endpoints.primary_port(), 7878);
    }

    #[test]
    fn test_pairing_endpoint_mode_can_advertise_internet_only() {
        let endpoints = PairingEndpoints {
            local_hosts: vec!["192.168.0.111".to_string()],
            local_port: 7878,
            internet_host: Some("capture.example.net".to_string()),
            internet_port: Some(9443),
            mode: EndpointMode::InternetOnly,
        };

        assert_eq!(
            endpoints.advertised_hosts(),
            vec!["capture.example.net".to_string()]
        );
        assert_eq!(endpoints.primary_host(), "capture.example.net");
        assert_eq!(endpoints.primary_port(), 9443);
    }

    #[test]
    fn test_pairing_host_policy_ignores_host_only_virtual_adapters() {
        assert_eq!(
            QrGenerator::classify_pairing_host_candidate(
                Ipv4Addr::new(192, 168, 1, 42),
                "Wi-Fi",
                false,
                false,
            ),
            Some(0)
        );
        assert_eq!(
            QrGenerator::classify_pairing_host_candidate(
                Ipv4Addr::new(172, 17, 0, 1),
                "DockerNAT",
                false,
                false,
            ),
            None
        );
    }
}
