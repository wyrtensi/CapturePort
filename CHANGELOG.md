# Changelog

All notable changes to this project will be documented in this file.

## [0.1.6] - 2026-06-02
### Fixed
- Android pairing now validates that the exported Ed25519 public key actually matches the private key in AndroidKeyStore, and regenerates stale incompatible entries that previously caused `Fingerprint mismatch` pairing failures.
- Android build system now forces using JDK 21 from Android Studio to avoid Kotlin compiler daemon crashes with JDK 25.
- Added detailed cryptographic tracing logs to the Windows WebSocket handler to simplify diagnostics during signature validation failures.

## [0.1.5] - 2026-06-02
### Fixed
- Android pairing QR payloads now advertise multiple candidate LAN addresses, and the phone retries them sequentially so host-only adapters such as `192.168.56.1` no longer block pairing on multi-OS setups.
- Android pairing camera flow now uses lifecycle-aware binding with `KEEP_ONLY_LATEST` analysis backpressure, which reduces scanner freezes and avoids the later `Not bound to a valid Camera` capture failure after returning home.
- Android release automation now uses Node 24-compatible GitHub Actions versions, removing the remaining deprecated Node 20 runtime warnings.

## [0.1.4] - 2026-06-02
### Fixed
- Pairing QR codes now prefer a real LAN IPv4 address instead of unusable virtual or VPN addresses such as `198.18.0.1`.
- Android pairing retries no longer trigger an invalid `port=0` WebSocket URL crash, and malformed QR payloads now fail with a controlled error state.
- Android QR scanning is throttled to a single in-flight analysis pass with a shared ML Kit scanner, which reduces the camera freeze seen shortly after entering pairing.
- The Android home screen no longer shows two competing pairing entry points when there are no paired receivers yet.

## [0.1.3] - 2026-06-02
### Fixed
- Android network security configuration now permits the app's arbitrary LAN `ws://` connections without using invalid CIDR entries, which also unblocks Android lint in CI.

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
