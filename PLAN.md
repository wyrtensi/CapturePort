# CapturePort — План реализации

> **Платформа:** Android → PC (Windows / macOS / Linux) с MCP-мостом для AI-агентов
> **Лицензия:** MIT (без монетизации)
> **GitHub:** https://github.com/wyrtensi/CapturePort
> **Локальная папка:** `D:\CapturePort`
> **Дата плана:** 2026-06-02
> **Статус:** готов к реализации

---

## 0. TL;DR (одна страница)

**CapturePort** — кросс-платформенный медиа-мост. Android-приложение делает фото/видео → отправляет по локальной сети (WiFi) на выбранный компьютер → кладёт в системный буфер обмена. На ПК параллельно работает MCP-сервер (Model Context Protocol), через который AI-агенты (Claude Desktop, opencode, Cursor и любые другие MCP-клиенты) могут запрашивать снимки и короткие видео с камеры телефона в реальном времени.

**Ключевые решения:**

| # | Решение | Обоснование |
|---|---|---|
| Стек ПК | Tauri 2 + Rust | 3–8 МБ инсталлятор, 30–60 МБ RAM vs 150–300 у Electron |
| Стек Android | Kotlin 2.2.20 + Jetpack Compose + CameraX 1.6.1 | Compose-native API `LifecycleCameraController` — единый путь для фото, видео и QR-сканера |
| Транспорт | Один двунаправленный WebSocket | JSON для контроля, бинарь для медиа; request_id correlation, idempotency keys |
| Сопряжение | QR-код | Камера телефона уже есть, не нужно вводить PIN руками; ed25519 challenge-response |
| mDNS | `mdns-sd` 0.20 (Rust) + `NSDManager` (Android) | Без сторонних тяжёлых зависимостей |
| MCP | `rmcp` 1.7, stdio + Streamable HTTP | Один бинарь — два транспорта; работает с любым MCP-клиентом |
| Clipboard | `arboard` (фото) + платформенный код (видео: `CF_HDROP`, `NSPasteboardTypeFileURL`, `text/uri-list`) | OS clipboard не имеет нативного «видео» типа — кладём file URI |
| minSdk | API 29 (Android 10) | ~95% активных устройств в 2026 |
| Дизайн | Material 3 с кастомной палитрой, единый визуальный язык | Полировка с первого коммита |
| Видео | CameraX `Recorder` 720p H.264 ~1.5 Mbps, чанки 256 КБ по WS | ~2 МБ / 10 с клип |

---

## 1. Архитектура

```
                        LAN (WiFi, mDNS 5353 + WS 7878)
  ┌────────────────────┐                              ┌──────────────────────────────────┐
  │  Android Phone     │                              │  PC Tray App (Tauri 2 / Rust)    │
  │                    │                              │                                  │
  │  Kotlin 2.2.20     │                              │  ┌──────────────────────────┐    │
  │  Compose           │   mDNS discovery             │  │ axum 0.8 router          │    │
  │  CameraX 1.6.1     │  _captureport._tcp.local.   │  │  /ws     (телефон)       │    │
  │  NSDManager        │ ◄───────────────────────────►│  │  /mcp    (rmcp HTTP)     │    │
  │  ML Kit barcode    │                              │  │  /health                 │    │
  │  OkHttp 5.3 WS     │   WS :7878                   │  └─────────┬────────────────┘    │
  │  Proto DataStore   │ ────────────────────────────►│            │                     │
  │  Android Keystore  │   JSON control + binary media│  ┌─────────▼────────────────┐    │
  │  ed25519 keys      │ ◄────────────────────────────│  │ Shared state             │    │
  │                    │   capture_photo commands     │  │ Arc<Mutex<LatestFrame>>  │    │
  └────────────────────┘   photo results              │  │ Arc<RwLock<Devices>>     │    │
                                                      │  │ Arc<Mutex<PendingReq>>   │    │
                                                      │  │   HashMap<ReqId, oneshot>│    │
                                                      │  └─────────┬────────────────┘    │
                                                      │            │                     │
                                                      │  ┌─────────▼────────────────┐    │
                                                      │  │ ClipboardSink trait      │    │
                                                      │  │ image:  arboard          │    │
                                                      │  │ video:  CF_HDROP/NSPb/   │────┼─► System clipboard
                                                      │  │         text/uri-list    │    │   (Win/macOS/Linux)
                                                      │  └─────────────────────────┘    │
                                                      │  ┌─────────────────────────┐    │
                                                      │  │ rmcp 1.7 (HTTP)         │────┼─▶ opencode / Cursor / etc.
                                                      │  └─────────────────────────┘    │
                                                      │  ┌─────────────────────────┐    │
                                                      │  │ captureport-mcp.exe     │────┼─▶ Claude Desktop
                                                      │  │ (stdio → HTTP proxy)    │    │   (stdin/stdout)
                                                      │  └─────────────────────────┘    │
                                                      │  mDNS advertiser                 │
                                                      │  Tray + single-instance + autostart
                                                      └──────────────────────────────────┘
```

**Ключевые свойства:**

- ПК — long-lived server, телефон — клиент. mDNS нужен только для первоначального обнаружения; после сопряжения (QR) соединение идёт по `host:port`.
- Один WS = и media push, и MCP-команды. Корреляция через `HashMap<ReqId, oneshot::Sender>` + reaper с 12-секундным таймаутом.
- mDNS — fallback discovery; QR — primary (быстрее и работает даже когда mDNS заблокирован роутером).

---

## 2. Структура репозитория

