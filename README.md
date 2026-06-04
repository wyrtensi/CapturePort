# CapturePort

[![Android Build](https://github.com/wyrtensi/CapturePort/actions/workflows/android-build.yml/badge.svg)](https://github.com/wyrtensi/CapturePort/actions/workflows/android-build.yml)
[![PC Build](https://github.com/wyrtensi/CapturePort/actions/workflows/pc-build.yml/badge.svg)](https://github.com/wyrtensi/CapturePort/actions/workflows/pc-build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

CapturePort is a secure, cross-platform media and capture bridge linking Android devices to your desktop environment (Windows, macOS, Linux). It includes a built-in **Model Context Protocol (MCP)** server, enabling real-time visual streams and clipboard synchronization directly inside agent-driven local IDEs like **Cursor** and assistants like **Claude Desktop**.

---

### Architecture & Communication Flow

CapturePort operates on a decoupled client-server model over local network WebSocket connections, with security established via out-of-band mutual cryptographic authentication.

### System Topology

The following diagram outlines the relationship between the Compose-based Android application, the local Tauri 2 desktop receiver (which directly handles stdio JSON-RPC for MCP), the axum server layer, and the host AI environment:

```mermaid
graph TD
    subgraph Android["Android Client (Kotlin + Compose)"]
        UI[Jetpack Compose UI] --> CamX[CameraX Controller]
        UI --> NSD[mDNS Service Discovery]
        UI --> UDP[UDP Discovery Listener]
        CamX --> WSClient[OkHttp WS Client]
        KM[Android Keystore KeyManager] --> WSClient
    end

    subgraph PC["PC Tauri Environment (Rust + Svelte)"]
        Tray[System Tray / Svelte UI] --> App[Tauri 2 Core]
        App --> Axum[Axum HTTP & WS Server]
        Axum --> WSHandler[WebSocket Handler]
        App --> UDPBeacon[UDP Broadcast Emitter]
    end

    subgraph Agent["Host AI Environment (MCP stdio mode)"]
        Claude[Claude Desktop / Cursor] -->|Stdio JSON-RPC| App
    end

    WSClient <==>|Local WebSocket (ws://)| WSHandler
    NSD -.->|mDNS Discovery| Axum
    UDPBeacon -.->|UDP Broadcasts (Port 5354)| UDP
```

### Cryptographic Mutual Pairing Flow

To ensure complete out-of-band trust validation, CapturePort relies on a **two-way mutual cryptographic verification** mechanism:

```mermaid
sequenceDiagram
    autonumber
    participant PC as PC Receiver (Tauri / Axum)
    participant And as Android Client (Compose)

    PC->>PC: Generate Ed25519 Keypair & Active Nonce
    PC->>PC: Render QR Code (PC PublicKey, Nonce, Hosts, Port)
    And->>And: Scan QR Code & Retrieve PC details
    And->>And: Retrieve Android Ed25519 Keypair
    And->>PC: WebSocket Connect & Send "hello" (PC Nonce signed by PC Key)
    PC->>PC: Verify Nonce and Signature
    PC->>And: Send "challenge" (Random Nonce PC)
    And->>And: Sign Nonce PC with Android Private Key
    And->>PC: Send Challenge Response (Signature)
    PC->>PC: Verify Android Signature with Android PublicKey & Calculate Fingerprint
    PC->>And: Send "verified" status with fingerprint & Device ID
    And->>And: Display Fingerprint verification dialog to User
    Note over And: User clicks "Confirm" in Android App
    And->>PC: Send "pair_confirm" Request
    PC->>PC: Save Android credentials to disk
    PC->>And: Send "paired" response with access Token
    And->>And: Save PC credentials to DataStore
```

---

## Security Hardening

CapturePort is engineered from the ground up for strict local privacy:

- **Local Loopback Boundary**: The MCP server binds strictly to `127.0.0.1`. This isolates the model interface, preventing malicious execution or port scanning from external network nodes.
- **Path Traversal & RCE Defeated**: All inbound client parameters (such as `request_id`) undergo strict alphanumeric validation using `^[a-zA-Z0-9_\-]+$` matching. Directory traversal sequences (`..`, `/`, `\`) or shell injection operators are rejected, and violations trigger instant connection teardown.
- **Hardware-Backed Keys**: Jetpack Compose client key material is generated inside the **Android Keystore System** using `KeyProperties.DIGEST_NONE`. The private key remains secured in the hardware-isolated Trusted Execution Environment (TEE) or StrongBox, ensuring it cannot be extracted by malicious root entities.
- **Network Security Configuration**: Cleartext traffic is permitted globally via `network_security_config.xml` to enable connections to arbitrary LAN IPs over standard `ws://` WebSocket connections, since Android cannot dynamically restrict cleartext configurations to private CIDR ranges.
- **Escaped Command Interpolation**: OS clipboard commands (PowerShell for Windows, AppleScript/`osascript` for macOS) undergo character-escaping. Single quotes (`'`) are doubled in PowerShell, and double quotes (`"`) are fully escaped in AppleScript.

---

## Feature Guide

### Camera Capture Policies
- **Screen Only** ("Camera: screen only"): Captures photos or records video only while the Android app is visible on the screen.
- **Background** ("Camera: background"): *Note: Background capture is currently restricted by Android OS and CameraX lifecycle constraints.*

### Connection Route Modes
- **Local** ("Local only"): Forces the socket to only try LAN IP addresses.
- **Mixed** ("Through internet"): Sequentially tries all discovered local LAN IPs first, then falls back to the external hostname.
- **Internet** ("Internet only"): Bypasses LAN hosts entirely and directly connects to the configured external host and port.

### Receiver Controls
- **Snap Photo**: Shoots a photo using CameraX, downscales it to 1920px (long edge) with 80% JPEG quality, and sends it as a raw binary packet over the WebSocket stream, automatically placing it in the PC’s clipboard.
- **Record Video**: Records a H.264 video clip with audio, transfers it via a foreground service to the PC, and copies its file URL to the clipboard.
- **Gallery Upload**: Pushes selected photos/videos from the gallery directly to the PC clipboard.

---

## Model Context Protocol (MCP) Setup

CapturePort acts as an MCP server, empowering AI tools with vision (real-time camera feeds) and clipboard access on your machine.

### Claude Desktop Integration

Claude Desktop runs MCP servers locally over stdio pipes. Since the primary CapturePort process is built into the main desktop application, you can configure Claude Desktop to launch the CapturePort executable directly with the headless flag:

1. Locate or create your Claude Desktop configuration file:
   - **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
   - **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
2. Add the `captureport` entry to `mcpServers`:

```json
{
  "mcpServers": {
    "captureport": {
      "command": "C:\\Program Files\\CapturePort\\CapturePort.exe",
      "args": ["--mcp-stdio"],
      "env": {
        "RUST_LOG": "info"
      }
    }
  }
}
```

### Cursor Integration

Cursor connects to MCP servers using stdio pipes.

1. Open Cursor and navigate to **Settings** -> **Features** -> **MCP**.
2. Click **+ Add New MCP Server**.
3. Fill out the dialog with these parameters:
   - **Name**: `CapturePort`
   - **Type**: `command`
   - **Command**: `C:\Program Files\CapturePort\CapturePort.exe --mcp-stdio`
4. Click **Save**.

---

## Development Setup

Ensure you have installed Node.js (v20+), Rust stable, and the Android SDK.

### 1. Build and Run the PC Receiver

#### Install Linux System Dependencies
If you are compiling on Ubuntu/Debian:
```bash
sudo apt-get update
sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf build-essential curl wget file libssl-dev libgtk-3-dev libayatana-appindicator3-dev
```

#### Run Dev Server
```bash
cd pc
npm install
npm run tauri dev
```

### 2. Build and Run the Android Client

Make sure you have JDK 17 or JDK 21 configured (JDK 21 is recommended on newer systems to avoid Kotlin compiler daemon crashes with JDK 25) and run the following in your terminal:
```bash
cd android
./gradlew assembleDebug
```

---

## Automated Release Pipeline (CI/CD)

Every push or pull request on `main` initiates a dual-validation pipeline:

- **Android CI**: Validates Lint rules, runs JVM unit tests (`testDebugUnitTest`), and packages an unsigned production APK.
- **PC CI**: Executes Svelte type-checking (`npm run check`), Rust linting (`clippy`), and unit tests. On release tags (`v*`), a matrix build triggers to compile assets across three architectures:

| Platform | Package Format | Target Architecture | Generated Asset Name |
|---|---|---|---|
| **Windows** | MSI Installer | x86_64 | `CapturePort_{version}_x64_en-US.msi` |
| **macOS** | DMG Disk Image | Universal (Intel + Apple Silicon) | `CapturePort_{version}_universal.dmg` |
| **Linux** | Debian Package | x86_64 | `captureport_{version}_amd64.deb` |
| **Linux** | Portable AppImage | x86_64 | `captureport_{version}_amd64.AppImage` |
| **Android** | APK Package | ARM64 / Universal | `app-debug.apk` |

Both pipelines publish their corresponding artifacts directly to the same **GitHub Release Draft** upon tag triggers, providing a single consolidated distribution point. Note that the Android pipeline currently publishes the debug build APK.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
