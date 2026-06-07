use crate::clipboard::ClipboardSink;
use anyhow::{Context, Result};
use objc2::runtime::ProtocolObject;
use objc2_app_kit::NSPasteboard;
use objc2_foundation::{NSArray, NSString, NSURL};
use std::path::Path;

pub struct MacosSink;

impl MacosSink {
    pub fn new() -> Self {
        Self
    }
}

impl Default for MacosSink {
    fn default() -> Self {
        Self::new()
    }
}

impl ClipboardSink for MacosSink {
    fn put_image(&self, jpeg_bytes: &[u8]) -> Result<()> {
        let img = image::load_from_memory(jpeg_bytes).context("Failed to decode JPEG bytes")?;
        let rgba = img.to_rgba8();
        let (w, h) = rgba.dimensions();
        let mut ctx = arboard::Clipboard::new()?;
        let img_data = arboard::ImageData {
            width: w as usize,
            height: h as usize,
            bytes: std::borrow::Cow::Borrowed(&rgba),
        };
        ctx.set_image(img_data)?;
        Ok(())
    }

    fn put_file(&self, path: &Path) -> Result<()> {
        let abs_path = std::fs::canonicalize(path)?;
        let path_str = abs_path.to_string_lossy().to_string();

        objc2::rc::autoreleasepool(|_| {
            let pboard = NSPasteboard::generalPasteboard();
            pboard.clearContents();
            let ns_path = NSString::from_str(&path_str);
            let ns_url = NSURL::fileURLWithPath(&ns_path);

            // Cast the Retained<NSURL> to Retained<ProtocolObject<dyn NSPasteboardWriting>>
            let protocol_obj = ProtocolObject::from_retained(ns_url);
            let objects = NSArray::from_retained_slice(&[protocol_obj]);
            let success = pboard.writeObjects(&objects);
            if success {
                Ok(())
            } else {
                Err(anyhow::anyhow!("macOS NSPasteboard writeObjects failed"))
            }
        })
    }
}
