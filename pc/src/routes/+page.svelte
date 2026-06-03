<script lang="ts">
  import { onMount } from "svelte";
  import { fade } from "svelte/transition";
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
  let pairingHosts = $state<string[]>([]);
  let pairingLocalHosts = $state<string[]>([]);
  let pairingInternetHost = $state("");
  let pairingInternetPort = $state(7878);
  let pairingEndpointMode = $state("local-only");
  let mediaHistory = $state<any[]>([]);
  let pairedDevices = $state<any[]>([]);
  let settingsTab = $state<"general" | "network">("general");
  let manualIpInput = $state("");
  let firewallStatus = $state("");

  // Custom Confirmation Modal State
  let showConfirmModal = $state(false);
  let confirmTitle = $state("");
  let confirmMessage = $state("");
  let confirmDangerLabel = $state("");
  let onConfirmCallback = $state<(() => void) | null>(null);

  function showConfirm(title: string, message: string, dangerLabel: string, onConfirm: () => void) {
    confirmTitle = title;
    confirmMessage = message;
    confirmDangerLabel = dangerLabel;
    onConfirmCallback = onConfirm;
    showConfirmModal = true;
  }
  let settings = $state({
    deviceName: "PC-Machine",
    port: 7878,
    mcpEnabled: true,
    autoStart: false,
    closeToTray: false,
    localIpMode: "auto",
    customLocalHost: "",
    externalHost: "",
    externalPort: 7878,
    externalEnabled: false
  });

  // Save Settings State
  let saveStatus = $state<"" | "saving" | "success" | "error">("");
  let saveErrorMessage = $state("");
  let saveTimeout: any;

  // Clipboard Feedback State
  let copyTarget = $state("");
  let copyTimeout: any;

  function copyToClipboard(text: string, label: string = "fingerprint") {
    navigator.clipboard.writeText(text).then(() => {
      copyTarget = label;
      if (copyTimeout) clearTimeout(copyTimeout);
      copyTimeout = setTimeout(() => {
        copyTarget = "";
      }, 2000);
    }).catch(() => {});
  }

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
      const info: any = await invoke("get_pairing_info", { endpointMode: pairingEndpointMode });
      pairingQr = info.qr_svg;
      pairingFingerprint = info.fingerprint;
      pairingHosts = info.hosts || [];
      pairingLocalHosts = info.local_hosts || [];
      pairingInternetHost = info.internet_host || "";
      pairingInternetPort = info.internet_port || settings.externalPort || settings.port;
      pairingStatus = "Waiting for scanner...";
    } catch (e) {
      pairingQr = "";
      pairingFingerprint = "";
      pairingHosts = [];
      pairingLocalHosts = [];
      pairingInternetHost = "";
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

  async function renamePairedDevice(id: string, alias: string) {
    try {
      await invoke("rename_paired_device", { id, alias });
      await loadPairedDevices();
    } catch (e) {
      alert("Failed to rename device");
    }
  }

  async function unpairDevice(id: string) {
    showConfirm(
      "Unpair Device",
      "Are you sure you want to unpair this device? It will no longer be able to capture and send media files to this PC.",
      "Unpair",
      async () => {
        try {
          await invoke("unpair_device", { id });
        } catch (e) {
          alert("Failed to unpair device");
        }
      }
    );
  }

  async function regenerateIdentity() {
    showConfirm(
      "Regenerate PC Identity",
      "Are you sure you want to regenerate the PC identity? This will invalidate all current pairings, and you will need to re-pair all your devices.",
      "Regenerate",
      async () => {
        pairingStatus = "Regenerating identity...";
        try {
          const info: any = await invoke("regenerate_pc_identity");
          pairingQr = info.qr_svg;
          pairingFingerprint = info.fingerprint;
          pairingHosts = info.hosts || [];
          pairingLocalHosts = info.local_hosts || [];
          pairingInternetHost = info.internet_host || "";
          pairingInternetPort = info.internet_port || settings.externalPort || settings.port;
          pairingStatus = "Waiting for scanner...";
          await loadPairedDevices();
        } catch (e) {
          pairingStatus = "Failed to regenerate identity";
        }
      }
    );
  }

  async function openSettings() {
    try {
      settings = await invoke("get_settings");
    } catch (e) {}
  }

  async function addManualIp() {
    if (!manualIpInput.trim()) return;
    try {
      settings.localIpMode = "custom";
      settings.customLocalHost = manualIpInput.trim();
      const snapshot = $state.snapshot(settings);
      await invoke("save_settings", { newSettings: snapshot });
      await refreshPairingInfo();
      manualIpInput = "";
    } catch (e) {
      alert(`Failed to set manual IP: ${e}`);
    }
  }

  async function loadView(view: WindowView) {
    windowLabel = view;

    if (view === "settings") {
      await openSettings();
      return;
    }

    if (view === "pairing") {
      pairingStatus = "Waiting for scanner...";

      try {
        const info: any = await invoke("get_pairing_info", { endpointMode: pairingEndpointMode });
        pairingQr = info.qr_svg;
        pairingFingerprint = info.fingerprint;
        pairingHosts = info.hosts || [];
        pairingLocalHosts = info.local_hosts || [];
        pairingInternetHost = info.internet_host || "";
        pairingInternetPort = info.internet_port || settings.externalPort || settings.port;
      } catch (e) {
        pairingQr = "";
        pairingFingerprint = "";
        pairingHosts = [];
        pairingLocalHosts = [];
        pairingInternetHost = "";
        pairingStatus = "Failed to load QR code";
      }

      await loadPairedDevices();
    }

    if (view === "history" || view === "main") {
      try {
        mediaHistory = await invoke("get_media_history");
      } catch (e) {}
    }

    if (view === "main") {
      try {
        settings = await invoke("get_settings");
      } catch (e) {}
    }
  }

  onMount(() => {
    let initialView: WindowView = "main";
    let unlistenPairingStatus: () => void;
    let unlistenMediaReceived: () => void;
    let unlistenDevicesChanged: () => void;
    let unlistenNavigate: () => void;

    async function init() {
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
      await loadPairedDevices();

      unlistenPairingStatus = await listen("pairing-status", (event: any) => {
        pairingStatus = event.payload;
      });

      unlistenMediaReceived = await listen("media-received", (event: any) => {
        mediaHistory = [event.payload, ...mediaHistory];
      });

      unlistenDevicesChanged = await listen("devices-changed", async () => {
        await loadPairedDevices();
      });

      unlistenNavigate = await listen("navigate", (event: any) => {
        void loadView(normalizeView(event.payload));
      });
    }

    void init();

    return () => {
      if (unlistenPairingStatus) unlistenPairingStatus();
      if (unlistenMediaReceived) unlistenMediaReceived();
      if (unlistenDevicesChanged) unlistenDevicesChanged();
      if (unlistenNavigate) unlistenNavigate();
      if (saveTimeout) clearTimeout(saveTimeout);
    };
  });

  async function saveSettings() {
    saveStatus = "saving";
    try {
      const snapshot = $state.snapshot(settings);
      await invoke("save_settings", { newSettings: snapshot });
      saveStatus = "success";
      if (saveTimeout) clearTimeout(saveTimeout);
      saveTimeout = setTimeout(() => {
        saveStatus = "";
      }, 3000);
    } catch (e) {
      saveStatus = "error";
      saveErrorMessage = String(e);
      if (saveTimeout) clearTimeout(saveTimeout);
      saveTimeout = setTimeout(() => {
        saveStatus = "";
      }, 5000);
    }
  }

  async function detectLocalIp() {
    try {
      settings.customLocalHost = await invoke("detect_local_advertised_ip");
      settings.localIpMode = "custom";
    } catch (e) {
      alert(`Failed to detect local IP: ${e}`);
    }
  }

  async function detectPublicIp() {
    try {
      settings.externalHost = await invoke("detect_public_ip");
      settings.externalEnabled = true;
    } catch (e) {
      alert(`Failed to detect public IP: ${e}`);
    }
  }

  async function openFirewallPort() {
    firewallStatus = "Opening system firewall prompt...";
    try {
      firewallStatus = await invoke("open_firewall_port", { port: settings.externalPort || settings.port });
    } catch (e) {
      firewallStatus = `Firewall setup failed: ${e}`;
    }
  }

  function setPairingEndpointMode(mode: string) {
    pairingEndpointMode = mode;
    void refreshPairingInfo();
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
            <circle cx="12" cy="12" r="10" />
            <line x1="14.31" y1="8" x2="20.05" y2="17.94" />
            <line x1="9.69" y1="8" x2="21.17" y2="8" />
            <line x1="7.38" y1="12" x2="13.12" y2="2.06" />
            <line x1="9.69" y1="16" x2="3.95" y2="6.06" />
            <line x1="14.31" y1="16" x2="2.83" y2="16" />
            <line x1="16.62" y1="12" x2="10.88" y2="21.94" />
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
        <button class="nav-btn settings-trigger-btn" class:active={windowLabel === "settings"} onclick={() => void loadView("settings")}>
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

      <div class="sidebar-devices-container">
        <div class="sidebar-devices-header">
          <span>PAIRED DEVICES</span>
          {#if pairedDevices.length > 0}
            <span class="device-count-badge">{pairedDevices.length}</span>
          {/if}
        </div>
        <div class="sidebar-devices-list">
          {#if pairedDevices.length === 0}
            <div class="sidebar-empty-devices">
              No paired devices
            </div>
          {:else}
            {#each pairedDevices as device}
              <div class="sidebar-device-card">
                <div class="sidebar-device-main">
                  <div class="sidebar-device-details">
                    <input
                      class="sidebar-device-alias-input"
                      value={device.alias || device.name}
                      onchange={(e) => renamePairedDevice(device.id, e.currentTarget.value)}
                      title="Click to rename"
                    />
                    <div class="sidebar-device-os-row">
                      <span class="sidebar-device-os">{device.os}</span>
                      {#if device.online}
                        <span class="sidebar-device-ip-info">
                          {device.ip || 'Unknown'} • {device.channel || 'Local'}
                        </span>
                      {/if}
                    </div>
                  </div>
                  <span class="sidebar-status-dot" class:online={device.online} title={device.online ? "Online" : "Offline"}></span>
                </div>
                <div class="sidebar-device-controls">
                  <div class="sidebar-mcp-container">
                    <label class="compact-switch">
                      <input type="checkbox" checked={device.exposed_to_mcp} onchange={(e) => toggleMcp(device.id, e.currentTarget.checked)} />
                      <span class="compact-slider round"></span>
                    </label>
                    <span class="compact-mcp-label">MCP</span>
                  </div>
                  <button type="button" class="sidebar-unpair-btn" onclick={() => unpairDevice(device.id)} title="Unpair Device">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <polyline points="3 6 5 6 21 6"></polyline>
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    </svg>
                  </button>
                </div>
              </div>
            {/each}
          {/if}
        </div>
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
            <div class="qr-polaroid" onclick={refreshPairingInfo} title="Click to refresh QR Code">
              <div class="qr-photo-area">
                {#if pairingQr}
                  <img src={pairingQr} class="qr-graphic-img" alt="Pairing QR Code" />
                {:else}
                  <div class="qr-placeholder spinner"></div>
                {/if}
              </div>
              <div class="qr-polaroid-caption">
                <span class="caption-title">Scan to Pair</span>
                <span class="caption-subtitle">Click to refresh</span>
              </div>
            </div>

            <div class="endpoint-mode-group" aria-label="QR endpoint mode">
              <button class:active={pairingEndpointMode === "local-only"} onclick={() => setPairingEndpointMode("local-only")}>Local only</button>
              <button class:active={pairingEndpointMode === "local-then-internet"} onclick={() => setPairingEndpointMode("local-then-internet")}>Local + Internet</button>
              <button class:active={pairingEndpointMode === "internet-only"} onclick={() => setPairingEndpointMode("internet-only")}>Internet only</button>
            </div>

            <div class="manual-ip-section">
              <form class="manual-ip-form" onsubmit={(e) => { e.preventDefault(); addManualIp(); }}>
                <div class="manual-ip-input-container">
                  <input
                    type="text"
                    placeholder="Enter PC IP manually (e.g. 192.168.1.50)"
                    bind:value={manualIpInput}
                    class="manual-ip-input"
                  />
                  <button type="submit" class="manual-ip-btn">Set</button>
                </div>
              </form>
            </div>

            <div class="connection-details-card">
              {#if pairingFingerprint}
                <div class="detail-section">
                  <div class="detail-label-row">
                    <span class="detail-label-title">
                      <svg class="icon-small" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                        <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                      </svg>
                      Device Fingerprint
                    </span>
                    <button class="copy-action-btn" onclick={() => copyToClipboard(pairingFingerprint, 'fingerprint')} title="Copy fingerprint">
                      {#if copyTarget === 'fingerprint'}
                        <span class="copied-indicator animate-scale">Copied!</span>
                      {:else}
                        <svg class="copy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                        </svg>
                      {/if}
                    </button>
                  </div>
                  <div class="detail-value-box">
                    <code class="mono-text">{pairingFingerprint}</code>
                  </div>
                </div>
              {/if}

              {#if pairingFingerprint && pairingHosts.length > 0}
                <div class="detail-divider"></div>
              {/if}

              {#if pairingHosts.length > 0}
                <div class="detail-section">
                  <div class="detail-label-row">
                    <span class="detail-label-title">
                      <svg class="icon-small" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="2" y="2" width="20" height="8" rx="2" ry="2"/>
                        <rect x="2" y="14" width="20" height="8" rx="2" ry="2"/>
                        <line x1="6" y1="6" x2="6.01" y2="6"/>
                        <line x1="6" y1="18" x2="6.01" y2="18"/>
                      </svg>
                      Manual IP Addresses
                    </span>
                  </div>
                  <div class="ip-addresses-list">
                    {#each pairingHosts as host}
                      <div class="ip-address-row">
                        <code class="mono-text-blue">{host}:{host === pairingInternetHost ? pairingInternetPort : settings.port}</code>
                        <button class="copy-action-btn-small" onclick={() => copyToClipboard(`${host}:${host === pairingInternetHost ? pairingInternetPort : settings.port}`, host)} title="Copy Address">
                          {#if copyTarget === host}
                            <span class="copied-indicator-small animate-scale">Copied!</span>
                          {:else}
                            <svg class="copy-icon-small" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                              <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                            </svg>
                          {/if}
                        </button>
                      </div>
                    {/each}
                  </div>
                </div>
              {/if}
            </div>

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
        </div>
      {:else if windowLabel === "settings"}
        <div class="settings-content animate-fade">
          <div class="content-header">
            <h2>Settings</h2>
            <p>Configure PC application and network settings</p>
          </div>
          
          <div class="panel settings-panel-content">
            <div class="settings-tabs">
              <div class="tab-strip">
                <button class:active={settingsTab === "general"} onclick={() => settingsTab = "general"}>General</button>
                <button class:active={settingsTab === "network"} onclick={() => settingsTab = "network"}>Network</button>
              </div>

              <form class="settings-form tab-panel" onsubmit={(e) => { e.preventDefault(); saveSettings(); }}>
                {#if settingsTab === "general"}
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
                {:else if settingsTab === "network"}
                  <div class="form-group">
                    <label for="local-ip-mode">Advertised Local IP</label>
                    <select id="local-ip-mode" bind:value={settings.localIpMode}>
                      <option value="auto">Auto</option>
                      <option value="custom">Custom</option>
                    </select>
                  </div>
                  <div class="form-group">
                    <label for="custom-local-host">Custom Local Host</label>
                    <div class="inline-input">
                      <input id="custom-local-host" type="text" bind:value={settings.customLocalHost} placeholder="192.168.0.111" />
                      <button type="button" class="secondary-btn" onclick={detectLocalIp}>Detect</button>
                    </div>
                  </div>
                  <div class="form-group checkbox-group">
                    <input id="external-enabled" type="checkbox" bind:checked={settings.externalEnabled} />
                    <label for="external-enabled">Enable internet endpoint in QR codes</label>
                  </div>
                  <div class="form-group">
                    <label for="external-host">External Host / DDNS</label>
                    <div class="inline-input">
                      <input id="external-host" type="text" bind:value={settings.externalHost} placeholder="capture.example.net" />
                      <button type="button" class="secondary-btn" onclick={detectPublicIp}>Detect</button>
                    </div>
                  </div>
                  <div class="form-group">
                    <label for="external-port">External Port</label>
                    <div class="inline-input">
                      <input id="external-port" type="number" bind:value={settings.externalPort} />
                      <button type="button" class="secondary-btn" onclick={openFirewallPort}>Open</button>
                    </div>
                  </div>
                  {#if firewallStatus}
                    <p class="settings-note">{firewallStatus}</p>
                  {/if}
                  <p class="settings-note">Router port forwarding is manual. Forward the external TCP port to this PC local address and WebSocket port.</p>
                {/if}

                <button type="submit" class="submit-btn">Save Configurations</button>

                <div class="save-status-container">
                  {#if saveStatus === 'saving'}
                    <div class="save-status-message saving animate-fade">
                      <span class="spinner-small" aria-hidden="true"></span>
                      <span>Saving configurations...</span>
                    </div>
                  {:else if saveStatus === 'success'}
                    <div class="save-status-message success animate-scale">
                      <svg class="status-icon-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                      <span>Configurations saved successfully!</span>
                    </div>
                  {:else if saveStatus === 'error'}
                    <div class="save-status-message error animate-scale">
                      <svg class="status-icon-error" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <circle cx="12" cy="12" r="10" />
                        <line x1="12" y1="8" x2="12" y2="12" />
                        <line x1="12" y1="16" x2="12.01" y2="16" />
                      </svg>
                      <span>Failed to save: {saveErrorMessage}</span>
                    </div>
                  {/if}
                </div>
              </form>
            </div>
          </div>
        </div>
      {/if}
    </section>
  </div>

  {#if showConfirmModal}
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="modal-backdrop" transition:fade onclick={() => showConfirmModal = false}>
      <div class="confirm-modal-card animate-scale" onclick={(e) => e.stopPropagation()}>
        <div class="modal-warning-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </div>
        <h3 class="modal-title">{confirmTitle}</h3>
        <p class="modal-message">{confirmMessage}</p>
        <div class="modal-actions">
          <button class="secondary-btn" onclick={() => showConfirmModal = false}>Cancel</button>
          <button class="danger-btn" onclick={() => {
            if (onConfirmCallback) onConfirmCallback();
            showConfirmModal = false;
          }}>{confirmDangerLabel}</button>
        </div>
      </div>
    </div>
  {/if}
</main>

<style>
  @import url('https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600;700&display=swap');

  /* Custom modern dark theme colors and CSS reset */
  :global(html) {
    color-scheme: dark;
  }

  :global(body) {
    margin: 0;
    padding: 0;
    font-family: 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, sans-serif;
    background-color: #08090c;
    color: #E3E3E6;
    overflow: hidden;
    color-scheme: dark;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }

  /* Custom dark scrollbar styling for WebKit */
  ::-webkit-scrollbar {
    width: 6px;
    height: 6px;
  }
  ::-webkit-scrollbar-track {
    background: transparent;
  }
  ::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.08);
    border-radius: 4px;
  }
  ::-webkit-scrollbar-thumb:hover {
    background: rgba(255, 255, 255, 0.15);
  }

  .app-container {
    width: 100vw;
    height: 100vh;
    display: flex;
    background-color: #08090c;
    background-image: 
      radial-gradient(at 0% 0%, rgba(59, 91, 255, 0.1) 0px, transparent 55%),
      radial-gradient(at 100% 100%, rgba(139, 92, 246, 0.08) 0px, transparent 55%),
      radial-gradient(at 100% 0%, rgba(59, 91, 255, 0.02) 0px, transparent 40%),
      radial-gradient(at 0% 100%, rgba(139, 92, 246, 0.02) 0px, transparent 40%);
    position: relative;
    overflow: hidden;
  }

  /* Vector animations and typography */
  .animate-fade {
    animation: fadeIn 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(8px); }
    to { opacity: 1; transform: translateY(0); }
  }

  /* Pairing Columns & Layout */
  .pairing-content {
    width: 100%;
    height: 100%;
    max-height: calc(100vh - 48px);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow-y: auto;
    padding: 32px 24px;
    box-sizing: border-box;
  }

  .pairing-columns {
    display: flex;
    flex-direction: row;
    flex-wrap: wrap;
    gap: 32px;
    max-width: 980px;
    width: 100%;
    align-items: stretch;
    justify-content: center;
  }

  .pairing-panel {
    flex: 1 1 340px;
    min-width: 320px;
    background: rgba(13, 14, 18, 0.45);
    backdrop-filter: blur(24px) saturate(150%);
    -webkit-backdrop-filter: blur(24px) saturate(150%);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 28px;
    padding: 32px;
    display: flex;
    flex-direction: column;
    align-items: center;
    box-shadow: 
      0 4px 30px rgba(0, 0, 0, 0.3),
      inset 0 1px 1px rgba(255, 255, 255, 0.03),
      0 24px 48px rgba(0, 0, 0, 0.4);
    box-sizing: border-box;
    transition: all 0.4s cubic-bezier(0.16, 1, 0.3, 1);
  }

  .pairing-panel:hover {
    border-color: rgba(91, 123, 255, 0.2);
    box-shadow: 
      0 8px 36px rgba(0, 0, 0, 0.4),
      0 0 25px rgba(59, 91, 255, 0.06),
      inset 0 1px 2px rgba(255, 255, 255, 0.05),
      0 32px 64px rgba(0, 0, 0, 0.5);
    transform: translateY(-3px);
  }

  .panel-header {
    text-align: center;
    margin-bottom: 28px;
    width: 100%;
  }

  .panel-title {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    width: 100%;
    margin-bottom: 8px;
  }

  .panel-header h2 {
    margin: 0;
    font-weight: 700;
    color: #FFFFFF;
    font-size: 22px;
    letter-spacing: -0.5px;
  }

  .title-icon {
    width: 24px;
    height: 24px;
    color: #3B5BFF;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    filter: drop-shadow(0 2px 8px rgba(59, 91, 255, 0.4));
  }

  .title-icon svg {
    width: 100%;
    height: 100%;
  }

  .panel-header p {
    margin: 0;
    font-size: 13px;
    color: #989A9F;
    line-height: 1.5;
    font-weight: 400;
  }

  /* Redesigned QR Card */
  .qr-polaroid {
    background: rgba(255, 255, 255, 0.025);
    backdrop-filter: blur(10px);
    -webkit-backdrop-filter: blur(10px);
    padding: 16px;
    border-radius: 20px;
    box-shadow: 
      0 20px 40px rgba(0, 0, 0, 0.4),
      0 0 30px rgba(59, 91, 255, 0.08),
      inset 0 1px 1px rgba(255, 255, 255, 0.05);
    margin-bottom: 24px;
    width: 280px;
    display: flex;
    flex-direction: column;
    align-items: center;
    cursor: pointer;
    transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
    border: 1px solid rgba(255, 255, 255, 0.06);
    position: relative;
    overflow: hidden;
  }

  .qr-polaroid:hover {
    transform: scale(1.03) translateY(-4px);
    border-color: rgba(164, 180, 255, 0.25);
    box-shadow: 
      0 24px 48px rgba(0, 0, 0, 0.5),
      0 0 40px rgba(59, 91, 255, 0.2),
      inset 0 1px 2px rgba(255, 255, 255, 0.1);
  }

  .qr-polaroid:active {
    transform: scale(0.98) translateY(-1px);
  }

  .qr-photo-area {
    width: 100%;
    aspect-ratio: 1;
    background: #ffffff;
    border-radius: 12px;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 8px;
    box-sizing: border-box;
    box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.1);
  }

  .qr-graphic-img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }

  .qr-placeholder {
    width: 48px;
    height: 48px;
    border: 3px solid rgba(59, 91, 255, 0.1);
    border-top: 3px solid #3B5BFF;
    border-radius: 50%;
    animation: spin 1s cubic-bezier(0.55, 0.055, 0.675, 0.19) infinite;
  }

  @keyframes spin {
    0% { transform: rotate(0deg); }
    100% { transform: rotate(360deg); }
  }

  .qr-polaroid-caption {
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    user-select: none;
  }

  .qr-polaroid-caption .caption-title {
    font-size: 13px;
    font-weight: 700;
    color: #FFFFFF;
    letter-spacing: -0.2px;
  }

  .qr-polaroid-caption .caption-subtitle {
    font-family: 'JetBrains Mono', monospace;
    font-size: 8.5px;
    font-weight: 600;
    color: #8c8e96;
    text-transform: uppercase;
    letter-spacing: 0.8px;
  }

  .endpoint-mode-group {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    margin: 2px 0 16px;
  }

  .endpoint-mode-group button,
  .tab-strip button,
  .secondary-btn {
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.035);
    color: #C8CAD2;
    border-radius: 10px;
    min-height: 34px;
    padding: 0 10px;
    font-size: 11px;
    font-weight: 700;
    cursor: pointer;
    transition: border-color 0.2s ease, background 0.2s ease, color 0.2s ease, transform 0.1s ease;
  }

  .endpoint-mode-group button:hover,
  .tab-strip button:hover,
  .secondary-btn:hover {
    border-color: rgba(255, 255, 255, 0.16);
    background: rgba(255, 255, 255, 0.06);
    color: #FFFFFF;
  }

  .endpoint-mode-group button:active,
  .tab-strip button:active,
  .secondary-btn:active {
    transform: scale(0.98);
  }

  .endpoint-mode-group button.active,
  .tab-strip button.active {
    border-color: rgba(91, 123, 255, 0.55);
    background: rgba(59, 91, 255, 0.18);
    color: #FFFFFF;
  }

  .secondary-btn {
    padding: 0 16px;
    font-size: 13px;
    min-height: 42px; /* Matches the text input height perfectly */
  }

  /* Connection details card */
  .connection-details-card {
    background: rgba(255, 255, 255, 0.015);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 20px;
    width: 100%;
    box-sizing: border-box;
    padding: 20px;
    display: flex;
    flex-direction: column;
    gap: 16px;
    margin-bottom: 24px;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.02);
  }

  .detail-section {
    display: flex;
    flex-direction: column;
    gap: 8px;
    width: 100%;
  }

  .detail-label-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .detail-label-title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 1.5px;
    color: #3B5BFF;
  }

  .icon-small {
    width: 14px;
    height: 14px;
    color: #3B5BFF;
    opacity: 0.85;
  }

  .detail-value-box {
    background: rgba(0, 0, 0, 0.25);
    border: 1px solid rgba(255, 255, 255, 0.04);
    border-radius: 12px;
    padding: 10px 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
  }

  .mono-text {
    font-family: 'JetBrains Mono', monospace;
    font-size: 13px;
    font-weight: 500;
    color: #e3e3e6;
    letter-spacing: 0.5px;
    word-break: break-all;
  }

  .detail-divider {
    height: 1px;
    background: rgba(255, 255, 255, 0.06);
    width: 100%;
  }

  .ip-addresses-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    width: 100%;
  }

  .ip-address-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: rgba(0, 0, 0, 0.2);
    border: 1px solid rgba(255, 255, 255, 0.03);
    border-radius: 10px;
    padding: 8px 14px;
    transition: all 0.2s ease;
  }

  .ip-address-row:hover {
    background: rgba(59, 91, 255, 0.05);
    border-color: rgba(59, 91, 255, 0.15);
  }

  .mono-text-blue {
    font-family: 'JetBrains Mono', monospace;
    font-size: 12.5px;
    font-weight: 600;
    color: #A4B4FF;
  }

  /* Copy Button and animation */
  .copy-action-btn, .copy-action-btn-small {
    background: none;
    border: none;
    color: #8c8e96;
    cursor: pointer;
    padding: 4px;
    border-radius: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s ease;
  }

  .copy-action-btn:hover, .copy-action-btn-small:hover {
    background: rgba(255, 255, 255, 0.05);
    color: #FFFFFF;
  }

  .copy-icon {
    width: 14px;
    height: 14px;
  }

  .copy-icon-small {
    width: 13px;
    height: 13px;
  }

  .copied-indicator {
    font-size: 10px;
    font-weight: 600;
    color: #3bff8a;
    background: rgba(59, 255, 138, 0.1);
    padding: 2px 6px;
    border-radius: 6px;
    border: 1px solid rgba(59, 255, 138, 0.2);
  }

  .copied-indicator-small {
    font-size: 9px;
    font-weight: 600;
    color: #3bff8a;
    background: rgba(59, 255, 138, 0.1);
    padding: 1px 5px;
    border-radius: 4px;
    border: 1px solid rgba(59, 255, 138, 0.15);
  }

  .animate-scale {
    animation: scaleIn 0.25s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
  }

  @keyframes scaleIn {
    from { opacity: 0; transform: scale(0.9); }
    to { opacity: 1; transform: scale(1); }
  }

  .pairing-warning {
    margin: 8px 0 20px 0;
    padding: 14px 20px;
    background: rgba(255, 92, 92, 0.04);
    border: 1px solid rgba(255, 92, 92, 0.15);
    border-radius: 16px;
    text-align: center;
    width: 100%;
    box-sizing: border-box;
    backdrop-filter: blur(10px);
  }

  .pairing-warning p {
    margin: 0 0 10px 0;
    font-size: 12px;
    color: #FF8F8F;
    line-height: 1.5;
  }

  .pairing-warning strong {
    color: #FF5C5C;
    font-weight: 600;
  }

  .action-btn {
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.1);
    color: #E3E3E6;
    padding: 6px 14px;
    border-radius: 10px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  }

  .action-btn:hover {
    background-color: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.2);
    transform: translateY(-1px);
  }

  .action-btn.text-danger {
    color: #FF5C5C;
    border-color: rgba(255, 92, 92, 0.2);
    background: rgba(255, 92, 92, 0.05);
  }

  .action-btn.text-danger:hover {
    background-color: rgba(255, 92, 92, 0.12);
    border-color: rgba(255, 92, 92, 0.4);
    color: #FF8F8F;
    box-shadow: 0 4px 12px rgba(255, 92, 92, 0.15);
  }

  .action-btn:active {
    transform: translateY(0) scale(0.98);
  }

  .pairing-footer {
    display: flex;
    justify-content: center;
    width: 100%;
    margin-top: auto;
    padding-top: 16px;
  }

  .status-indicator {
    display: flex;
    align-items: center;
    gap: 10px;
    background: rgba(255, 255, 255, 0.03);
    padding: 8px 18px;
    border-radius: 24px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.02);
  }

  .pulse-dot {
    width: 8px;
    height: 8px;
    background-color: #3B5BFF;
    border-radius: 50%;
    box-shadow: 0 0 10px rgba(59, 91, 255, 0.8);
    animation: pulse 2s infinite ease-in-out;
  }

  @keyframes pulse {
    0% { transform: scale(0.8); opacity: 0.6; box-shadow: 0 0 4px rgba(59, 91, 255, 0.4); }
    50% { transform: scale(1.2); opacity: 1; box-shadow: 0 0 12px rgba(59, 91, 255, 0.9); }
    100% { transform: scale(0.8); opacity: 0.6; box-shadow: 0 0 4px rgba(59, 91, 255, 0.4); }
  }

  .status-text {
    font-size: 12px;
    font-weight: 500;
    color: #C5C4DD;
  }



  @media (max-width: 860px) {
    .pairing-content {
      align-items: stretch;
      justify-content: flex-start;
      padding: 16px;
      max-height: calc(100vh - 48px);
    }

    .pairing-columns {
      display: grid;
      grid-template-columns: minmax(250px, 1.05fr) minmax(220px, 0.95fr);
      gap: 16px;
      max-width: none;
      height: 100%;
      align-items: stretch;
    }

    .pairing-panel {
      min-width: 0;
      max-width: none;
      padding: 18px;
      border-radius: 20px;
      box-shadow:
        0 4px 18px rgba(0, 0, 0, 0.28),
        inset 0 1px 1px rgba(255, 255, 255, 0.03);
      overflow-y: auto;
      scrollbar-gutter: stable;
    }

    .pairing-panel:hover {
      transform: none;
    }

    .panel-header {
      margin-bottom: 14px;
    }

    .panel-header h2 {
      font-size: 18px;
    }

    .panel-header p {
      font-size: 12px;
      line-height: 1.35;
    }

    .qr-polaroid {
      width: 168px;
      padding: 12px;
      margin-bottom: 14px;
      border-radius: 16px;
    }

    .qr-photo-area {
      border-radius: 10px;
      padding: 6px;
    }

    .qr-polaroid-caption {
      margin-top: 9px;
    }

    .connection-details-card {
      padding: 14px;
      gap: 10px;
      margin-bottom: 14px;
      border-radius: 16px;
    }

    .detail-label-title {
      font-size: 9px;
      letter-spacing: 1px;
    }

    .mono-text {
      font-size: 11px;
    }

    .mono-text-blue {
      font-size: 10.5px;
      min-width: 0;
      overflow-wrap: anywhere;
    }

    .ip-address-row {
      gap: 8px;
      padding: 7px 10px;
    }

    .pairing-warning {
      margin-bottom: 12px;
      padding: 8px 12px;
      font-size: 11px;
      border-radius: 12px;
    }

    .pairing-footer {
      padding-top: 8px;
    }

    .status-indicator {
      padding: 7px 12px;
    }

    .status-text {
      font-size: 11px;
    }


  }

  @media (max-width: 700px) {
    .pairing-columns {
      grid-template-columns: 1fr;
    }
  }



  /* Dashboard Core (Sidebar & Content) */
  .dashboard {
    display: flex;
    width: 100%;
    height: 100%;
    background: transparent;
  }

  .sidebar {
    width: 220px;
    background: rgba(11, 12, 16, 0.6);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-right: 1px solid rgba(255, 255, 255, 0.05);
    padding: 24px 16px;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
    z-index: 10;
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
    background-color: rgba(59, 91, 255, 0.1);
    color: #A4B4FF;
    border: 1px solid rgba(59, 91, 255, 0.25);
    box-shadow: 0 2px 10px rgba(59, 91, 255, 0.15);
  }

  .logo-mark svg {
    width: 20px;
    height: 20px;
  }

  .logo-area h3 {
    margin: 0;
    font-weight: 700;
    color: #E3E3E6;
    letter-spacing: -0.3px;
  }

  .nav-links {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .nav-btn {
    display: flex;
    align-items: center;
    gap: 12px;
    background: none;
    border: 1px solid transparent;
    outline: none;
    padding: 12px 16px;
    border-radius: 12px;
    color: #8C8E96;
    text-align: left;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  }

  .nav-icon {
    width: 18px;
    height: 18px;
    flex: 0 0 18px;
  }

  .nav-btn:hover {
    background-color: rgba(255, 255, 255, 0.04);
    color: #E3E3E6;
  }

  .nav-btn.active {
    background: rgba(59, 91, 255, 0.1);
    border: 1px solid rgba(59, 91, 255, 0.2);
    color: #A4B4FF;
    font-weight: 600;
  }

  .content-area {
    flex: 1;
    padding: 32px;
    overflow-y: auto;
    box-sizing: border-box;
    background: transparent;
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
    font-weight: 700;
    color: #E3E3E6;
    letter-spacing: -0.5px;
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
    filter: drop-shadow(0 2px 8px rgba(164, 180, 255, 0.3));
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
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 20px;
  }

  .media-card {
    background: rgba(255, 255, 255, 0.02);
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 16px;
    overflow: hidden;
    cursor: pointer;
    transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
    padding: 0;
    text-align: left;
    display: flex;
    flex-direction: column;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  }

  .media-card:hover {
    transform: scale(1.02) translateY(-2px);
    border-color: rgba(164, 180, 255, 0.2);
    box-shadow: 
      0 12px 24px rgba(0, 0, 0, 0.3),
      0 0 15px rgba(59, 91, 255, 0.1);
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
    transition: transform 0.5s ease;
  }

  .media-card:hover .media-preview {
    transform: scale(1.05);
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
    background-color: rgba(59, 91, 255, 0.2);
    border: 1px solid rgba(59, 91, 255, 0.3);
    color: #DEE0FF;
    padding: 2px 6px;
    border-radius: 4px;
    letter-spacing: 0.5px;
  }

  .media-info {
    padding: 12px 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid rgba(255, 255, 255, 0.05);
    background-color: rgba(0, 0, 0, 0.15);
  }

  .media-kind {
    font-size: 9px;
    font-weight: 700;
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
  .settings-tabs {
    width: min(760px, 100%);
    min-height: 0;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .tab-strip {
    display: flex;
    gap: 8px;
  }

  .tab-strip button {
    flex: 1;
  }

  .tab-panel {
    min-height: 0;
    flex: 1;
  }

  .settings-form {
    max-width: 760px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .inline-input {
    display: flex;
    gap: 8px;
  }

  .inline-input input {
    min-width: 0;
    flex: 1;
  }

  .settings-note {
    color: #8C8E96;
    font-size: 12px;
    line-height: 1.5;
    margin: 0;
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
    background-color: rgba(255, 255, 255, 0.02);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 10px;
    padding: 10px 14px;
    color: #E3E3E6;
    font-size: 14px;
    outline: none;
    transition: all 0.2s ease;
  }

  .form-group input[type="text"]:hover,
  .form-group input[type="number"]:hover {
    border-color: rgba(255, 255, 255, 0.16);
    background-color: rgba(255, 255, 255, 0.03);
  }

  .form-group input[type="text"]:focus,
  .form-group input[type="number"]:focus {
    border-color: #3B5BFF;
    background-color: rgba(255, 255, 255, 0.04);
    box-shadow: 0 0 10px rgba(59, 91, 255, 0.15);
  }

  .form-group input[type="text"]:focus:hover,
  .form-group input[type="number"]:focus:hover {
    border-color: #3B5BFF;
    background-color: rgba(255, 255, 255, 0.04);
  }

  .form-group select {
    background-color: rgba(255, 255, 255, 0.02);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 10px;
    padding: 10px 36px 10px 14px;
    color: #E3E3E6;
    font-size: 14px;
    outline: none;
    transition: all 0.2s ease;
    appearance: none;
    -webkit-appearance: none;
    -moz-appearance: none;
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%238C8E96' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
    background-repeat: no-repeat;
    background-position: right 14px center;
    background-size: 16px;
    cursor: pointer;
    width: 100%;
    box-sizing: border-box;
  }

  .form-group select:hover {
    border-color: rgba(255, 255, 255, 0.16);
    background-color: rgba(255, 255, 255, 0.03);
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%23A4B4FF' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
  }

  .form-group select:focus {
    border-color: #3B5BFF;
    background-color: rgba(255, 255, 255, 0.04);
    box-shadow: 0 0 10px rgba(59, 91, 255, 0.15);
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%233B5BFF' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
  }

  .form-group select:focus:hover {
    border-color: #3B5BFF;
    background-color: rgba(255, 255, 255, 0.04);
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%233B5BFF' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
  }

  .form-group select option {
    background-color: #0d0e12;
    color: #E3E3E6;
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
    accent-color: #3B5BFF;
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
    transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
    margin-top: 12px;
    box-shadow: 0 4px 12px rgba(59, 91, 255, 0.25);
  }

  .submit-btn:hover {
    background-color: #5C77FF;
    transform: translateY(-1px);
    box-shadow: 0 6px 16px rgba(59, 91, 255, 0.35);
  }

  .submit-btn:active {
    transform: translateY(0) scale(0.98);
  }

  .save-status-container {
    min-height: 24px;
    display: flex;
    align-items: center;
    justify-content: flex-start;
    margin-top: 4px;
  }

  .save-status-message {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    font-weight: 500;
    transform-origin: left center;
  }

  .save-status-message.saving {
    color: #8C8E96;
  }

  .save-status-message.success {
    color: #3BFF8A;
  }

  .save-status-message.error {
    color: #FF5C5C;
  }

  .status-icon-check {
    width: 14px;
    height: 14px;
    color: #3BFF8A;
    flex-shrink: 0;
  }

  .status-icon-error {
    width: 14px;
    height: 14px;
    color: #FF5C5C;
    flex-shrink: 0;
  }

  .spinner-small {
    width: 12px;
    height: 12px;
    border: 2px solid rgba(255, 255, 255, 0.1);
    border-top: 2px solid #3B5BFF;
    border-radius: 50%;
    animation: spin-status 0.8s linear infinite;
    flex-shrink: 0;
  }

  @keyframes spin-status {
    0% { transform: rotate(0deg); }
    100% { transform: rotate(360deg); }
  }

  @media (max-width: 768px) {
    .sidebar {
      width: 64px;
      padding: 24px 8px;
      align-items: center;
    }
    .logo-area h3, .nav-btn span {
      display: none;
    }
    .logo-area {
      padding-left: 0;
      justify-content: center;
      margin-bottom: 24px;
    }
    .logo-mark {
      width: 32px;
      height: 32px;
    }
    .nav-btn {
      padding: 12px;
      justify-content: center;
    }
    .content-area {
      padding: 20px;
    }
    .sidebar-devices-container {
      display: none;
    }
  }

  /* Confirmation Modal Custom CSS */
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(12px);
    z-index: 150;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
  }

  .confirm-modal-card {
    background: rgba(13, 14, 18, 0.75);
    backdrop-filter: blur(30px) saturate(180%);
    -webkit-backdrop-filter: blur(30px) saturate(180%);
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow: 
      0 24px 60px rgba(0, 0, 0, 0.6),
      0 0 40px rgba(59, 91, 255, 0.05),
      inset 0 1px 1px rgba(255, 255, 255, 0.05);
    border-radius: 24px;
    padding: 32px;
    max-width: 420px;
    width: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    box-sizing: border-box;
  }

  .modal-warning-icon {
    width: 56px;
    height: 56px;
    background: rgba(255, 92, 92, 0.1);
    color: #FF5C5C;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 20px;
    box-shadow: 0 0 20px rgba(255, 92, 92, 0.15);
  }

  .modal-warning-icon svg {
    width: 28px;
    height: 28px;
  }

  .modal-title {
    font-size: 20px;
    font-weight: 700;
    color: #FFFFFF;
    margin: 0 0 12px 0;
    letter-spacing: -0.4px;
  }

  .modal-message {
    font-size: 14px;
    color: #8C8E96;
    line-height: 1.5;
    margin: 0 0 28px 0;
  }

  .modal-actions {
    display: flex;
    gap: 12px;
    width: 100%;
  }

  .modal-actions button {
    flex: 1;
    height: 42px;
    border-radius: 12px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s;
  }

  .modal-actions .secondary-btn {
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.08);
    color: #C8CAD2;
  }

  .modal-actions .secondary-btn:hover {
    background: rgba(255, 255, 255, 0.06);
    border-color: rgba(255, 255, 255, 0.16);
    color: #FFFFFF;
  }

  .modal-actions .danger-btn {
    background: #FF5C5C;
    border: none;
    color: #FFFFFF;
    box-shadow: 0 4px 12px rgba(255, 92, 92, 0.25);
  }

  .modal-actions .danger-btn:hover {
    background: #FF7373;
    transform: translateY(-1px);
    box-shadow: 0 6px 16px rgba(255, 92, 92, 0.35);
  }

  .modal-actions .danger-btn:active {
    transform: translateY(0);
  }

  /* Settings Area Custom CSS */
  .settings-content {
    width: 100%;
    max-width: 680px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
  }

  .settings-panel-content {
    background: rgba(13, 14, 18, 0.45);
    backdrop-filter: blur(24px) saturate(150%);
    -webkit-backdrop-filter: blur(24px) saturate(150%);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 28px;
    padding: 32px;
    box-shadow: 
      0 4px 30px rgba(0, 0, 0, 0.3),
      inset 0 1px 1px rgba(255, 255, 255, 0.03),
      0 24px 48px rgba(0, 0, 0, 0.4);
    box-sizing: border-box;
  }

  .settings-tabs {
    display: flex;
    flex-direction: column;
    gap: 24px;
    width: 100%;
  }

  .tab-strip {
    display: flex;
    gap: 8px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
    padding-bottom: 12px;
  }

  .tab-strip button {
    border: 1px solid transparent;
    background: transparent;
    color: #8C8E96;
    font-size: 14px;
    font-weight: 600;
    padding: 8px 16px;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
  }

  .tab-strip button:hover {
    color: #FFFFFF;
    background: rgba(255, 255, 255, 0.04);
  }

  .tab-strip button.active {
    color: #A4B4FF;
    background: rgba(59, 91, 255, 0.1);
    border: 1px solid rgba(59, 91, 255, 0.2);
  }

  .settings-form {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .settings-note {
    font-size: 12px;
    color: #8C8E96;
    line-height: 1.5;
    margin: 4px 0 0 0;
  }

  .inline-input {
    display: flex;
    gap: 12px;
  }

  .inline-input input {
    flex: 1;
  }

  /* Settings Trigger Button Glow & Spin effect */
  .settings-trigger-btn {
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.02);
    position: relative;
    overflow: hidden;
  }
  .settings-trigger-btn:hover {
    border-color: rgba(59, 91, 255, 0.4);
    background: rgba(59, 91, 255, 0.06);
    box-shadow: 0 0 12px rgba(59, 91, 255, 0.15);
  }
  .settings-trigger-btn:hover .nav-icon {
    transform: rotate(45deg);
    color: #A4B4FF;
  }

  .nav-icon {
    width: 18px;
    height: 18px;
    flex: 0 0 18px;
    transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1), color 0.2s ease;
  }

  /* Sidebar layout adjustment for scrolling list */
  .sidebar {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    height: 100%;
  }

  /* Sidebar Devices styles */
  .sidebar-devices-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-height: 0; /* Important for flex child overflow */
    margin-top: 20px;
    border-top: 1px solid rgba(255, 255, 255, 0.05);
    padding-top: 16px;
  }

  .sidebar-devices-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1px;
    color: #8C8E96;
    margin-bottom: 12px;
    padding: 0 4px;
  }

  .device-count-badge {
    background: rgba(59, 91, 255, 0.15);
    color: #A4B4FF;
    padding: 1px 6px;
    border-radius: 8px;
    font-size: 9px;
  }

  .sidebar-devices-list {
    flex: 1;
    overflow-y: auto; /* Scrollbar only on overflow */
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-right: 4px;
    scrollbar-width: thin; /* Thin scrollbar for Firefox */
  }

  /* Custom scrollbar for WebKit */
  .sidebar-devices-list::-webkit-scrollbar {
    width: 4px;
  }
  .sidebar-devices-list::-webkit-scrollbar-track {
    background: transparent;
  }
  .sidebar-devices-list::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.05);
    border-radius: 2px;
  }
  .sidebar-devices-list::-webkit-scrollbar-thumb:hover {
    background: rgba(255, 255, 255, 0.12);
  }

  .sidebar-device-card {
    background: rgba(255, 255, 255, 0.02);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 12px;
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    transition: all 0.2s ease;
  }

  .sidebar-device-card:hover {
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(91, 123, 255, 0.15);
  }

  .sidebar-device-main {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }

  .sidebar-device-details {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .sidebar-device-alias-input {
    width: 100%;
    border: 1px solid transparent;
    background: transparent;
    color: #E3E3E6;
    font-size: 12px;
    font-weight: 600;
    padding: 1px 2px;
    margin: 0;
    outline: none;
    border-radius: 4px;
    text-overflow: ellipsis;
  }

  .sidebar-device-alias-input:hover {
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(255, 255, 255, 0.08);
  }

  .sidebar-device-alias-input:focus {
    background: rgba(0, 0, 0, 0.25);
    border-color: rgba(59, 91, 255, 0.4);
  }

  .sidebar-device-os-row {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .sidebar-device-os {
    font-size: 9px;
    font-weight: 700;
    color: #8C8E96;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }

  .sidebar-device-ip-info {
    font-size: 9px;
    color: #A4B4FF;
    font-family: 'JetBrains Mono', monospace;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .sidebar-status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background-color: #FF5C5C;
    flex-shrink: 0;
  }

  .sidebar-status-dot.online {
    background-color: #3BFF8A;
    box-shadow: 0 0 6px #3BFF8A;
  }

  .sidebar-device-controls {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-top: 1px solid rgba(255, 255, 255, 0.04);
    padding-top: 6px;
  }

  .sidebar-mcp-container {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .compact-mcp-label {
    font-size: 9px;
    font-weight: 600;
    color: #8C8E96;
  }

  .sidebar-unpair-btn {
    background: none;
    border: none;
    color: #FF8F8F;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 3px;
    border-radius: 4px;
    transition: all 0.2s;
  }

  .sidebar-unpair-btn:hover {
    background: rgba(255, 92, 92, 0.08);
    color: #FF5C5C;
  }

  .sidebar-unpair-btn svg {
    width: 11px;
    height: 11px;
  }

  .sidebar-empty-devices {
    font-size: 11px;
    color: #5C5D64;
    text-align: center;
    padding: 16px 8px;
    border: 1px dashed rgba(255, 255, 255, 0.03);
    border-radius: 8px;
  }

  /* Compact switch toggle */
  .compact-switch {
    position: relative;
    display: inline-block;
    width: 24px;
    height: 14px;
  }

  .compact-switch input {
    opacity: 0;
    width: 0;
    height: 0;
  }

  .compact-slider {
    position: absolute;
    cursor: pointer;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-color: rgba(255, 255, 255, 0.06);
    border: 1px solid rgba(255, 255, 255, 0.04);
    transition: .2s ease;
  }

  .compact-slider:before {
    position: absolute;
    content: "";
    height: 8px;
    width: 8px;
    left: 2px;
    bottom: 2px;
    background-color: #8C8E96;
    transition: .2s ease;
  }

  .compact-switch input:checked + .compact-slider {
    background-color: rgba(59, 91, 255, 0.2);
    border-color: rgba(59, 91, 255, 0.3);
  }

  .compact-switch input:checked + .compact-slider:before {
    transform: translateX(10px);
    background-color: #3B5BFF;
  }

  .compact-slider.round {
    border-radius: 10px;
  }

  .compact-slider.round:before {
    border-radius: 50%;
  }

  /* Manual IP QR pairing input form styles */
  .manual-ip-section {
    width: 100%;
    margin-bottom: 20px;
  }

  .manual-ip-form {
    display: flex;
    flex-direction: column;
    gap: 8px;
    width: 100%;
  }

  .manual-ip-input-container {
    display: flex;
    gap: 8px;
    width: 100%;
  }

  .manual-ip-input {
    flex: 1;
    min-width: 0;
    background-color: rgba(0, 0, 0, 0.25);
    border: 1px solid rgba(255, 255, 255, 0.06);
    border-radius: 10px;
    padding: 8px 12px;
    color: #E3E3E6;
    font-size: 12px;
    outline: none;
    transition: all 0.2s ease;
  }

  .manual-ip-input:focus {
    border-color: #3B5BFF;
    background-color: rgba(0, 0, 0, 0.35);
  }

  .manual-ip-btn {
    background-color: #3B5BFF;
    color: #FFFFFF;
    border: none;
    border-radius: 10px;
    padding: 8px 16px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s ease;
  }

  .manual-ip-btn:hover {
    background-color: #5C77FF;
  }
</style>
