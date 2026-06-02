# Changelog

All notable changes to this project will be documented in this file.

## [0.1.2] - 2026-06-02
### Fixed
- Android startup no longer crashes on launch because the custom `CapturePortApp` application class is now declared in the manifest.
- Desktop dashboard replaced emoji UI markers with SVG icons and exposes Pairing directly in the sidebar.
- Desktop typechecking is clean again after adding Node typings and removing a stale Vite `@ts-expect-error`.

## [0.1.1] - 2026-06-02
### Fixed
- Desktop tray navigation now reuses the main window instead of opening unavailable pseudo-pages.
- Desktop startup keeps the app resident in the tray without the earlier startup panic.
- Android remote camera capture is limited to the visible receiver screen, and release automation now publishes an installable APK asset.

## [0.1.0] - 2026-06-02
### Added
- Initial architectural setup and project schemas.
- Sprints roadmap definition.