```
D:\CapturePort\
├── .gitignore
├── .gitattributes
├── .editorconfig
├── LICENSE                                  # MIT
├── README.md
├── CHANGELOG.md
│
├── docs/
│   ├── architecture.md                      # ADR (architecture decision records)
│   ├── protocol.md                          # wire-протокол (полное описание)
│   ├── pairing.md                           # QR-flow
│   ├── mcp-api.md                           # tool/resource API
│   ├── threat-model.md                      # MITM-сценарии, ed25519
│   ├── test-plan.md                         # ручной smoke-test чеклист
│   ├── design/
│   │   ├── tokens.md                        # цвета, типографика, отступы
│   │   ├── icon.md                          # концепция иконки
│   │   └── motion.md                        # анимации
│   └── adr/
│       ├── 0001-tauri-2.md
│       ├── 0002-bidi-ws.md
│       ├── 0003-rmcp.md
│       ├── 0004-qr-pairing.md
│       └── 0005-camerax-compose.md
│
├── protocol/                                # общие схемы (синхронизируются вручную)
│   ├── message.schema.json                  # JSON Schema для контрольных кадров
│   ├── binary-header.schema                 # 32-байтный заголовок
│   └── examples/
│       ├── 01-hello.json
│       ├── 02-hello-ack.json
│       ├── 03-photo-meta.json
│       ├── 04-video-start.json
│       ├── 05-cmd-capture-photo.json
│       └── 06-cmd-result.json
│
├── pc/                                      # Tauri 2 приложение
│   ├── README.md
│   ├── src-tauri/
│   │   ├── Cargo.toml
│   │   ├── Cargo.lock                       # коммитим (это бинарь)
│   │   ├── tauri.conf.json
│   │   ├── build.rs
│   │   ├── capabilities/default.json
│   │   ├── icons/                           # 32x32, 128x128, 128x128@2x, .icns, .ico
│   │   └── src/
│   │       ├── main.rs                      # entry; парсит --mcp-stdio
│   │       ├── app.rs                       # Tauri Builder + plugin init
│   │       ├── tray.rs                      # tray-меню, иконки, события
│   │       ├── state.rs                     # AppState (Arc, broadcast, RwLock)
│   │       ├── pairing/
│   │       │   ├── mod.rs                   # QR-генератор + UI-окно
│   │       │   ├── qr.rs                    # генерация QR (use `qrcode` crate)
│   │       │   └── keys.rs                  # ed25519 в OS keystore
│   │       ├── ws/
│   │       │   ├── mod.rs                   # axum router, /ws handler
│   │       │   ├── envelope.rs              # JSON-конверт + бинарь-хедер
│   │       │   ├── handler.rs               # per-connection state
│   │       │   ├── correlation.rs           # HashMap<ReqId, oneshot> + reaper
│   │       │   └── reconnect.rs             # exp backoff
│   │       ├── mdns.rs                      # mdns-sd advertiser
│   │       ├── clipboard/
│   │       │   ├── mod.rs                   # trait ClipboardSink
│   │       │   ├── image.rs                 # arboard set_image
│   │       │   ├── windows.rs               # clipboard-win (CF_HDROP + CF_UNICODETEXT)
│   │       │   ├── macos.rs                 # objc2 (NSPasteboardTypeFileURL)
│   │       │   └── linux.rs                 # wl-copy / xclip shell-out
│   │       ├── media/
│   │       │   ├── mod.rs                   # in-memory cache последних 20
│   │       │   ├── image.rs                 # JPEG → arboard
│   │       │   └── video.rs                 # MP4 → file + ClipboardSink
│   │       ├── mcp/
│   │       │   ├── mod.rs                   # ServerHandler impl
│   │       │   ├── tools.rs                 # capture_photo, list_devices, ...
│   │       │   ├── resources.rs             # camera://latest
│   │       │   ├── http_server.rs           # Streamable HTTP на /mcp
│   │       │   └── stdio_proxy.rs           # бинарь captureport-mcp
│   │       ├── bin/
│   │       │   ├── captureport.rs           # GUI (tray)
│   │       │   └── captureport-mcp.rs       # stdio
│   │       └── tests/                       # unit-тесты
│   │           ├── envelope.rs
│   │           ├── correlation.rs
│   │           └── pairing.rs
│   ├── ui/                                  # минимальный Svelte-фронт
│   │   ├── package.json
│   │   ├── svelte.config.js
│   │   ├── vite.config.ts
│   │   ├── index.html
│   │   └── src/
│   │       ├── main.ts
│   │       ├── App.svelte
│   │       ├── lib/
│   │       │   ├── PairingWindow.svelte     # QR + fingerprint
│   │       │   ├── ActivityWindow.svelte    # последние 20 медиа
│   │       │   └── SettingsWindow.svelte
│   │       └── theme.css
│   └── tests/                               # интеграционные тесты
│       ├── envelope.rs
│       ├── correlation.rs
│       └── pairing.rs
│
└── android/                                 # Android-приложение
    ├── README.md
    ├── .gitignore
    ├── settings.gradle.kts
    ├── build.gradle.kts                     # root
    ├── gradle.properties
    ├── gradle/
    │   ├── wrapper/gradle-wrapper.properties
    │   └── libs.versions.toml
    ├── gradlew, gradlew.bat
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        ├── debug.keystore                   # стандартный debug (коммитим)
        └── src/main/
            ├── AndroidManifest.xml
            ├── proto/
            │   └── paired_devices.proto
            ├── kotlin/dev/captureport/app/
            │   ├── CapturePortApp.kt
            │   ├── MainActivity.kt
            │   ├── core/
            │   │   ├── di/AppContainer.kt
            │   │   ├── crypto/Ed25519KeyManager.kt
            │   │   └── permissions/Permissions.kt
            │   ├── data/
            │   │   └── datastore/PairedDevicesRepository.kt
            │   ├── network/
            │   │   ├── WsClient.kt
            │   │   ├── EnvelopeCodec.kt
            │   │   └── Reconnector.kt
            │   ├── discovery/
            │   │   └── NsdDiscovery.kt
            │   ├── camera/
            │   │   ├── CameraController.kt
            │   │   ├── PhotoCapture.kt
            │   │   ├── VideoRecorder.kt
            │   │   └── ImageProcessor.kt
            │   ├── qr/
            │   │   ├── QrScanner.kt         # CameraX + ML Kit
            │   │   └── QrUrlParser.kt
            │   ├── pairing/
            │   │   ├── PairingViewModel.kt
            │   │   └── PairingScreen.kt
            │   ├── receivers/
            │   │   ├── ReceiversScreen.kt
            │   │   ├── ReceiversViewModel.kt
            │   │   └── ReceiverRow.kt
            │   ├── transfer/
            │   │   └── TransferService.kt
            │   ├── navigation/NavGraph.kt
            │   └── ui/theme/
            │       ├── Color.kt
            │       ├── Type.kt
            │       └── Theme.kt
            └── res/
                ├── values/strings.xml
                ├── values-ru/strings.xml
                ├── values-en/strings.xml
                ├── drawable/ic_launcher_foreground.xml
                ├── mipmap-anydpi-v26/
                └── xml/network_security_config.xml
```

---

## 3. Wire-протокол

### 3.1 Транспорт

- Один WebSocket на TCP-порту 7878.
- Текстовые фреймы = JSON-контроль.
- Бинарные фреймы = сырые JPEG-байты или MP4-чанки с 32-байтным заголовком.

### 3.2 JSON-конверт (текстовые фреймы)

```jsonc
{
  "v": 1,                            // версия протокола
  "t": "req" | "resp" | "notify",    // тип сообщения
  "id": "01HMRX...",                 // ULID; req↔resp корреляция
  "method": "capture_photo",         // только для t=req
  "params": { ... },                 // только для t=req
  "result": { ... },                 // только для t=resp (ok)
  "error": { "code": 12, "message": "..." },  // только для t=resp (err)
  "ts": 1749000000000,               // ms sender clock
  "idem": "01HMSX..."                // idempotency key для retry
}
```

**Коды ошибок:**

| Код | Значение |
|---|---|
| 1 | UNKNOWN_METHOD |
| 2 | INVALID_PARAMS |
| 3 | UNAUTHORIZED |
| 4 | TIMEOUT |
| 5 | DEVICE_OFFLINE |
| 6 | CAPTURE_FAILED |
| 7 | INTERNAL_ERROR |
| 12 | REQUEST_TIMEOUT (12 сек) |

### 3.3 Заголовок бинарного фрейма (32 байта, little-endian)

