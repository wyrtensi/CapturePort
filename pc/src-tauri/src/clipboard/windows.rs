use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::path::Path;
use anyhow::{Result, Context};
use crate::clipboard::ClipboardSink;

pub struct WindowsSink;

impl WindowsSink {
    pub fn new() -> Self {
        Self
    }
}

impl Default for WindowsSink {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(target_os = "windows")]
mod win32 {
    use super::*;
    use windows_sys::Win32::System::DataExchange::{
        OpenClipboard, EmptyClipboard, SetClipboardData, CloseClipboard,
    };
    use windows_sys::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GHND};
    use windows_sys::Win32::System::Ole::CF_HDROP;
    use windows_sys::Win32::Foundation::{POINT, GlobalFree};


    #[repr(C)]
    struct Dropfiles {
        p_files: u32,
        pt: POINT,
        f_nc: i32,
        f_wide: i32,
    }

    pub unsafe fn copy_files_to_clipboard(paths: &[&Path]) -> Result<()> {
        let mut buffer: Vec<u16> = Vec::new();
        for path in paths {
            let abs_path = std::fs::canonicalize(path)?;
            let file_path_str = abs_path.to_string_lossy().to_string();
            
            // Clean extended path prefix (e.g. \\?\ or \\?\UNC\)
            let clean_path = if let Some(stripped) = file_path_str.strip_prefix(r#"\\?\UNC\"#) {
                format!(r#"\\{}"#, stripped)
            } else if let Some(stripped) = file_path_str.strip_prefix(r#"\\?\"#) {
                stripped.to_string()
            } else {
                file_path_str
            };
            
            let os_str = OsStr::new(&clean_path);
            buffer.extend(os_str.encode_wide());
            buffer.push(0); // null separator
        }
        buffer.push(0); // double-null terminator

        let dropfiles_size = std::mem::size_of::<Dropfiles>();
        let total_size = dropfiles_size + (buffer.len() * 2);

        let h_global = GlobalAlloc(GHND, total_size);
        if h_global.is_null() {
            return Err(anyhow::anyhow!("GlobalAlloc failed"));
        }

        let p_mem = GlobalLock(h_global);
        if p_mem.is_null() {
            GlobalFree(h_global);
            return Err(anyhow::anyhow!("GlobalLock failed"));
        }

        let dropfiles = Dropfiles {
            p_files: dropfiles_size as u32,
            pt: POINT { x: 0, y: 0 },
            f_nc: 0,
            f_wide: 1, // wide chars
        };

        std::ptr::copy_nonoverlapping(
            &dropfiles as *const Dropfiles as *const u8,
            p_mem as *mut u8,
            dropfiles_size,
        );

        std::ptr::copy_nonoverlapping(
            buffer.as_ptr() as *const u8,
            p_mem.cast::<u8>().add(dropfiles_size),
            buffer.len() * 2,
        );

        GlobalUnlock(h_global);

        if OpenClipboard(std::ptr::null_mut()) == 0 {
            GlobalFree(h_global);
            return Err(anyhow::anyhow!("OpenClipboard failed"));
        }

        EmptyClipboard();

        let handle = SetClipboardData(CF_HDROP.into(), h_global);
        if handle.is_null() {
            CloseClipboard();
            GlobalFree(h_global);
            return Err(anyhow::anyhow!("SetClipboardData failed"));
        }

        CloseClipboard();
        Ok(())
    }
}

impl ClipboardSink for WindowsSink {
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
        tracing::info!("Successfully copied image to Windows clipboard via arboard");
        Ok(())
    }

    fn put_file(&self, path: &Path) -> Result<()> {
        #[cfg(target_os = "windows")]
        unsafe {
            win32::copy_files_to_clipboard(&[path])
        }
        #[cfg(not(target_os = "windows"))]
        {
            let _ = path;
            Err(anyhow::anyhow!("Not supported on non-Windows platform"))
        }
    }
}
