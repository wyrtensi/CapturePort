use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::TrayIconBuilder,
    AppHandle, Emitter, Manager, Runtime, WebviewUrl, WebviewWindowBuilder,
};
use anyhow::Result;

pub struct TrayManager;

impl TrayManager {
    // Keep desktop navigation inside the main window instead of opening pseudo-pages as separate windows.
    pub fn open_main_window<R: Runtime>(app: &AppHandle<R>, view: &str) {
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.show();
            let _ = window.set_skip_taskbar(false);
            let _ = window.set_focus();
            let _ = window.emit("navigate", view.to_string());
        } else {
            let window_url = match view {
                "pairing" | "settings" => format!("/?view={view}"),
                _ => "/".to_string(),
            };

            #[allow(unused_mut)]
            let mut builder = WebviewWindowBuilder::new(app, "main", WebviewUrl::App(window_url.into()))
                .title("CapturePort")
                .inner_size(980.0, 720.0)
                .resizable(true)
                .decorations(true);

            // On macOS, add elegant titlebar configuration if needed
            #[cfg(target_os = "macos")]
            {
                builder = builder.title_bar_style(tauri::TitleBarStyle::Transparent);
            }

            match builder.build() {
                Ok(window) => {
                    let _ = window.show();
                    let _ = window.set_skip_taskbar(false);
                    let _ = window.set_focus();
                    let _ = window.emit("navigate", view.to_string());
                }
                Err(e) => {
                    tracing::error!("Failed to build main window: {:?}", e);
                }
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
        let open_dashboard = MenuItem::with_id(app, "open_dashboard", "Open CapturePort", true, None::<&str>)?;
        let pair = MenuItem::with_id(app, "pair", "Pair new device...", true, None::<&str>)?;
        let open_folder = MenuItem::with_id(app, "open_folder", "Open received folder", true, None::<&str>)?;
        let settings = MenuItem::with_id(app, "settings", "Settings...", true, None::<&str>)?;
        let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;

        let menu = Menu::with_items(
            app,
            &[
                &open_dashboard,
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
                "open_dashboard" => {
                    Self::open_main_window(app, "history");
                }
                "pair" => {
                    Self::open_main_window(app, "pairing");
                }
                "open_folder" => {
                    Self::open_pictures_folder();
                }
                "settings" => {
                    Self::open_main_window(app, "settings");
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