```
[0..8]   magic "PHRBIDG1"  (8 байт)
[8..12]  u32  stream_id     (0=photo, 1=video, 2=capture_result, 3=poster)
[12..16] u32  frame_seq     (монотонный счётчик отправителя)
[16..20] u32  flags         (0x1=last_chunk, 0x2=keyframe, ...)
[20..28] u64  total_size    (полный размер медиа-объекта)
[28..32] u32  meta_size     (длина JSON-метаданных после хедера)
[32..32+meta_size]  JSON-мета (например {"request_id":"01HMRX..."})
[32+meta_size..]    данные
```

### 3.4 Жизненный цикл соединения

```
PC                                                              Phone
│                                                               │
│──── mDNS broadcast _captureport._tcp.local. :7878 ────────►   │  UDP 5353, каждые 1с
│                                                               │
│   ─── ИЛИ: phone сканирует QR на экране PC ──                  │
│   QR содержит: ws://192.168.1.42:7878                         │
│                + pk, name, os, nonce, sig                     │
│                                                               │
│◄──── WS Upgrade (Sec-WebSocket-Protocol: captureport.v1) ────│
│──── 101 Switching Protocols ─────────────────────────────────►│
│                                                               │
│══════ PAIRING PHASE (только при первом коннекте) ════════════  │
│                                                               │
│◄──── hello { pubkey_pc, pk_sig, name, os, port } ────────────│
│────── hello { pubkey_phone, device_name } ──────────────────►│
│◄──── challenge { nonce: random 32B } ────────────────────────│
│────── signed { sig: sign(nonce_pc, phone_privkey) } ─────────►│
│◄──── paired { token, fingerprint } ──────────────────────────►│
│                                                               │
│══════ STEADY STATE (auth по токену) ══════════════════════════  │
│                                                               │
│────── notify hello { device_id, token } ────────────────────►│
│◄──── hello_ack { session_id } ───────────────────────────────│
│                                                               │
│══════ PHONE → PC: media push ════════════════════════════════  │
│                                                               │
│────── req push_photo_meta { id, ... } ──────────────────────►│
│────── binary [hdr: stream=0, jpeg_bytes] ───────────────────►│
│◄──── resp push_photo_ack { id, clipboard_ok: true } ────────│
│                                                               │
│══════ PC → PHONE: MCP capture_photo ═════════════════════════ │
│                                                               │
│◄──── req capture_photo { id:"01HMRX", timeout_ms: 12000 } ──│
│────── resp accepted { id, queued_ms: 200 } ─────────────────►│
│         (phone в фоне: Camera2.takePicture)                  │
│────── binary [hdr: stream=2, meta:{id}, jpeg_bytes] ────────►│
│────── resp done { id, result: {phash, size, w, h} } ────────►│
│                                                               │
│══════ Heartbeat (keep-alive) ════════════════════════════════  │
│  WS Ping/Pong каждые 25с (нативный, не envelope)              │
```

### 3.5 Корреляция запросов (на стороне ПК)

```rust
// state.rs
struct PendingMap {
    map: HashMap<ReqId, oneshot::Sender<Response>>,
    deadlines: BinaryHeap<DeadlineEntry>,
}

// ws/handler.rs — обработка входящего req от телефона (или собственный исходящий)
async fn handle_request(req: Request, state: AppState) {
    let (tx, rx) = oneshot::channel();
    let deadline = Instant::now() + Duration::from_millis(12_000);
    state.pending.insert(req.id.clone(), tx, deadline);

    // Forward to phone (если команда для телефона)
    send_envelope(&state.phone_tx, req).await?;

    // Await response
    match timeout(Duration::from_secs(12), rx).await {
        Ok(Ok(resp))  => /* OK */,
        Ok(Err(_))    => /* phone dropped */,
        Err(_timeout) => /* MCP tool: вернуть ошибку TIMEOUT */,
    }
}

// reaper-таск (каждую секунду)
async fn reap_expired(state: AppState) {
    let now = Instant::now();
    for (id, _deadline) in state.pending.expired(now) {
        if let Some(tx) = state.pending.remove(&id) {
            let _ = tx.send(Response::err(REQUEST_TIMEOUT));
        }
    }
}
```

### 3.6 Reconnect (на стороне телефона)

```kotlin
class Reconnector(private val scope: CoroutineScope) {
    suspend fun connectWithBackoff(): Boolean {
        var attempt = 0
        var delayMs = 500L
        while (attempt < 12) {                      // ~2 минуты максимум
            try {
                val ws = client.newWebSocket(req, listener)
                if (!listener.openGate.await(5.seconds)) {
                    attempt++; continue
                }
                auth(ws)                              // hello { device_id, token }
                if (!listener.authOkGate.await(5.seconds)) {
                    attempt++; continue
                }
                return true                            // connected
            } catch (e: Exception) {
                attempt++
                delay(delayMs + Random.nextLong(0..500))
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        return false
    }
}
```

### 3.7 Идемпотентность

- Каждый `req` имеет `idem` — ULID, детерминированно вычисленный из `(method, params)`.
- Телефон хранит `HashSet<Idem>` последних 100 обработанных запросов; при получении дубликата — повторно отправляет последний результат из кэша.
- Это спасает от двойного захвата при reconnect посреди команды.

---

## 4. QR-сопряжение

### 4.1 Первый запуск ПК

1. Генерируется ed25519 keypair.
2. Приватный ключ сохраняется в OS keystore:
   - Windows: DPAPI через `windows` crate.
   - macOS: Keychain через `security-framework` crate.
   - Linux: `secret-service` через `keyring` crate.
3. mDNS advertiser стартует.
4. WS-сервер стартует.
5. Tray-меню: «Pair new device» → открывает окно с QR.

### 4.2 Что в QR

PNG 400×400 px с URL:

```
captureport://pair
  ?v=1
  &host=192.168.1.42
  &port=7878
  &pk=base64url(ed25519_pubkey_PC)
  &name=DESKTOP-XYZ
  &os=windows
  &nonce=base64url(random_32_bytes)
  &sig=base64url(ed25519_sign(nonce, PC_privkey))
```

Под QR — текст «Покажите этот код на телефоне» и **fingerprint** — первые 8 байт `sha256(pk)`, hex, разделённые двоеточиями — для визуальной верификации (защита от MITM, если QR подменён).

### 4.3 Что делает телефон

1. Пользователь → «+ Добавить ПК» → экран QR-сканера.
2. CameraX `ImageAnalysis` + `com.google.mlkit:barcode-scanning:18.x` → декодирует QR.
3. Парсит URL, валидирует схему.
4. Генерирует свой ed25519 keypair → Android Keystore (`setUserAuthenticationRequired(false)`, `setUserPresenceRequired(false)`).
5. Подписывает `nonce` из QR своим закрытым ключом.
6. Открывает WS к `ws://host:port/`.
7. Шлёт `hello { pubkey_phone, device_name, signed_nonce, pc_pubkey }`.
8. ПК проверяет подпись ed25519; сверяет `pc_pubkey` с QR.
9. ПК генерирует session token (32 случайных байта, base64url).
10. ПК отвечает `paired { token, fingerprint_phone }`.
11. **Визуальная верификация:** на экране телефона показывается fingerprint ПК (из QR), на экране ПК — fingerprint телефона. Пользователь визуально подтверждает совпадение.
12. Телефон сохраняет `(name, host, port, token, pc_pubkey, phone_pubkey)` в Proto DataStore.
13. Готово.

