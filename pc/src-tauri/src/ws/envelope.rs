use serde::{Serialize, Deserialize};
use std::convert::TryInto;
use anyhow::{anyhow, Result};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ErrorPayload {
    pub code: i32,
    pub message: String,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct Envelope {
    pub v: u32,
    pub t: String, // "req" | "resp" | "notify"
    pub id: String, // ULID correlation
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorPayload>,
    pub ts: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub idem: Option<String>,
}

impl Envelope {
    pub fn new_request(id: String, method: String, params: serde_json::Value, idem: Option<String>) -> Self {
        Self {
            v: 1,
            t: "req".to_string(),
            id,
            method: Some(method),
            params: Some(params),
            result: None,
            error: None,
            ts: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            idem,
        }
    }

    pub fn new_response(id: String, result: serde_json::Value) -> Self {
        Self {
            v: 1,
            t: "resp".to_string(),
            id,
            method: None,
            params: None,
            result: Some(result),
            error: None,
            ts: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            idem: None,
        }
    }

    pub fn new_error(id: String, code: i32, message: String) -> Self {
        Self {
            v: 1,
            t: "resp".to_string(),
            id,
            method: None,
            params: None,
            result: None,
            error: Some(ErrorPayload { code, message }),
            ts: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            idem: None,
        }
    }

    pub fn new_notification(method: String, params: serde_json::Value) -> Self {
        Self {
            v: 1,
            t: "notify".to_string(),
            id: "".to_string(),
            method: Some(method),
            params: Some(params),
            result: None,
            error: None,
            ts: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            idem: None,
        }
    }
}

pub struct BinaryFrame {
    pub stream_id: u32,
    pub frame_seq: u32,
    pub flags: u32,
    pub total_size: u64,
    pub meta: serde_json::Value,
    pub payload: Vec<u8>,
}

impl BinaryFrame {
    pub const MAGIC: &'static [u8; 8] = b"PHRBIDG1";

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < 32 {
            return Err(anyhow!("Binary frame too short, less than 32 bytes"));
        }

        if &bytes[0..8] != Self::MAGIC {
            return Err(anyhow!("Invalid magic header in binary frame"));
        }

        let stream_id = u32::from_le_bytes(bytes[8..12].try_into()?);
        let frame_seq = u32::from_le_bytes(bytes[12..16].try_into()?);
        let flags = u32::from_le_bytes(bytes[16..20].try_into()?);
        let total_size = u64::from_le_bytes(bytes[20..28].try_into()?);
        let meta_size = u32::from_le_bytes(bytes[28..32].try_into()?) as usize;

        if bytes.len() < 32 + meta_size {
            return Err(anyhow!("Binary frame truncated, meta_size exceeds packet length"));
        }

        let meta = if meta_size > 0 {
            serde_json::from_slice(&bytes[32..32 + meta_size])?
        } else {
            serde_json::Value::Null
        };

        let payload = bytes[32 + meta_size..].to_vec();

        Ok(Self {
            stream_id,
            frame_seq,
            flags,
            total_size,
            meta,
            payload,
        })
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        let meta_bytes = if !self.meta.is_null() {
            serde_json::to_vec(&self.meta)?
        } else {
            Vec::new()
        };

        let mut bytes = Vec::with_capacity(32 + meta_bytes.len() + self.payload.len());
        bytes.extend_from_slice(Self::MAGIC);
        bytes.extend_from_slice(&self.stream_id.to_le_bytes());
        bytes.extend_from_slice(&self.frame_seq.to_le_bytes());
        bytes.extend_from_slice(&self.flags.to_le_bytes());
        bytes.extend_from_slice(&self.total_size.to_le_bytes());
        bytes.extend_from_slice(&(meta_bytes.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&meta_bytes);
        bytes.extend_from_slice(&self.payload);

        Ok(bytes)
    }
}
