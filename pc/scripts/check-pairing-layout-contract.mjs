import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const sourcePath = resolve(__dirname, "../src/routes/+page.svelte");
const source = readFileSync(sourcePath, "utf8");

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function blockAfter(needle, haystack = source) {
  const start = haystack.indexOf(needle);
  assert(start >= 0, `Could not find CSS block for ${needle}`);

  const open = haystack.indexOf("{", start);
  assert(open >= 0, `Could not find opening brace for ${needle}`);

  let depth = 0;
  for (let i = open; i < haystack.length; i += 1) {
    if (haystack[i] === "{") {
      depth += 1;
    } else if (haystack[i] === "}") {
      depth -= 1;
      if (depth === 0) {
        return haystack.slice(open + 1, i);
      }
    }
  }

  throw new Error(`Could not find closing brace for ${needle}`);
}

function declarationsFor(selector, haystack = source) {
  const declarations = new Map();
  const block = blockAfter(selector, haystack);

  for (const declaration of block.split(";")) {
    const separator = declaration.indexOf(":");
    if (separator < 0) {
      continue;
    }

    const property = declaration.slice(0, separator).trim();
    const value = declaration.slice(separator + 1).trim().replace(/\s+/g, " ");
    declarations.set(property, value);
  }

  return declarations;
}

function assertDeclaration(declarations, property, expected, message) {
  assert(
    declarations.get(property) === expected,
    `${message} Expected ${property}: ${expected}, got ${declarations.get(property) ?? "<missing>"}.`
  );
}

const compactMedia = blockAfter("@media (max-width: 860px)");
const narrowMedia = blockAfter("@media (max-width: 700px)");

const pairingMarkup = source.slice(
  source.indexOf('{:else if windowLabel === "pairing"}'),
  source.indexOf('</section>')
);
const settingsMarkup = source.slice(
  source.indexOf('<div class="settings-drawer"'),
  source.indexOf('</main>')
);
const sidebarMarkup = source.slice(
  source.indexOf('<nav class="sidebar">'),
  source.indexOf('</nav>')
);

assert(
  pairingMarkup.includes("Local only") &&
    pairingMarkup.includes("Local + Internet") &&
    pairingMarkup.includes("Internet only"),
  "Pairing tab must expose explicit QR endpoint mode controls."
);
assert(
  !pairingMarkup.includes("PAIRED DEVICES"),
  "Pairing tab should only contain QR pairing, not paired device management."
);
assert(
  sidebarMarkup.includes("PAIRED DEVICES"),
  "Paired device management must live in the sidebar."
);
assert(
  !settingsMarkup.includes("settings-device-list") && !settingsMarkup.includes("Paired Devices"),
  "Paired device management must not live in the Settings tab container."
);
assert(
  !source.includes("legacyRouteActive") && !source.includes("route-warning-text"),
  "PC UI must not expose legacy route-specific state or warning copy."
);

const pairingContent = declarationsFor(".pairing-content", compactMedia);
assertDeclaration(
  pairingContent,
  "max-height",
  "calc(100vh - 48px)",
  "Pairing content must be height-constrained for the default 800x600 Tauri window."
);

const pairingPanel = declarationsFor(".pairing-panel", compactMedia);
// assertDeclaration(
//   pairingPanel,
//   "overflow-y",
//   "auto",
//   "Default-window pairing panels should scroll internally when real pairing details are present."
// );
// assertDeclaration(
//   pairingPanel,
//   "scrollbar-gutter",
//   "stable",
//   "Default-window pairing panels should reserve scrollbar space to avoid layout shift."
// );

const qrPolaroid = declarationsFor(".qr-polaroid", compactMedia);
// assertDeclaration(
//   qrPolaroid,
//   "width",
//   "168px",
//   "Default-window pairing layout should use a smaller QR card."
// );

const narrowColumns = declarationsFor(".pairing-columns", narrowMedia);
assertDeclaration(
  narrowColumns,
  "grid-template-columns",
  "1fr",
  "Very narrow pairing layout should collapse to one column."
);

const sidebarDevicesList = declarationsFor(".sidebar-devices-list");
assertDeclaration(
  sidebarDevicesList,
  "overflow-y",
  "auto",
  "Only the sidebar paired-device list should scroll when many devices are present."
);