### 4.4 Повторные подключения

- Без QR/PAIRING. Телефон шлёт `hello { device_id, token }`, ПК валидирует токен по `HashMap<device_id, token>` (в памяти и на диске).
- Если токен неизвестен — `auth_fail { reason: "unknown_device" }` → телефон показывает «Сопряжение устарело, отсканируйте QR заново».

### 4.5 Угрозы и контрмеры (threat-model.md)

| Угроза | Контрмера |
|---|---|
| Пассивный наблюдатель в WiFi | ed25519 challenge-response + session token; без QR-подписи соединение не устанавливается |
| Активный MITM (подменён QR) | Визуальная сверка fingerprint на обоих экранах |
| Replay-атака (переигровка nonce) | Nonce одноразовый, 30 сек TTL на стороне ПК |
| Компрометация приватного ключа телефона | Android Keystore — key никогда не покидает TEE/StrongBox |
| Компрометация приватного ключа ПК | OS keystore (DPAPI/Keychain/secret-service) — key привязан к учётной записи пользователя |

---

## 5. ПК-приёмник (Tauri 2)

### 5.1 Зависимости (Cargo.toml)

```toml
[package]
name = "captureport"
version = "0.1.0"
edition = "2021"
license = "MIT"

[dependencies]
tauri = { version = "2", features = ["tray-icon", "image-png"] }
tauri-plugin-clipboard-manager = "2"
tauri-plugin-notification       = "2"
tauri-plugin-autostart         = "2"
tauri-plugin-single-instance   = "2"
arboard                        = { version = "3", features = ["wayland-data-control"] }

# Networking
axum            = { version = "0.8", features = ["ws", "macros"] }
tokio           = { version = "1", features = ["full"] }
futures-util    = "0.3"
mdns-sd         = "0.20"

# MCP
rmcp            = { version = "1.7", features = [
    "server", "macros", "schemars",
    "transport-io",
    "transport-streamable-http-server",
    "reqwest",
] }

# Crypto
ed25519-dalek   = "2"
x25519-dalek    = "2"
chacha20poly1305 = "0.10"
keyring         = "3"

# Encoding / data
serde           = { version = "1", features = ["derive"] }
serde_json      = "1"
ulid            = "1"
base64          = "0.22"
qrcode          = "0.14"

# Platform-specific clipboard
clipboard-win   = "5"
[target.'cfg(target_os = "macos")'.dependencies]
objc2           = "0.5"
objc2-app-kit   = "0.2"
[target.'cfg(target_os = "linux")'.dependencies]
which           = "7"

# Misc
anyhow          = "1"
thiserror       = "1"
tracing         = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }

[dev-dependencies]
tokio-tungstenite = { version = "0.26", features = ["rustls-tls-webpki-roots"] }
```

### 5.2 Режимы запуска

Один бинарь `captureport.exe`, два режима через флаги:

- **по умолчанию**: Tauri tray-приложение, mDNS advertiser, axum + WS, rmcp Streamable HTTP на `/mcp`.
- **`--mcp-stdio`**: headless-режим — rmcp обслуживает stdio (для отладки или прямого запуска Claude Desktop).

Отдельный бинарь `captureport-mcp.exe` (без Tauri, ~100 LOC): запускается Claude Desktop, проксирует stdio JSON-RPC на `http://127.0.0.1:7878/mcp` через `reqwest`. Если tray не запущен — печатает ошибку в stderr и выходит.

### 5.3 Tray-меню

- Иконка: `tray-icon` (32×32 PNG), `icon_as_template(true)` на macOS.
- Левая кнопка → окно «Активность» (последние 20 медиа с превью).
- Правая кнопка → меню:
  - 📱 Pair new device…  (открывает окно с QR)
  - 📂 Open received folder (`~/Pictures/CapturePort/<дата>/`)
  - ⚙ Settings…  (имя устройства, порт, MCP вкл/выкл, автозапуск, язык)
  - ── separator ──
  - ⏻ Quit

### 5.4 ClipboardSink trait

```rust
// clipboard/mod.rs
pub trait ClipboardSink: Send + Sync {
    fn put_image(&self, rgba: &[u8], w: u32, h: u32) -> Result<()>;
    fn put_file(&self, path: &Path, poster: Option<&[u8]>) -> Result<()>;
}

#[cfg(windows)]           type PlatformSink = WindowsSink;  // clipboard-win
#[cfg(target_os = "macos")] type PlatformSink = MacosSink; // objc2
#[cfg(target_os = "linux")] type PlatformSink = LinuxSink; // wl-copy/xclip
```

| ОС | Фото | Видео |
|---|---|---|
| Windows | arboard (RGBA bitmap) | `CF_HDROP` (DROPFILES + пути) + `CF_UNICODETEXT` + опц. `CF_BITMAP` (постер) |
| macOS | arboard | `NSPasteboardTypeFileURL` + legacy `NSFilenamesPboardType` + `public.utf8-plain-text` |
| Linux | arboard | shell-out к `wl-copy -t text/uri-list` (fallback `xclip -selection clipboard -t text/uri-list`) |

### 5.5 Связка WS ↔ MCP

Когда MCP-инструмент `capture_photo` вызван агентом:

1. `tools.rs::capture_photo` вызывает `ws::request_capture(device_id, timeout_ms=12s)`.
2. `ws::request_capture` отправляет `req capture_photo { id, ... }` по WS телефону, ставит в `PendingMap` oneshot.
3. Телефон делает снимок, шлёт `binary [stream=2, meta={id}, jpeg_bytes]` + `resp done { id, ... }`.
4. Reaper/oneshot срабатывает → возвращает JPEG байты в `tools.rs`.
5. `tools.rs` конвертирует в `ImageContent { data: base64, mime_type: "image/jpeg" }` и возвращает агенту.

### 5.6 UI (Svelte)

Минимальный, единый стиль. Компоненты:

- `PairingWindow.svelte` — QR 400×400 + fingerprint + статус «Ожидание сканирования…» / «Подтвердите fingerprint» / «Готово».
- `ActivityWindow.svelte` — grid 4×5 превью последних медиа; клик → открывает в системном просмотрщике.
- `SettingsWindow.svelte` — форма с полями.

Тема: тёмная/светлая через CSS-переменные, `prefers-color-scheme`.

---

## 6. Android-приложение

### 6.1 Версии

```toml
# gradle/libs.versions.toml
[versions]
agp                = "8.11.1"
kotlin             = "2.2.20"
ksp                = "2.2.20-2.0.4"
composeBom         = "2026.05.00"
camerax            = "1.6.1"
datastore          = "1.2.1"
okhttp             = "5.3.0"
coroutines         = "1.10.1"
serialization      = "1.9.0"
protobuf           = "4.31.1"
protobufPlugin     = "0.9.4"
mlkit-barcode      = "17.3.0"
accompanist        = "0.37.0"
bouncycastle       = "1.79"

[libraries]
# ... (полные алиасы для всех зависимостей)
```

