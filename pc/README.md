<p align="center">
  <img src="src-tauri/icons/icon.png" width="128" height="128" alt="CapturePort Logo" />
</p>

<h1 align="center">CapturePort PC Receiver & MCP Server</h1>

<p align="center">
  <strong>Cross-platform desktop background service, receiver tray application, and Model Context Protocol (MCP) server.</strong>
</p>

CapturePort's PC Receiver is a cross-platform desktop application designed to receive media (photos and videos) captured by the paired CapturePort Android application over a local network. It acts as both a system tray background service and a Model Context Protocol (MCP) server for local AI agents.

## Features
- **Tray-based UI**: Minimize-to-tray application built with Svelte 5 and Tauri v2.
- **Mutual Cryptographic Pairing**: Secure pairing using Ed25519 signatures and QR code scanning.
- **Model Context Protocol (MCP)**: Native integration for tools (`list_devices`, `capture_photo`, `capture_screenshot`, `record_video`, `get_device_clipboard`, `set_device_clipboard`, `snap_frame`) over Stdio or Server-Sent Events (SSE).
- **Auto Clipboard Sync**: Photos are directly copied to the system clipboard as images. Videos are saved to disk and copied as file URIs.
- **Firewall Integration**: Settings-driven utility to configure OS-level firewall rules.

---

## Prerequisites

Ensure you have the following installed:
1. **Node.js** (v20 or higher) & **npm**
2. **Rust Stable Compiler** (Cargo 1.75+)
3. **Platform-Specific Requirements**:
   - **Windows**: WebView2 runtime.
   - **macOS**: Xcode Command Line Tools.
   - **Linux**: Build dependencies and WebKitGTK. Install via:
     ```bash
     sudo apt-get update
     sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf build-essential curl wget file libssl-dev libgtk-3-dev libayatana-appindicator3-dev
     ```

---

## Development & Setup Commands

Run all commands from the `/pc` directory.

### 1. Install Dependencies
```bash
npm install
```

### 2. Run the Desktop Application (Development Mode)
This launches the Svelte dev server and boots the Tauri window.
```bash
npm run tauri dev
```

### 3. Svelte Type-Checking & Linting
```bash
npm run check
```

### 4. Run Layout Constraints Verification
Validates that the UI elements comply with sizing rules and design constraints.
```bash
npm run test:layout
```

### 5. Build for Production
Compiles frontend assets and packages the native executable.
```bash
npm run tauri build
```

---

## CLI & Execution Modes

The compiled binary (`captureport`) can run in two modes:

1. **Desktop Tray GUI (Default)**:
   Launches the system tray icon, spawns the local Axum WebSocket listener on port `7878`, and starts the mDNS and UDP advertisers.
   ```bash
   captureport
   ```

2. **Headless Stdio MCP (CLI)**:
   Runs headless directly in the terminal, listening for standard JSON-RPC input over stdin/stdout (used by Claude Desktop).
   ```bash
   captureport --mcp-stdio
   ```

---

## Configuration & Architecture Notes

- **Settings Path**: Saved in JSON format at `~/AppData/Roaming/CapturePort/settings.json` (Windows), `~/Library/Application Support/CapturePort/settings.json` (macOS), or `~/.config/CapturePort/settings.json` (Linux).
- **Default Media Path**: Received photos and videos are stored in `~/Pictures/CapturePort`.
- **Default Network Ports**:
  - WebSocket Receiver/MCP Server: `7878` (customizable in Settings).
  - Svelte/Vite Dev Server: `1420` (strictPort enabled in `vite.config.js`).
