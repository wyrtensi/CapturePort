<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";

  type WindowView = "main" | "history" | "pairing" | "settings";

  function normalizeView(view: string | null | undefined): WindowView {
    if (view === "history" || view === "pairing" || view === "settings") {
      return view;
    }

    return "main";
  }

  // Svelte 5 Reactive States
  let windowLabel = $state<WindowView>("main");
  let pairingQr = $state("");
  let pairingFingerprint = $state("");
  let pairingStatus = $state("Waiting for scanner...");
  let mediaHistory = $state<any[]>([]);
  let pairedDevices = $state<any[]>([]);
  let settings = $state({
    deviceName: "PC-Machine",
    port: 7878,
    mcpEnabled: true,
    autoStart: false,
    closeToTray: false
  });

  async function loadPairedDevices() {
    try {
      pairedDevices = await invoke("get_paired_devices");
    } catch (e) {
      pairedDevices = [];
    }
  }

  async function refreshPairingInfo() {
    pairingStatus = "Refreshing QR code...";
    try {
      const info: any = await invoke("get_pairing_info");
      pairingQr = info.qr_svg;
      pairingFingerprint = info.fingerprint;
      pairingStatus = "Waiting for scanner...";
    } catch (e) {
      pairingQr = "";
      pairingFingerprint = "";
      pairingStatus = "Failed to load QR code";
    }
  }

  async function toggleMcp(id: string, exposed: boolean) {
    try {
      await invoke("set_device_mcp_exposure", { id, exposed });
    } catch (e) {
      alert("Failed to toggle MCP exposure");
      await loadPairedDevices();
    }
  }

  async function unpairDevice(id: string) {
    if (confirm("Are you sure you want to unpair this device?")) {
      try {
        await invoke("unpair_device", { id });
      } catch (e) {
        alert("Failed to unpair device");
      }
    }
  }

  async function regenerateIdentity() {
    if (confirm("Are you sure you want to regenerate the PC identity? This will invalidate all current pairings.")) {
      pairingStatus = "Regenerating identity...";
      try {
        const info: any = await invoke("regenerate_pc_identity");
        pairingQr = info.qr_svg;
        pairingFingerprint = info.fingerprint;
        pairingStatus = "Waiting for scanner...";
        await loadPairedDevices();
      } catch (e) {
        pairingStatus = "Failed to regenerate identity";
      }
    }
  }

  async function loadView(view: WindowView) {
    windowLabel = view;

    if (view === "pairing") {
      pairingStatus = "Waiting for scanner...";

      try {
        const info: any = await invoke("get_pairing_info");
        pairingQr = info.qr_svg;
        pairingFingerprint = info.fingerprint;
      } catch (e) {
        pairingQr = "";
        pairingFingerprint = "";
        pairingStatus = "Failed to load QR code";
      }

      await loadPairedDevices();
    }

    if (view === "history" || view === "main") {
      try {
        mediaHistory = await invoke("get_media_history");
      } catch (e) {}
    }

    if (view === "settings" || view === "main") {
      try {
        settings = await invoke("get_settings");
      } catch (e) {}
    }
  }

  onMount(async () => {
    // Determine the initial view from either the window label or the startup query.
    let initialView: WindowView = "main";

    try {
      const { getCurrentWindow } = await import("@tauri-apps/api/window");
      initialView = normalizeView(getCurrentWindow().label);
    } catch (e) {
      initialView = "main";
    }

    const requestedView = normalizeView(new URLSearchParams(window.location.search).get("view"));
    if (requestedView !== "main") {
      initialView = requestedView;
    }

    await loadView(initialView);

    listen("pairing-status", (event: any) => {
      pairingStatus = event.payload;
    });

    listen("media-received", (event: any) => {
      mediaHistory = [event.payload, ...mediaHistory];
    });

    listen("devices-changed", async () => {
      await loadPairedDevices();
    });

    listen("navigate", (event: any) => {
      void loadView(normalizeView(event.payload));
    });
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
  <div class="dashboard animate-fade">
    <nav class="sidebar">
      <div class="logo-area">
        <div class="logo-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M5 12h14" />
            <path d="M8 8l-4 4 4 4" />
            <path d="M16 8l4 4-4 4" />
            <path d="M12 5v14" />
          </svg>
        </div>
        <h3>CapturePort</h3>
      </div>
      <div class="nav-links">
        <button class="nav-btn" class:active={windowLabel === "history" || windowLabel === "main"} onclick={() => void loadView("history")}>
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2h6.5A2.5 2.5 0 0 1 21 9.5v7A2.5 2.5 0 0 1 18.5 19h-13A2.5 2.5 0 0 1 3 16.5z" />
          </svg>
          <span>Activity log</span>
        </button>
        <button class="nav-btn" class:active={windowLabel === "pairing"} onclick={() => void loadView("pairing")}>
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
            <path d="M10 6.5h4" />
            <circle cx="12" cy="17.5" r="1" fill="currentColor" stroke="none" />
          </svg>
          <span>Pairing</span>
        </button>
        <button class="nav-btn" class:active={windowLabel === "settings"} onclick={() => void loadView("settings")}>
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M4 7h7" />
            <path d="M15 7h5" />
            <path d="M4 17h3" />
            <path d="M11 17h9" />
            <circle cx="13" cy="7" r="2" />
            <circle cx="9" cy="17" r="2" />
          </svg>
          <span>Settings</span>
        </button>
      </div>
    </nav>

    <section class="content-area" class:content-area-centered={windowLabel === "pairing"}>
      {#if windowLabel === "history" || windowLabel === "main"}
        <div class="content-header">
          <h2>Activity Log</h2>
          <p>Recently received media files captured on your devices</p>
        </div>

        {#if mediaHistory.length === 0}
          <div class="empty-state">
            <div class="empty-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M5 8.5A2.5 2.5 0 0 1 7.5 6h2l1.2-1.5h2.6L14.5 6h2A2.5 2.5 0 0 1 19 8.5v7A2.5 2.5 0 0 1 16.5 18h-9A2.5 2.5 0 0 1 5 15.5z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
            </div>
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
                      <span class="play-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24" fill="currentColor">
                          <path d="M8 6.5v11l8.5-5.5z" />
                        </svg>
                      </span>
                      <span class="video-badge">VIDEO</span>
                    </div>
                  {/if}
                </div>
                <div class="media-info">
                  <span class="media-kind" class:kind-video={item.kind === 'video'}>
                    {item.kind.toUpperCase()}
                  </span>
                  {#if item.device_name}
                    <span class="media-device-name" style="margin-left: 8px; font-size: 11px; color: #8c8e96;">
                      {item.device_name}
                    </span>
                  {/if}
                  <span class="media-time">
                    {new Date(item.timestamp).toLocaleTimeString()}
                  </span>
                </div>
              </button>
            {/each}
          </div>
        {/if}

      {:else if windowLabel === "pairing"}
        <div class="pairing-content">
          <div class="pairing-columns">
            <div class="panel pairing-panel">
              <header class="panel-header">
                <div class="panel-title">
                  <span class="title-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                      <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
                      <path d="M10 6.5h4" />
                      <circle cx="12" cy="17.5" r="1" fill="currentColor" stroke="none" />
                    </svg>
                  </span>
                  <h2>Pair New Device</h2>
                </div>
                <p>Scan this QR code from the CapturePort app on your phone</p>
              </header>

              <!-- svelte-ignore a11y_click_events_have_key_events -->
              <!-- svelte-ignore a11y_no_static_element_interactions -->
              <div class="qr-container" onclick={refreshPairingInfo} title="Click to refresh QR Code">
                {#if pairingQr}
                  <img src={pairingQr} class="qr-graphic-img" alt="Pairing QR Code" />
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

              {#if pairedDevices.length > 0}
                <div class="pairing-warning">
                  <p><strong>Notice:</strong> PC is already paired. Scanning will update pairing or add a new device.</p>
                  <button class="action-btn text-danger" onclick={regenerateIdentity}>
                    Regenerate PC Identity
                  </button>
                </div>
              {/if}

              <footer class="pairing-footer">
                <div class="status-indicator">
                  <span class="pulse-dot"></span>
                  <span class="status-text">{pairingStatus}</span>
                </div>
              </footer>
            </div>

            <div class="panel devices-panel">
              <header class="panel-header">
                <div class="panel-title">
                  <span class="title-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                      <circle cx="9" cy="7" r="4" />
                      <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                    </svg>
                  </span>
                  <h2>Paired Devices</h2>
                </div>
                <p>Manage devices allowed to connect to this PC</p>
              </header>

              <div class="devices-list">
                {#if pairedDevices.length === 0}
                  <div class="empty-devices">
                    <p>No devices paired yet.</p>
                    <p class="subtitle" style="font-size: 12px; color: #5C5D64; margin-top: 4px;">Use the QR code to pair your first device.</p>
                  </div>
                {:else}
                  {#each pairedDevices as device}
                    <div class="device-card">
                      <div class="device-info-row">
                        <div class="device-details">
                          <span class="device-name">{device.name}</span>
                          <span class="device-os">{device.os}</span>
                        </div>
                        <div class="device-status">
                          <span class="status-badge" class:online={device.online}>
                            {device.online ? "Online" : "Offline"}
                          </span>
                        </div>
                      </div>

                      <div class="device-controls">
                        <div class="mcp-toggle-container">
                          <label class="switch">
                            <input type="checkbox" checked={device.exposed_to_mcp} onchange={(e) => toggleMcp(device.id, e.currentTarget.checked)} />
                            <span class="slider round"></span>
                          </label>
                          <span class="mcp-label">Pass to MCP</span>
                        </div>

                        <button class="unpair-btn" onclick={() => unpairDevice(device.id)}>
                          Unpair
                        </button>
                      </div>
                    </div>
                  {/each}
                {/if}
              </div>
            </div>
          </div>
        </div>

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

          <div class="form-group checkbox-group">
            <input id="close-to-tray" type="checkbox" bind:checked={settings.closeToTray} />
            <label for="close-to-tray">Minimize to system tray instead of exiting on window close</label>
          </div>

          <button type="submit" class="submit-btn">Save Configurations</button>
        </form>
      {/if}
    </section>
  </div>
</main>

<style>
  /* Custom modern dark theme colors and CSS reset */
  :global(html) {
    color-scheme: dark;
  }

  :global(body) {
    margin: 0;
    padding: 0;
    font-family: 'Segoe UI', -apple-system, Roboto, Helvetica, sans-serif;
    background-color: #101114;
    color: #E3E3E6;
    overflow: hidden;
    color-scheme: dark;
  }

  /* Custom dark scrollbar styling for WebKit */
  ::-webkit-scrollbar {
    width: 8px;
    height: 8px;
  }
  ::-webkit-scrollbar-track {
    background: #101114;
  }
  ::-webkit-scrollbar-thumb {
    background: #2F3138;
    border-radius: 4px;
  }
  ::-webkit-scrollbar-thumb:hover {
    background: #44464F;
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

  /* Pairing Columns & Layout */
  .pairing-content {
    width: 100%;
    min-height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .pairing-columns {
    display: flex;
    gap: 24px;
    max-width: 900px;
    width: 100%;
    align-items: stretch;
    justify-content: center;
    padding: 16px;
    box-sizing: border-box;
  }

  .pairing-panel, .devices-panel {
    flex: 1;
    background-color: #141519;
    border: 1px solid #232429;
    border-radius: 16px;
    padding: 24px;
    display: flex;
    flex-direction: column;
    align-items: center;
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.4);
    box-sizing: border-box;
  }

  .devices-panel {
    align-items: stretch;
  }

  .panel-header {
    text-align: center;
    margin-bottom: 20px;
    width: 100%;
  }

  .panel-title {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    width: 100%;
  }

  .panel-header h2 {
    margin: 0;
    font-weight: 600;
    color: #A4B4FF;
    font-size: 20px;
  }

  .title-icon {
    width: 22px;
    height: 22px;
    color: #A4B4FF;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .title-icon svg {
    width: 100%;
    height: 100%;
  }

  .panel-header p {
    margin: 6px 0 0 0;
    font-size: 13px;
    color: #8C8E96;
  }

  .qr-container {
    background-color: #FFFFFF;
    padding: 16px;
    border-radius: 16px;
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.4);
    margin-bottom: 20px;
    width: 240px;
    height: 240px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: transform 0.2s, box-shadow 0.2s;
  }

  .qr-container:hover {
    transform: scale(1.02);
    box-shadow: 0 12px 40px rgba(164, 180, 255, 0.2);
  }

  .qr-container:active {
    transform: scale(0.98);
  }

  /* Devices list and card stylings */
  .devices-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-top: 8px;
    overflow-y: auto;
    max-height: 380px;
    padding-right: 4px;
  }

  .empty-devices {
    text-align: center;
    color: #8C8E96;
    padding: 48px 16px;
    font-size: 14px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    flex: 1;
  }

  .empty-devices p {
    margin: 0;
  }

  .device-card {
    background-color: #1A1B20;
    border: 1px solid #232429;
    border-radius: 12px;
    padding: 14px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .device-info-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .device-details {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .device-name {
    font-weight: 600;
    color: #E3E3E6;
    font-size: 14px;
  }

  .device-os {
    font-size: 11px;
    color: #8C8E96;
    text-transform: capitalize;
  }

  .status-badge {
    font-size: 10px;
    font-weight: 600;
    padding: 2px 8px;
    border-radius: 10px;
    background-color: #2F2121;
    color: #FF8F8F;
  }

  .status-badge.online {
    background-color: #1B2F21;
    color: #8FFF9F;
  }

  .device-controls {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid #232429;
    padding-top: 10px;
  }

  .mcp-toggle-container {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .mcp-label {
    font-size: 12px;
    color: #C5C4DD;
  }

  .unpair-btn {
    background: none;
    border: 1px solid #FF5C5C40;
    color: #FF5C5C;
    padding: 4px 10px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
  }

  .unpair-btn:hover {
    background-color: #FF5C5C15;
    border-color: #FF5C5C;
  }

  .unpair-btn:active {
    transform: scale(0.97);
  }

  /* Custom Switch Toggle styling */
  .switch {
    position: relative;
    display: inline-block;
    width: 32px;
    height: 18px;
  }

  .switch input {
    opacity: 0;
    width: 0;
    height: 0;
  }

  .slider {
    position: absolute;
    cursor: pointer;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-color: #2F3138;
    transition: .3s;
  }

  .slider:before {
    position: absolute;
    content: "";
    height: 12px;
    width: 12px;
    left: 3px;
    bottom: 3px;
    background-color: #8C8E96;
    transition: .3s;
  }

  input:checked + .slider {
    background-color: #3B5BFF;
  }

  input:checked + .slider:before {
    transform: translateX(14px);
    background-color: #FFFFFF;
  }

  .slider.round {
    border-radius: 18px;
  }

  .slider.round:before {
    border-radius: 50%;
  }

  /* Styling for warnings / action buttons */
  .pairing-warning {
    margin: 8px 0 16px 0;
    padding: 10px;
    background-color: #221A1A;
    border: 1px solid #FF5C5C25;
    border-radius: 8px;
    text-align: center;
    width: 100%;
    box-sizing: border-box;
  }

  .pairing-warning p {
    margin: 0 0 6px 0;
    font-size: 11px;
    color: #FF8F8F;
    line-height: 1.4;
  }

  .action-btn {
    background: none;
    border: 1px solid #44464F;
    color: #E3E3E6;
    padding: 4px 8px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
  }

  .action-btn:hover {
    background-color: #1F2128;
    border-color: #8C8E96;
  }

  .action-btn.text-danger {
    color: #FF5C5C;
    border-color: #FF5C5C30;
  }

  .action-btn.text-danger:hover {
    background-color: #FF5C5C15;
    border-color: #FF5C5C;
  }

  .action-btn:active {
    transform: scale(0.97);
  }

  .qr-graphic-img {
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

  .pairing-footer {
    display: flex;
    justify-content: center;
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
    gap: 12px;
    margin-bottom: 32px;
    padding-left: 8px;
  }

  .logo-mark {
    width: 36px;
    height: 36px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 12px;
    background-color: #151821;
    color: #A4B4FF;
    box-shadow: inset 0 0 0 1px #252834;
  }

  .logo-mark svg {
    width: 20px;
    height: 20px;
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
    display: flex;
    align-items: center;
    gap: 10px;
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

  .nav-icon {
    width: 18px;
    height: 18px;
    flex: 0 0 18px;
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

  .content-area.content-area-centered {
    display: flex;
    align-items: center;
    justify-content: center;
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
    width: 52px;
    height: 52px;
    margin-bottom: 16px;
    color: #A4B4FF;
  }

  .empty-icon svg {
    width: 100%;
    height: 100%;
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
    width: 28px;
    height: 28px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .play-icon svg {
    width: 100%;
    height: 100%;
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
