<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";

  // Svelte 5 Reactive States
  let windowLabel = $state("main");
  let pairingQr = $state("");
  let pairingFingerprint = $state("");
  let pairingStatus = $state("Waiting for scanner...");
  let mediaHistory = $state<any[]>([]);
  let settings = $state({
    deviceName: "PC-Machine",
    port: 7878,
    mcpEnabled: true,
    autoStart: false
  });

  onMount(async () => {
    // 1. Determine active window view from Tauri label
    try {
      const { getCurrentWindow } = await import("@tauri-apps/api/window");
      windowLabel = getCurrentWindow().label;
    } catch (e) {
      windowLabel = "main";
    }

    // 2. Load context based on view
    if (windowLabel === "pairing") {
      try {
        const info: any = await invoke("get_pairing_info");
        pairingQr = info.qr_svg;
        pairingFingerprint = info.fingerprint;
      } catch (e) {
        pairingStatus = "Failed to load QR code";
      }

      // Listen for socket pairing progression states
      listen("pairing-status", (event: any) => {
        pairingStatus = event.payload;
      });
    } else if (windowLabel === "history" || windowLabel === "main") {
      try {
        mediaHistory = await invoke("get_media_history");
      } catch (e) {}

      // Listen to new media items pushed from the socket
      listen("media-received", (event: any) => {
        mediaHistory = [event.payload, ...mediaHistory];
      });
    }

    if (windowLabel === "settings" || windowLabel === "main") {
      try {
        settings = await invoke("get_settings");
      } catch (e) {}
    }
  });

  async function saveSettings() {
    try {
      await invoke("save_settings", { new_settings: settings });
      alert("Settings saved successfully.");
    } catch (e) {
      alert("Failed to save settings.");
    }
  }

  async function openMediaFile(path: string) {
    try {
      await invoke("open_media_file", { path });
    } catch (e) {}
  }
</script>

