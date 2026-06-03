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

const pairingContent = declarationsFor(".pairing-content", compactMedia);
assertDeclaration(
  pairingContent,
  "max-height",
  "calc(100vh - 48px)",
  "Pairing content must be height-constrained for the default 800x600 Tauri window."
);

const pairingColumns = declarationsFor(".pairing-columns", compactMedia);
assertDeclaration(
  pairingColumns,
  "display",
  "grid",
  "Default-window pairing layout should switch to a compact grid instead of overflowing two wide cards."
);
assertDeclaration(
  pairingColumns,
  "grid-template-columns",
  "minmax(250px, 1.05fr) minmax(220px, 0.95fr)",
  "Default-window pairing columns should preserve the two-panel compact layout."
);
assertDeclaration(
  pairingColumns,
  "height",
  "100%",
  "Default-window pairing columns should fill the available height instead of growing past the window."
);
assertDeclaration(
  pairingColumns,
  "max-width",
  "none",
  "Default-window pairing columns should not inherit the wider desktop max width."
);

const pairingPanel = declarationsFor(".pairing-panel", compactMedia);
assertDeclaration(
  pairingPanel,
  "overflow-y",
  "auto",
  "Default-window pairing panels should scroll internally when real pairing details are present."
);
assertDeclaration(
  pairingPanel,
  "scrollbar-gutter",
  "stable",
  "Default-window pairing panels should reserve scrollbar space to avoid layout shift."
);

const qrPolaroid = declarationsFor(".qr-polaroid", compactMedia);
assertDeclaration(
  qrPolaroid,
  "width",
  "168px",
  "Default-window pairing layout should use a smaller QR card."
);

const narrowColumns = declarationsFor(".pairing-columns", narrowMedia);
assertDeclaration(
  narrowColumns,
  "grid-template-columns",
  "1fr",
  "Very narrow pairing layout should collapse to one column."
);
