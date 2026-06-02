use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::TrayIconBuilder,
    AppHandle, Manager, WebviewUrl, WebviewWindowBuilder, Runtime,
};
use anyhow::Result;

pub struct TrayManager;

impl TrayManager {
    // Helper to open or focus a specific Svelte dashboard window in Tauri 2
    pub fn open_window<R: Runtime>(app: &AppHandle<R>, label: &str, title: &str, width: f64, height: f64) {
        if let Some(window) = app.get_webview_window(label) {
            let _ = window.show();
            let _ = window.set_focus();
        } else {
            let mut builder = WebviewWindowBuilder::new(app, label, WebviewUrl::App("/".into()))
                .title(title)
                .inner_size(width, height)
                .resizable(true)
                .decorations(true);

            // On macOS, add elegant titlebar configuration if needed
            #[cfg(target_os = "macos")]
            {
                builder = builder.title_bar_style(tauri::TitleBarStyle::Transparent);
            }

            if let Err(e) = builder.build() {
                tracing::error!("Failed to build window {}: {:?}", label, e);
            }
        }
    }

    // Opens the platform-specific Pictures output folder
    pub fn open_pictures_folder() {
        let pictures_dir = dirs::picture_dir()
            .unwrap_or_else(|| std::path::PathBuf::from("."))
            .join("CapturePort");
        
        // Ensure directory exists before opening
        let _ = std::fs::create_dir_all(&pictures_dir);

        let path_str = pictures_dir.to_string_lossy().to_string();

        #[cfg(target_os = "windows")]
        {
            let _ = std::process::Command::new("explorer").arg(&path_str).spawn();
        }
        #[cfg(target_os = "macos")]
        {
            let _ = std::process::Command::new("open").arg(&path_str).spawn();
        }
        #[cfg(target_os = "linux")]
        {
            let _ = std::process::Command::new("xdg-open").arg(&path_str).spawn();
        }
    }

    // Creates the system-tray context menu and binds event listeners
    pub fn create_tray<R: Runtime>(app: &AppHandle<R>) -> Result<()> {
        let pair = MenuItem::with_id(app, "pair", "📱 Pair new device...", true, None::<&str>)?;
        let open_folder = MenuItem::with_id(app, "open_folder", "📂 Open received folder", true, None::<&str>)?;
        let settings = MenuItem::with_id(app, "settings", "⚙ Settings...", true, None::<&str>)?;
        let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;

        let menu = Menu::with_items(
            app,
            &[
                &pair,
                &open_folder,
                &settings,
                &PredefinedMenuItem::separator(app)?,
                &quit,
            ],
        )?;

        let _tray = TrayIconBuilder::new()
            .icon(app.default_window_icon().cloned().unwrap())
            .menu(&menu)
            .on_menu_event(|app, event| match event.id.as_ref() {
                "pair" => {
                    Self::open_window(app, "pairing", "CapturePort - Pairing", 400.0, 480.0);
                }
                "open_folder" => {
                    Self::open_pictures_folder();
                }
                "settings" => {
                    Self::open_window(app, "settings", "CapturePort - Settings", 540.0, 600.0);
                }
                "quit" => {
                    app.exit(0);
                }
                _ => {}
            })
            .build(app)?;

        Ok(())
    }
}
pub mod dirs {
    use std::path::PathBuf;
    pub fn picture_dir() -> Option<PathBuf> {
        #[allow(deprecated)]
        std::env::home_dir().map(|h| h.join("Pictures"))
    }
}
