use ed25519_dalek::SigningKey;
use keyring::Entry;
use base64::prelude::*;
use anyhow::{Result, Context};
use std::convert::TryInto;

pub struct KeystoreManager;

impl KeystoreManager {
    const SERVICE_NAME: &'static str = "CapturePort";
    const KEY_NAME: &'static str = "pc_private_key";

    // Loads or generates the Ed25519 signing key from OS Keyring
    pub fn get_or_create_keys() -> Result<( [u8; 32], [u8; 32] )> {
        let entry = Entry::new(Self::SERVICE_NAME, Self::KEY_NAME)
            .context("Failed to open platform OS keyring entry")?;

        match entry.get_password() {
            Ok(password) => {
                // Key exists, decode it from base64
                let priv_bytes = BASE64_STANDARD.decode(password.trim())
                    .context("Failed to decode base64 private key from keyring")?;
                
                if priv_bytes.len() != 32 {
                    return Err(anyhow::anyhow!("Invalid private key length stored in keyring: {} bytes", priv_bytes.len()));
                }

                let priv_array: [u8; 32] = priv_bytes.try_into().unwrap();
                let signing_key = SigningKey::from_bytes(&priv_array);
                let pub_array = signing_key.verifying_key().to_bytes();

                Ok((pub_array, priv_array))
            }
            Err(keyring::Error::NoEntry) => {
                // Key does not exist, generate a new one
                let mut csprng = rand::thread_rng();
                let signing_key = SigningKey::generate(&mut csprng);
                let priv_array = signing_key.to_bytes();
                let pub_array = signing_key.verifying_key().to_bytes();

                // Save to OS Keyring
                let base64_priv = BASE64_STANDARD.encode(priv_array);
                entry.set_password(&base64_priv)
                    .context("Failed to save private key to OS keyring")?;

                Ok((pub_array, priv_array))
            }
            Err(err) => {
                Err(anyhow::anyhow!("Keyring error: {:?}", err))
            }
        }
    }
}