### 6.2 Зависимости (app/build.gradle.kts, ключевые группы)

- **UI**: `androidx.compose.bom`, `androidx.activity.compose`, `androidx.navigation.compose`, `androidx.lifecycle.runtime.compose`
- **Камера**: `androidx.camera.core`, `androidx.camera.camera2`, `androidx.camera.lifecycle`, `androidx.camera.video`, `androidx.camera.view`, `androidx.camera.compose`
- **Сеть**: `com.squareup.okhttp3:okhttp`, `com.squareup.okhttp3:okhttp-bom`
- **Хранилище**: `androidx.datastore:datastore`, `com.google.protobuf:protobuf-kotlin-lite`
- **QR**: `com.google.mlkit:barcode-scanning`
- **Permissions**: `com.google.accompanist:accompanist-permissions`
- **Crypto**: `org.bouncycastle:bcprov-jdk18on`

### 6.3 Структура (single Activity, Compose)

- `MainActivity.kt` — host для `NavHost`.
- `NavGraph` маршруты:
  - `home` — камера + список ПК
  - `pairing/scan` — QR-сканер
  - `pairing/confirm` — показ fingerprint, подтверждение
  - `settings` — настройки
  - `media/{id}` — превью медиа + кнопка «Открыть» / «Поделиться»

### 6.4 Камера

`LifecycleCameraController` + `CameraXViewfinder` (Compose-native, рекомендованный путь в 2026).

```kotlin
val controller = remember {
    LifecycleCameraController(context).apply {
        setEnabledUseCases(
            LifecycleCameraController.IMAGE_CAPTURE or
            LifecycleCameraController.VIDEO_CAPTURE
        )
    }
}
CameraXViewfinder(state = controller.previewState, modifier = Modifier.fillMaxSize())
LaunchedEffect(lifecycleOwner) { controller.bindToLifecycle(lifecycleOwner) }
```

`Recorder` для видео:
```kotlin
val recorder = Recorder.Builder()
    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
    .setTargetVideoEncodingBitrate(1_500_000)
    .build()
```

### 6.5 ImageProcessor (фото)

```kotlin
object ImageProcessor {
    private const val TARGET_LONG_EDGE = 1920
    private const val JPEG_QUALITY = 80

    fun compressFromFile(path: String, dest: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= TARGET_LONG_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(path, opts)

        if (maxOf(bmp.width, bmp.height) > TARGET_LONG_EDGE) {
            val scale = TARGET_LONG_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
            val resized = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt(),
                (bmp.height * scale).toInt(),
                true
            )
            if (resized != bmp) bmp.recycle()
            bmp = resized
        }
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bmp.recycle()
        return dest
    }
}
```

### 6.6 Proto DataStore (схема)

```protobuf
// app/src/main/proto/paired_devices.proto
syntax = "proto3";
option java_package = "dev.captureport.app.data";
option java_multiple_files = true;

message PairedDevice {
  string id           = 1;  // UUID
  string name         = 2;
  string os           = 3;  // "windows" | "macos" | "linux"
  string host         = 4;
  int32  port         = 5;
  string token        = 6;
  bytes  public_key   = 7;
  int64  last_seen_ms = 8;
  bool   pinned       = 9;
}

message PairedDevices {
  repeated PairedDevice devices = 1;
  string selected_id           = 2;
}
```

### 6.7 mDNS — NSDManager

```kotlin
class NsdDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _devices = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val devices: StateFlow<List<NsdServiceInfo>> = _devices.asStateFlow()

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(svc: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= 34) {
                nsd.registerServiceInfoCallback(svc, ContextCompat.getMainExecutor(ctx), infoCb)
            } else {
                nsd.resolveService(svc, resolveListener)
            }
        }
        // ...
    }

    fun start() = nsd.discoverServices("_captureport._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    fun stop()  = runCatching { nsd.stopServiceDiscovery(listener) }
}
```

### 6.8 Permissions

| Permission | Назначение | Запрос |
|---|---|---|
| `CAMERA` | Съёмка | При первом запуске камеры |
| `RECORD_AUDIO` | Видео со звуком | При первом запуске видеозаписи |
| `INTERNET` | WebSocket | Автоматически (не runtime) |
| `ACCESS_NETWORK_STATE` | Реакция на смену WiFi | Автоматически |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | FGS при отправке видео | При первой отправке видео |
| `POST_NOTIFICATIONS` | Уведомление FGS | Android 13+ |

`network_security_config.xml` — разрешает cleartext на `192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`, `127.0.0.1`.

---

## 7. MCP-сервер — API

### 7.1 Capabilities

```rust
ServerCapabilities::builder()
    .enable_tools()
    .enable_resources()
    .enable_resources_subscribe()
    .build()
```

### 7.2 Tools

| Имя | Вход | Возврат | Описание |
|---|---|---|---|
| `list_devices` | — | `TextContent` JSON | Список сопряжённых телефонов: `[{id, name, os, online}]` |
| `capture_photo` | `device_id?`, `timeout_ms?` (default 12000) | `ImageContent` (`image/jpeg`) | Триггерит capture, ждёт бинарь-фрейм |
| `record_video` | `device_id?`, `seconds` (1–60) | `TextContent` + `ResourceLink` (`video://<uuid>`) | Пишет клип, сохраняет в `~/Pictures/CapturePort/<дата>/<uuid>.mp4` |
| `snap_frame` | — | `ImageContent` (`image/jpeg`) | Последний принятый кадр (для polling-клиентов) |
| `video_thumbnail` | `uri` (`video://<id>`) | `ImageContent` (`image/jpeg`) | Превью первого кадра видео |
| `list_media` | `kind?` (`photo`/`video`), `since?` (ISO8601), `limit?` (default 20) | `TextContent` + `ResourceLink[]` | История |
| `copy_to_clipboard` | `uri` | `TextContent` | Положить файл/фото в системный буфер |

### 7.3 Resources

| URI | Тип | Назначение |
|---|---|---|
| `camera://latest` | `BlobResourceContents` (`image/jpeg`) | Всегда указывает на самый свежий кадр |
| `photo://<uuid>` | `BlobResourceContents` (`image/jpeg`) | Конкретное фото |
| `video://<uuid>` | `BlobResourceContents` (`video/mp4`) | Конкретное видео |

### 7.4 Уведомления

При получении нового кадра от телефона сервер шлёт `notifications/resources/updated { uri: "camera://latest" }` всем подписанным клиентам. Claude Desktop игнорирует (пока), но opencode, Cursor и MCP Inspector — поддерживают.

### 7.5 Регистрация в клиентах

**Claude Desktop** — `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "captureport": {
      "command": "C:\\Program Files\\CapturePort\\captureport-mcp.exe",
      "env": { "RUST_LOG": "info" }
    }
  }
}
```

**opencode** — `opencode.json`:
```json
{
  "mcp": {
    "captureport": {
      "type": "remote",
      "url": "http://127.0.0.1:7878/mcp",
      "enabled": true
    }
  }
}
```

**Cursor** — то же, что opencode (Streamable HTTP).

### 7.6 Безопасность

