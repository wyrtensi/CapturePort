# Changelog

All notable changes to this project will be documented in this file.

## [0.2.0] - 2026-06-03
### Added
- Explicit local and internet endpoint modes for pairing and receiver connection fallback.
- PC settings for advertised local host, external host/DDNS, external port, and best-effort firewall setup.
- Android receiver cards can store separate local and internet endpoints without losing manual aliases.
- Automatic device discovery via periodic UDP broadcast beacons on port 5354 (PC Emitter & Android `UdpDiscoveryListener`).
- Manual IP and port input dialog with Compose form validation and fallback connection updates.
- Robust singleton `OkHttpClient` sharing with literal IP resolution for direct local or internet receiver connections.

## [0.1.7] - 2026-06-02
### Fixed
- Android pairing no longer crashes with `Failed to generate a valid Ed25519 key pair in AndroidKeyStore`. The keypair self-test no longer rejects a freshly generated AndroidKeyStore Ed25519 entry when the default JCE provider (`KeyFactory`/`Signature` for `Ed25519`) is missing or misbehaving on certain Android 13/14 OEM builds. We now only do a structural check (algorithm + 32-byte raw public key) and let the PC verify the real challenge signature via `ed25519-dalek`.
- Android pairing now shows a friendly in-app error instead of crashing the process if the device identity key still cannot be produced.

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
- Pairing QR codes now prefer a real LAN IPv4 address instead of unusable virtual or benchmarking addresses such as `198.18.0.1`.
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