<main class="app-container theme-dark">
  {#if windowLabel === "pairing"}
    <!-- PAIRING VIEW -->
    <div class="panel pairing-panel animate-fade">
      <header class="panel-header">
        <h2>📱 Pair New Device</h2>
        <p>Scan this QR code from the CapturePort app on your phone</p>
      </header>

      <div class="qr-container">
        {#if pairingQr}
          <div class="qr-graphic">
            <!-- Render the raw QR SVG vector code directly -->
            {@html pairingQr.replace("data:image/svg+xml;utf8,", "")}
          </div>
        {:else}
          <div class="qr-placeholder spinner"></div>
        {/if}
      </div>

      {#if pairingFingerprint}
        <div class="fingerprint-box">
          <span class="label">FINGERPRINT</span>
          <code class="fingerprint">{pairingFingerprint}</code>
        </div>
      {/if}

      <footer class="pairing-footer">
        <div class="status-indicator">
          <span class="pulse-dot"></span>
          <span class="status-text">{pairingStatus}</span>
        </div>
      </footer>
    </div>

  {:else}
    <!-- DEFAULT DASHBOARD (HISTORY & SETTINGS) -->
    <div class="dashboard animate-fade">
      <nav class="sidebar">
        <div class="logo-area">
          <span class="logo-icon">⚡</span>
          <h3>CapturePort</h3>
        </div>
        <div class="nav-links">
          <button class="nav-btn" class:active={windowLabel === "history" || windowLabel === "main"} onclick={() => windowLabel = "history"}>
            📂 Activity log
          </button>
          <button class="nav-btn" class:active={windowLabel === "settings"} onclick={() => windowLabel = "settings"}>
            ⚙ Settings
          </button>
        </div>
      </nav>

      <section class="content-area">
        {#if windowLabel === "history" || windowLabel === "main"}
          <div class="content-header">
            <h2>Activity Log</h2>
            <p>Recently received media files captured on your devices</p>
          </div>

          {#if mediaHistory.length === 0}
            <div class="empty-state">
              <span class="empty-icon">📷</span>
              <p>No media files received yet</p>
              <p class="subtitle">Photos and videos will appear here automatically</p>
            </div>
          {:else}
            <div class="media-grid">
              {#each mediaHistory as item}
                <button class="media-card" onclick={() => openMediaFile(item.path)}>
                  <div class="media-preview-container">
                    {#if item.kind === 'photo'}
                      <img src="data:image/jpeg;base64,{item.base64_data}" class="media-preview" alt="Captured view" />
                    {:else}
                      <div class="video-preview-fallback">
                        <span class="play-icon">▶</span>
                        <span class="video-badge">VIDEO</span>
                      </div>
                    {/if}
                  </div>
                  <div class="media-info">
                    <span class="media-kind" class:kind-video={item.kind === 'video'}>
                      {item.kind.toUpperCase()}
                    </span>
                    <span class="media-time">
                      {new Date(item.timestamp).toLocaleTimeString()}
                    </span>
                  </div>
                </button>
              {/each}
            </div>
          {/if}

        {:else if windowLabel === "settings"}
          <div class="content-header">
            <h2>Settings</h2>
            <p>Configure your local network receiver parameters</p>
          </div>

          <form class="settings-form" onsubmit={(e) => { e.preventDefault(); saveSettings(); }}>
            <div class="form-group">
              <label for="device-name">Device Name</label>
              <input id="device-name" type="text" bind:value={settings.deviceName} />
            </div>

            <div class="form-group">
              <label for="port">WebSocket Port</label>
              <input id="port" type="number" bind:value={settings.port} />
            </div>

            <div class="form-group checkbox-group">
              <input id="mcp-enabled" type="checkbox" bind:checked={settings.mcpEnabled} />
              <label for="mcp-enabled">Enable MCP Camera Server for AI agents</label>
            </div>

            <div class="form-group checkbox-group">
              <input id="auto-start" type="checkbox" bind:checked={settings.autoStart} />
              <label for="auto-start">Launch automatically on system startup</label>
            </div>

            <button type="submit" class="submit-btn">Save Configurations</button>
          </form>
        {/if}
      </section>
    </div>
  {/if}
</main>

<style>
  /* Custom modern dark theme colors and CSS reset */
  :global(body) {
    margin: 0;
    padding: 0;
    font-family: 'Segoe UI', -apple-system, Roboto, Helvetica, sans-serif;
    background-color: #101114;
    color: #E3E3E6;
    overflow: hidden;
  }

  .app-container {
    width: 100vw;
    height: 100vh;
    display: flex;
    background-color: #101114;
  }

  /* Vector animations and typography */
  .animate-fade {
    animation: fadeIn 0.40s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(6px); }
    to { opacity: 1; transform: translateY(0); }
  }

  /* Pairing Panel Layout */
  .pairing-panel {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    padding: 24px;
    box-sizing: border-box;
  }

  .panel-header {
    text-align: center;
    margin-bottom: 20px;
  }

  .panel-header h2 {
    margin: 0 0 6px 0;
    font-weight: 600;
    color: #A4B4FF;
  }

  .panel-header p {
    margin: 0;
    font-size: 14px;
    color: #8C8E96;
  }

  .qr-container {
    background-color: #FFFFFF;
    padding: 16px;
    border-radius: 16px;
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.4);
    margin-bottom: 20px;
    width: 260px;
    height: 260px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .qr-graphic {
    width: 100%;
    height: 100%;
  }

  .qr-placeholder {
    width: 80px;
    height: 80px;
    border: 4px solid #A4B4FF30;
    border-top: 4px solid #A4B4FF;
    border-radius: 50%;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    0% { transform: rotate(0deg); }
    100% { transform: rotate(360deg); }
  }

  .fingerprint-box {
    background-color: #1F2128;
    border: 1px solid #44464F;
    border-radius: 10px;
    padding: 10px 20px;
    text-align: center;
    margin-bottom: 24px;
  }

  .fingerprint-box .label {
    display: block;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 1.5px;
    color: #8C8E96;
    margin-bottom: 4px;
  }

  .fingerprint-box .fingerprint {
    font-family: monospace;
    font-size: 16px;
    color: #DEE0FF;
    font-weight: bold;
  }

  .status-indicator {
    display: flex;
    align-items: center;
    gap: 8px;
    background-color: #1A1C22;
    padding: 8px 16px;
    border-radius: 20px;
    border: 1px solid #2F3138;
  }

  .pulse-dot {
    width: 8px;
    height: 8px;
    background-color: #A4B4FF;
    border-radius: 50%;
    animation: pulse 1.6s infinite ease-in-out;
  }

  @keyframes pulse {
    0% { transform: scale(0.9); opacity: 0.5; }
    50% { transform: scale(1.2); opacity: 1; }
    100% { transform: scale(0.9); opacity: 0.5; }
  }

  .status-text {
    font-size: 12px;
    color: #C5C4DD;
  }

  /* Dashboard Core (Sidebar & Content) */
  .dashboard {
    display: flex;
    width: 100%;
    height: 100%;
  }

  .sidebar {
    width: 220px;
    background-color: #0B0C0E;
    border-right: 1px solid #1F2128;
    padding: 24px 16px;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
  }

  .logo-area {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 32px;
    padding-left: 8px;
  }

  .logo-icon {
    font-size: 20px;
    color: #A4B4FF;
  }

  .logo-area h3 {
    margin: 0;
    font-weight: 600;
    color: #E3E3E6;
  }

  .nav-links {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .nav-btn {
    background: none;
    border: none;
    outline: none;
    padding: 12px 16px;
    border-radius: 10px;
    color: #8C8E96;
    text-align: left;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s ease;
  }

  .nav-btn:hover {
    background-color: #1F212880;
    color: #E3E3E6;
  }

  .nav-btn.active {
    background-color: #1F2128;
    color: #A4B4FF;
    font-weight: 600;
  }

  .content-area {
    flex: 1;
    padding: 32px;
    overflow-y: auto;
    box-sizing: border-box;
  }

  .content-header {
    margin-bottom: 28px;
  }

  .content-header h2 {
    margin: 0 0 6px 0;
    font-size: 24px;
    font-weight: 600;
    color: #E3E3E6;
  }

  .content-header p {
    margin: 0;
    font-size: 14px;
    color: #8C8E96;
  }

  /* Activity Log list */
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 60%;
    color: #8C8E96;
  }

  .empty-icon {
    font-size: 48px;
    margin-bottom: 16px;
  }

  .empty-state p {
    margin: 0 0 4px 0;
    font-weight: 500;
  }

  .empty-state .subtitle {
    font-size: 13px;
    color: #5C5D64;
  }

  .media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 16px;
  }

  .media-card {
    background-color: #1A1B1F;
    border: 1px solid #2F3138;
    border-radius: 12px;
    overflow: hidden;
    cursor: pointer;
    transition: all 0.2s ease-out;
    padding: 0;
    text-align: left;
    display: flex;
    flex-direction: column;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  }

  .media-card:hover {
    transform: scale(1.03);
    border-color: #A4B4FF50;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  }

  .media-card:active {
    transform: scale(0.98);
  }

  .media-preview-container {
    width: 100%;
    aspect-ratio: 1;
    background-color: #0A0A0C;
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }

  .media-preview {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .video-preview-fallback {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    color: #A4B4FF;
  }

  .play-icon {
    font-size: 24px;
  }

  .video-badge {
    font-size: 9px;
    font-weight: 600;
    background-color: #1F318B;
    color: #DEE0FF;
    padding: 2px 6px;
    border-radius: 4px;
    letter-spacing: 0.5px;
  }

  .media-info {
    padding: 10px 12px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid #2F3138;
    background-color: #121316;
  }

  .media-kind {
    font-size: 9px;
    font-weight: bold;
    color: #A4B4FF;
    letter-spacing: 0.5px;
  }

  .media-kind.kind-video {
    color: #DEE0FF;
  }

  .media-time {
    font-size: 11px;
    color: #8C8E96;
  }

  /* Settings Form Layout */
  .settings-form {
    max-width: 480px;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .form-group {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .form-group label {
    font-size: 13px;
    font-weight: 600;
    color: #C5C4DD;
  }

  .form-group input[type="text"],
  .form-group input[type="number"] {
    background-color: #18191E;
    border: 1px solid #2F3138;
    border-radius: 8px;
    padding: 10px 14px;
    color: #E3E3E6;
    font-size: 14px;
    outline: none;
    transition: border-color 0.2s;
  }

  .form-group input[type="text"]:focus,
  .form-group input[type="number"]:focus {
    border-color: #A4B4FF;
  }

  .checkbox-group {
    flex-direction: row;
    align-items: center;
    gap: 10px;
    margin-top: 4px;
    cursor: pointer;
  }

  .checkbox-group input[type="checkbox"] {
    width: 18px;
    height: 18px;
    accent-color: #A4B4FF;
    cursor: pointer;
  }

  .checkbox-group label {
    font-weight: 500;
    color: #C5C4DD;
    cursor: pointer;
    user-select: none;
  }

  .submit-btn {
    background-color: #3B5BFF;
    color: #FFFFFF;
    border: none;
    outline: none;
    padding: 12px 20px;
    border-radius: 10px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
    margin-top: 12px;
    box-shadow: 0 4px 12px rgba(59, 91, 255, 0.2);
  }

  .submit-btn:hover {
    background-color: #5C77FF;
  }

  .submit-btn:active {
    transform: scale(0.98);
  }
</style>