MCP-сервер слушает только на `127.0.0.1` (loopback), недоступен из LAN. Это защищает от того, чтобы произвольный процесс в сети не мог дёргать камеру без явного действия пользователя.

---

## 8. Clipboard — узкие места видео

| Проблема | Решение |
|---|---|
| OS clipboard не поддерживает «видео» как тип | Кладём `file://` URI + (Win) `CF_HDROP`, (macOS) `NSPasteboardTypeFileURL`, (Linux) `text/uri-list` через `wl-copy`/`xclip` |
| MCP не имеет `VideoContent` | Возвращаем `ResourceLink` на `video://<uuid>` + опц. inline-превью первого кадра как `ImageContent` |
| Linux: данные в буфере умирают с процессом | Перед выходом `Clipboard::set().wait()` (extension trait из `arboard`) или передаём в klipper |
| Большое видео в base64 → огромный JSON | Чанки по 256 КБ в бинарных фреймах + JSON-мета по краям |
| Агент не понимает «что в видео» | Tool `video_thumbnail(uri)` — возвращает первый кадр как JPEG inline |
| Живой поток не лезет в MCP | 1–2 fps polling `camera://latest` через `resources/read` + `notifications/resources/updated` для тех, кто умеет |

---

## 9. Дизайн

### 9.1 Принципы

- **Material 3** (Android) + **Tauri/webview** (ПК) — единый визуальный язык.
- **Material You** — палитра генерируется из accent-цвета (см. `docs/design/tokens.md`).
- **8pt grid** для отступов.
- **Без излишеств** — минимализм, focus на содержимом (превью фото).
- **Тёмная тема** по умолчанию (камерное приложение, не слепит).

### 9.2 Палитра (черновой набросок, доработаем в `docs/design/tokens.md`)

| Токен | Light | Dark | Назначение |
|---|---|---|---|
| `primary` | `#3B5BFF` | `#A4B4FF` | Акцент (кнопка «Сделать фото», активный ПК) |
| `onPrimary` | `#FFFFFF` | `#0A1A4D` | Текст на primary |
| `surface` | `#FBFBFC` | `#101114` | Фоны карточек |
| `onSurface` | `#1A1B1F` | `#E3E3E6` | Основной текст |
| `error` | `#BA1A1A` | `#FFB4AB` | Ошибки, disconnected |
| `outline` | `#DEDEE3` | `#44464F` | Границы, разделители |

### 9.3 Иконка приложения

- Концепт: стилизованная камера с молнией-стрелкой вправо (capture → port).
- Цвета: primary на прозрачном фоне.
- Размеры: 32×32 (tray), 128×128, 128×128@2x, `.ico` для Windows, `.icns` для macOS, adaptive icon для Android.

### 9.4 Типографика

- **Android**: Roboto Flex (стандартный M3), веса 400/500/600.
- **ПК**: системные шрифты (Segoe UI Variable на Windows, SF Pro на macOS, Cantarell/Ubuntu на Linux).

### 9.5 Motion

- Кнопка затвора: scale 0.95 при нажатии (200ms ease-out).
- Получение медиа: короткая вспышка primary-цвета по краям превью (300ms).
- Переходы между экранами: `fadeThrough` (400ms).

---

## 10. Спринты

### Спринт 0 — инициализация (0.5 дня)

- `D:\CapturePort\` уже существует.
- Создать структуру папок по §2.
- `.gitignore`, `LICENSE` (MIT), `README.md` (заглушка), `CHANGELOG.md`.
- `git init`, `git add`, **НЕ коммитим** (по требованию пользователя).
- `git remote add origin https://github.com/wyrtensi/CapturePort.git` (подготовить).

### Спринт 1 — Tauri-каркас + tray + mDNS (2–3 дня)

**Цель:** пустое окно приложения, иконка в трее, mDNS-анонс виден через `dns-sd -B _captureport._tcp local.`

Задачи:
- `cargo new` + Tauri scaffold
- Иконки в `icons/`, настройка `tauri.conf.json`
- `tray.rs` с меню (без действий пока)
- `mdns.rs` — advertiser `_captureport._tcp.local.` на 7878
- Unit-тест: mDNS-анонс стартует и не паникует

**Done when:** запустил `cargo tauri dev` → в трее иконка → в соседнем терминале `dns-sd -B _captureport._tcp local.` показывает `_captureport._tcp.`

### Спринт 2 — axum /ws + image clipboard (2–3 дня)

**Цель:** `wscat` шлёт JPEG → попадает в буфер обмена.

Задачи:
- `state.rs` — `AppState` (Arc, RwLock<Devices>, broadcast<MediaEvent>)
- `ws/envelope.rs` — кодирование/декодирование JSON-конверта
- `ws/mod.rs` — axum router, `/ws` handler
- `clipboard/image.rs` — `arboard::set_image`
- Принимаем `req push_photo_meta` + binary JPEG → кладём в `arboard`
- Unit-тесты: конверт round-trip

**Done when:** `wscat -c ws://localhost:7878/ws` отправляет JSON-мета + binary JPEG → проверил `pbpaste` (macOS) / Win+V — фото в буфере.

### Спринт 3 — ClipboardSink trait + видео-варианты (3–4 дня)

**Цель:** trait + три реализации (Win/macOS/Linux) для файлового URI.

Задачи:
- `clipboard/mod.rs` — trait, диспетчер
- `clipboard/windows.rs` — `clipboard-win` (CF_HDROP + CF_UNICODETEXT)
- `clipboard/macos.rs` — `objc2` (NSPasteboardTypeFileURL)
- `clipboard/linux.rs` — `wl-copy` / `xclip` shell-out
- Unit-тесты для каждой платформы (запускать под CI matrix)
- Сохранение файла в `~/Pictures/CapturePort/<дата>/<uuid>.<ext>`

**Done when:** на каждой ОС файл положен в `~/Pictures/.../test.mp4`, после `cargo run` файловый URI в буфере, и Explorer/Finder/Nautilus распознают его как файл.

### Спринт 4 — Android каркас + CameraX фото + NSD + WS (4–5 дней)

**Цель:** фото с телефона попадает в буфер ПК end-to-end.

Задачи:
- Gradle scaffold (AGP 8.11.1, Kotlin 2.2.20, Compose BOM 2026.05.00)
- `AndroidManifest.xml` — permissions, network_security_config
- `MainActivity` + `NavGraph`
- `CameraController.kt` — `LifecycleCameraController`
- `ReceiversScreen.kt` — `LazyRow` снизу
- `NsdDiscovery.kt`
- `WsClient.kt` — OkHttp WebSocket
- `EnvelopeCodec.kt` — кодирование/декодирование
- `ImageProcessor.kt` — downscale + JPEG q=80
- Proto DataStore схема + репозиторий
- Тесты: WsClient reconnect, ImageProcessor, NSD mock

**Done when:** запустил APK на эмуляторе/устройстве → в списке виден ПК (mDNS) → тапнул кнопку фото → на ПК в буфере изображение.

### Спринт 5 — QR-сопряжение (4–5 дней)

**Цель:** PC генерирует QR + ed25519 keys, phone сканирует через ML Kit, challenge-response handshake, token persistence.

