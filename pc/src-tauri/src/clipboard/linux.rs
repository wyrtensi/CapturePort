use std::path::Path;
use anyhow::{Result, Context};
use crate::clipboard::ClipboardSink;

#[derive(Default)]
pub struct LinuxSink;

impl LinuxSink {
    pub fn new() -> Self {
        Self
    }
    
    // Checks if a CLI command is available on PATH
    fn command_exists(cmd: &str) -> bool {
        std::process::Command::new("which")
            .arg(cmd)
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
}

impl ClipboardSink for LinuxSink {
    fn put_image(&self, jpeg_bytes: &[u8]) -> Result<()> {
        let img = image::load_from_memory(jpeg_bytes)
            .context("Failed to decode JPEG bytes for clipboard writing")?;
        
        let rgba = img.to_rgba8();
        let (w, h) = rgba.dimensions();
        
        let mut ctx = arboard::Clipboard::new()
            .context("Failed to open system clipboard via arboard")?;
            
        let img_data = arboard::ImageData {
            width: w as usize,
            height: h as usize,
            bytes: std::borrow::Cow::Borrowed(&rgba),
        };
        
        ctx.set_image(img_data)
            .context("Failed to set image to system clipboard")?;
            
        tracing::info!("Successfully copied image to Linux clipboard via arboard");
        Ok(())
    }

    fn put_file(&self, path: &Path) -> Result<()> {
        let abs_path = std::fs::canonicalize(path)
            .context("Failed to resolve absolute path of target file")?;
            
        let file_uri = format!("file://{}", abs_path.to_string_lossy());
        
        if Self::command_exists("wl-copy") {
            // Wayland copy
            let mut child = std::process::Command::new("wl-copy")
                .arg("-t")
                .arg("text/uri-list")
                .stdin(std::process::Stdio::piped())
                .spawn()
                .context("Failed to spawn wl-copy process")?;
                
            use std::io::Write;
            if let Some(mut stdin) = child.stdin.take() {
                stdin.write_all(file_uri.as_bytes())?;
            }
            
            let status = child.wait()?;
            if status.success() {
                tracing::info!("Successfully copied file to Wayland clipboard: {:?}", path);
                return Ok(());
            }
        }
        
        if Self::command_exists("xclip") {
            // X11 copy
            let mut child = std::process::Command::new("xclip")
                .arg("-selection")
                .arg("clipboard")
                .arg("-t")
                .arg("text/uri-list")
                .stdin(std::process::Stdio::piped())
                .spawn()
                .context("Failed to spawn xclip process")?;
                
            use std::io::Write;
            if let Some(mut stdin) = child.stdin.take() {
                stdin.write_all(file_uri.as_bytes())?;
            }
            
            let status = child.wait()?;
            if status.success() {
                tracing::info!("Successfully copied file to X11 clipboard: {:?}", path);
                return Ok(());
            }
        }
        
        Err(anyhow::anyhow!("Neither wl-copy nor xclip is available on PATH. Unable to copy file reference to clipboard."))
    }
}
