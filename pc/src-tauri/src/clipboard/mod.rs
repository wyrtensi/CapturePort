use std::path::Path;
use anyhow::Result;

pub trait ClipboardSink: Send + Sync {
    // Decodes JPEG bytes internally and copies RGBA to system clipboard
    fn put_image(&self, jpeg_bytes: &[u8]) -> Result<()>;
    // Copies raw files (e.g. video files) to system clipboard
    fn put_file(&self, path: &Path) -> Result<()>;
}

// Platform dispatch selection
#[cfg(target_os = "windows")]
pub mod windows;
#[cfg(target_os = "windows")]
pub type PlatformSink = windows::WindowsSink;

#[cfg(target_os = "macos")]
pub mod macos;
#[cfg(target_os = "macos")]
pub type PlatformSink = macos::MacosSink;

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
pub mod linux;
#[cfg(not(any(target_os = "windows", target_os = "macos")))]
pub type PlatformSink = linux::LinuxSink;

// Helper function to create active platform clipboard sink
pub fn get_platform_sink() -> PlatformSink {
    PlatformSink::new()
}