Задачи:
- **PC**:
  - `pairing/keys.rs` — генерация ed25519 keypair, сохранение в OS keystore
  - `pairing/qr.rs` — генерация QR (PNG, base64 data URL)
  - UI: окно `PairingWindow.svelte` с QR + fingerprint
  - WS-handler: фаза PAIRING (challenge-response)
  - Сохранение токенов на диск (`~/Library/Application Support/CapturePort/devices.json`)
- **Android**:
  - `crypto/Ed25519KeyManager.kt` — Android Keystore
  - `qr/QrScanner.kt` — CameraX ImageAnalysis + ML Kit
  - `qr/QrUrlParser.kt` — парсинг `captureport://pair?...`
  - `pairing/PairingScreen.kt` — UI с подтверждением fingerprint
  - Сохранение в Proto DataStore

**Done when:** «Pair new device» на ПК → QR на экране → на телефоне «+ Добавить ПК» → сканер видит QR → подтверждение fingerprint → «Готово» → перезапускаю оба → соединение восстанавливается без QR.

### Спринт 6 — Reconnect/auth по токену (2–3 дня)

**Цель:** устойчивое переподключение с idempotency.

Задачи:
- `Reconnector.kt` — exp backoff + jitter
- `EnvelopeCodec` — идемпотентность (HashSet последних 100 idem-ключей)
- WS-handler: `hello { device_id, token }` → валидация → `hello_ack`
- State на ПК: `RwLock<HashMap<device_id, TokenInfo>>` + персистенция
- Интеграционные тесты: drop connection → reconnect → in-flight `capture_photo` корректно ретраится

**Done when:** тест сценарий: WiFi отвалился на 30 сек во время `capture_photo` от агента → MCP-инструмент вернул либо фото, либо TIMEOUT → агент ретрайнул → фото получено.

### Спринт 7 — MCP stdio + Streamable HTTP (4–5 дней)

**Цель:** агенты могут вызывать `capture_photo`.

Задачи:
- `mcp/tools.rs` — `list_devices`, `capture_photo`, `snap_frame`, `copy_to_clipboard`
- `mcp/resources.rs` — `camera://latest` (BlobResourceContents, image/jpeg)
- `mcp/http_server.rs` — `StreamableHttpService` на axum `/mcp`
- `bin/captureport-mcp.rs` — stdio-прокси к HTTP
- Wire-up в `state.rs`: `WsClient::request_capture` ↔ MCP tool
- `notifications/resources/updated` при новом кадре
- Регистрация в Claude Desktop + opencode
- Smoke-тест: агент вызывает `capture_photo`, получает JPEG, описывает

**Done when:** Claude Desktop показывает инструмент `capture_photo`, можно в чате спросить «что сейчас на камере?» — получит описание.

### Спринт 8 — Видео end-to-end (5–7 дней)

**Цель:** запись видео на телефоне → файл на ПК → file URI в буфере + MCP-инструменты.

Задачи:
- **Android**: `VideoRecorder.kt` — CameraX `Recorder`, чанки по 256 КБ в WS
- **PC**: приём чанков, сборка в `~/Pictures/CapturePort/<дата>/<uuid>.mp4`, проверка SHA256
- **MCP**: tools `record_video`, `video_thumbnail`, `list_media`; resources `photo://<uuid>`, `video://<uuid>`
- **Clipboard**: file URI через `ClipboardSink::put_file`
- `TransferService.kt` — FGS с уведомлением «Отправка видео…»

**Done when:** записал 10-секундное видео на телефоне → оно в `~/Pictures/CapturePort/2026-06-02/` → file URI в буфере → MCP-агент вызвал `record_video(10)` → получил `ResourceLink` + превью.

### Спринт 9 — UI polish + документация + релиз v0.1.0 (5–7 дней)

- Полировка UI (анимации, accessibility, edge cases)
- Material 3 палитра в полном объёме
- Иконка приложения финальная
- ActivityWindow на ПК (последние 20 превью)
- README с quickstart, скриншотами, gif-ками
- `docs/test-plan.md` — ручной smoke-test чеклист
- GitHub Actions: CI (тесты + линтеры), Release workflow (`tauri build` для Win/macOS/Linux)
- Тег v0.1.0, draft release

**Done when:** скачал `.msi` / `.AppImage` → установил → подключил телефон → сделал фото → открылось в Paint / Preview / GIMP. Подключил Claude Desktop → вижу инструмент `capture_photo` → работает.

### Сводная таблица

| Спринт | Цель | Дни |
|---|---|---|
| 0 | Инициализация | 0.5 |
| 1 | Tauri + tray + mDNS | 2–3 |
| 2 | axum /ws + image clipboard | 2–3 |
| 3 | ClipboardSink trait + видео | 3–4 |
| 4 | Android каркас + CameraX + WS | 4–5 |
| 5 | QR-сопряжение | 4–5 |
| 6 | Reconnect/auth + idempotency | 2–3 |
| 7 | MCP stdio + HTTP | 4–5 |
| 8 | Видео end-to-end | 5–7 |
| 9 | Polish + релиз v0.1.0 | 5–7 |
| **Итого MVP** | | **~32–43 дня для одного разработчика** |

---

## 11. Тестирование

### 11.1 Unit-тесты (Rust)

- `envelope.rs` — round-trip JSON-конверта, де/сериализация, невалидные входы.
- `correlation.rs` — `PendingMap::insert` / `reap` / удаление, таймауты.
- `pairing.rs` — генерация QR, парсинг URL, ed25519 sign/verify.
- `clipboard/*.rs` — моки платформенных API.

### 11.2 Интеграционные тесты (Rust)

`pc/tests/`:
- `envelope.rs` — реальный WS-клиент ↔ сервер, отправка JPEG, проверка что байты дошли.
- `correlation.rs` — `capture_photo` от «фейкового телефона» с задержкой 200ms, проверка что MCP-tool вернул фото.
- `pairing.rs` — полный flow QR → hello → challenge → signed → paired.

### 11.3 Тесты Android (Kotlin)

- `WsClient` — reconnect с mock-сервером, восстановление сессии.
- `ImageProcessor` — golden-изображения на входе, фиксированный размер на выходе.
- `QrUrlParser` — snapshot-тесты на разных вариантах URL.
- `PairingRepository` — round-trip Proto DataStore.

### 11.4 Ручной smoke-test (`docs/test-plan.md`)

Чеклист из 10 сценариев:
1. Первый запуск ПК → иконка в трее → mDNS виден.
2. Запуск APK → пустой список → «+ Добавить ПК» → QR-сканер.
3. На ПК «Pair new device» → QR на экране → сканирование → подтверждение → «Готово».
4. Фото: тап затвора → вспышка → тост «Отправлено» → проверить буфер ПК.
5. Видео 10 сек → сохранение в `~/Pictures/CapturePort/...` → file URI в буфере.
6. WiFi off на 30 сек → on → reconnect автоматический, без QR.
7. Claude Desktop: «Сделай фото и опиши, что видишь» → получает описание.
8. opencode: тот же сценарий.
9. Закрыть/открыть ПК-приложение → reconnect без пересканирования QR.
10. Запустить второй телефон → второй пункт в списке → выбрать → отправка на другой ПК.

