use std::path::Path;
use anyhow::{Result, Context};
use crate::clipboard::ClipboardSink;

pub struct MacosSink;

impl MacosSink {
    pub fn new() -> Self {
        Self
    }
}

impl ClipboardSink for MacosSink {
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
            
        tracing::info!("Successfully copied image to macOS clipboard via arboard");
        Ok(())
    }

    fn put_file(&self, path: &Path) -> Result<()> {
        let abs_path = std::fs::canonicalize(path)
            .context("Failed to resolve absolute path of target file")?;
            
        let path_str = abs_path.to_string_lossy().to_string();
        
        // Escape backslashes and double quotes to prevent AppleScript command injection
        let escaped_path = path_str.replace('\\', "\\\\").replace('"', "\\\"");
        let script = format!("set the clipboard to (POSIX file \"{}\")", escaped_path);
        
        let output = std::process::Command::new("osascript")
            .arg("-e")
            .arg(&script)
            .output()
            .context("Failed to execute osascript command")?;
            
        if output.status.success() {
            tracing::info!("Successfully copied file to macOS clipboard via AppleScript: {:?}", path);
            Ok(())
        } else {
            let err_msg = String::from_utf8_lossy(&output.stderr).to_string();
            Err(anyhow::anyhow!("AppleScript error setting clipboard: {}", err_msg))
        }
    }
}