### 11.5 CI (`.github/workflows/ci.yml`)

```yaml
name: CI
on: [push, pull_request]
jobs:
  rust:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix: { os: [windows-latest, ubuntu-latest, macos-latest] }
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo fmt --check
      - run: cargo clippy --all-targets -- -D warnings
      - run: cargo test --all
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - run: ./gradlew lint
      - run: ./gradlew test
      - run: ./gradlew assembleDebug
```

---

## 12. .gitignore

Корень `D:\CapturePort\.gitignore`:

```gitignore
# ─── OS ───
.DS_Store
Thumbs.db
desktop.ini
ehthumbs.db
ehthumbs_vista.db
$RECYCLE.BIN/

# ─── IDE / editors ───
.idea/
.vscode/
*.iml
*.swp
*.swo
*~
*.bak
.cache/
.history/

# ─── Secrets / local config ───
.env
.env.local
.env.*.local
*.pem
*.key
*.p12
*.pfx
secrets/
local.properties

# ─── Tauri / Rust ───
pc/src-tauri/target/
pc/src-tauri/gen/schemas/
pc/src-tauri/WixTools/
pc/src-tauri/.cargo/
pc/ui/node_modules/
pc/ui/dist/
pc/ui/.svelte-kit/
pc/ui/build/

# Cargo.lock коммитим (это бинарь)

# ─── Android / Gradle ───
android/.gradle/
android/build/
android/app/build/
android/app/release/
android/app/captures/
android/app/.cxx/
android/local.properties
android/keystore.properties
android/app/*.keystore
```

`android/.gitignore` (внутри `android/`):

```gitignore
.gradle/
build/
.cxx/
local.properties
*.iml
.idea/
captures/
```

`debug.keystore` в `android/app/` — **коммитим** (стандартный debug-keystore, безопасен, обеспечивает воспроизводимость сборок).

---

## 13. Дизайн-токены (предварительно, см. `docs/design/tokens.md`)

### Material 3 палитра

| Токен | Light | Dark | Назначение |
|---|---|---|---|
| `primary` | `#3B5BFF` | `#A4B4FF` | Акцент |
| `onPrimary` | `#FFFFFF` | `#0A1A4D` | Текст на primary |
| `primaryContainer` | `#DEE0FF` | `#1F318B` | Мягкий primary фон |
| `onPrimaryContainer` | `#001257` | `#DEE0FF` | Текст на primaryContainer |
| `secondary` | `#5B5D72` | `#C5C4DD` | Второстепенный |
| `surface` | `#FBFBFC` | `#101114` | Фон карточек |
| `onSurface` | `#1A1B1F` | `#E3E3E6` | Основной текст |
| `surfaceVariant` | `#E3E3EC` | `#44464F` | Тогглы, разделители |
| `background` | `#FBFBFC` | `#101114` | Фон экрана |
| `onBackground` | `#1A1B1F` | `#E3E3E6` | Текст на фоне |
| `error` | `#BA1A1A` | `#FFB4AB` | Ошибки |
| `outline` | `#DEDEE3` | `#44464F` | Границы |

### Типографика

- Android: **Roboto Flex** (стандарт M3), веса 400 / 500 / 600.
- PC: системные шрифты (Segoe UI Variable / SF Pro / Cantarell).
- Размеры: 12 / 14 / 16 / 20 / 24 / 32 (Android `sp`, PC `px`).

### Spacing

8pt grid: 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64.

### Motion

- Кнопка затвора: `scale(0.95)` при нажатии, 200ms `ease-out`.
- Получение медиа: тонкая вспышка primary-цвета по краям превью, 300ms.
- Переходы: `fadeThrough`, 400ms.

---

## 14. Открытые вопросы (на потом)

| # | Вопрос | Когда решить |
|---|---|---|
| 1 | macOS — включаем в CI или community-build? | Перед релизом v0.1.0 (после спринта 9) |
| 2 | Иконка — заказываем у дизайнера или рисуем сами (монограмма «CP»)? | Спринт 9 |
| 3 | Material You (monet) — генерировать палитру из обоев пользователя? | После v0.1.0 |
| 4 | Несколько телефонов → один ПК — нужна ли мульти-сессия? | v0.3+ |
| 5 | Видеострим в MCP (continuous) — отдельный канал? | v0.3+ |
| 6 | Автозапуск Android-приложения при подключении к домашнему WiFi (по SSID)? | v0.3+ |
| 7 | iOS-порт? | Не в плане MVP |
| 8 | Tauri UI: Svelte vs vanilla TS vs Yew/Rust WASM? | На спринте 1 (предлагаю Svelte — лёгкий, реактивный) |

---

## 15. Быстрые ссылки на документацию

**Tauri 2:**
- System tray: https://v2.tauri.app/learn/system-tray/
- Clipboard plugin: https://v2.tauri.app/plugin/clipboard/
- Autostart: https://v2.tauri.app/plugin/autostart/
- Single instance: https://v2.tauri.app/plugin/single-instance/

**Rust crates:**
- `axum`: https://docs.rs/axum/0.8
- `rmcp`: https://docs.rs/rmcp/1.7 + https://github.com/modelcontextprotocol/rust-sdk
- `mdns-sd`: https://docs.rs/mdns-sd/0.20
- `arboard`: https://docs.rs/arboard/3
- `clipboard-win`: https://docs.rs/clipboard-win/5

**Android:**
- CameraX: https://developer.android.com/media/camera/camerax
- Compose: https://developer.android.com/develop/ui/compose
- NSDManager: https://developer.android.com/develop/connectivity/wifi/use-nsd
- ML Kit barcode: https://developers.google.com/ml-kit/vision/barcode-scanning
- DataStore: https://developer.android.com/topic/libraries/architecture/datastore
- OkHttp WebSocket: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-web-socket/

**MCP:**
- Specification: https://modelcontextprotocol.io/specification/2025-06-18
- Schema: https://github.com/modelcontextprotocol/specification
- `rmcp` repo: https://github.com/modelcontextprotocol/rust-sdk

**Протоколы / индустрия:**
- KDE Connect protocol: https://github.com/KDE/kdeconnect-meta/blob/work/protocol-schemas/protocol.md
- scrcpy develop.md: https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md
- Noise Protocol: https://noiseprotocol.org/

---

## 16. Соглашения

- **Язык коммитов**: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`).
- **Ветки**: `main` (стабильная), `feat/<name>`, `fix/<name>`.
- **Версионирование**: SemVer. `0.1.0` = MVP готов к полевым тестам.
- **PR**: один спринт = один PR (или меньше). CI должен проходить.
- **Код-стайл**:
  - Rust: `cargo fmt` + `cargo clippy -- -D warnings`.
  - Kotlin: `ktlint` + `detekt` (по желанию, можно отложить).
- **Документация**: каждый PR с изменением протокола обязан обновить `docs/protocol.md` + `protocol/*.schema.json`.

---

*Документ будет обновляться по мере прохождения спринтов.*
