# The Antigravity Mobile Journey: Reverse Engineering Google's Advanced Agent IDE

## Table of Contents
1. [Prologue: The Challenge](#1-prologue-the-challenge)
2. [Phase 1: Binary & Electron Anatomy](#2-phase-1-binary--electron-anatomy)
3. [Phase 2: Decoding the Wire Protocol](#3-phase-2-decoding-the-wire-protocol)
4. [Phase 3: The Secret Mobile UI in Desktop Code](#4-phase-3-the-secret-mobile-ui-in-desktop-code)
5. [Phase 4: Constructing the On-Device Android Host](#5-phase-4-constructing-the-on-device-android-host)
6. [Phase 5: Diagnosing the "Black Screen of Death"](#6-phase-5-diagnosing-the-black-screen-of-death)
7. [Phase 6: The ConnectRPC Protocol & gRPC-Web Binary Framing Breakthrough](#7-phase-6-the-connectrpc-protocol--grpc-web-binary-framing-breakthrough)
8. [Phase 7: The AudioWorklet 404 & Audio Engine Emulation](#8-phase-7-the-audioworklet-404--audio-engine-emulation)
9. [Current Project State & Checkpoint Analysis](#9-current-project-state--checkpoint-analysis)
10. [Roadmap for Future Resurrection](#10-roadmap-for-future-resurrection)

---

## 1. Prologue: The Challenge
Google Antigravity is DeepMind's flagship coding assistant IDE. While officially distributed as a desktop-only application for Windows, macOS, and Linux, the objective of this project was ambitious:
> **Make Antigravity run 100% natively on an Android smartphone without any external PC or cloud VM dependency.**

The smartphone's internal storage must hold the workspaces (`/sdcard/Projects`), its ARM64 CPU must execute terminal commands and builds (`/system/bin/sh`), and the user experience must match the desktop interface 1:1.

---

## 2. Phase 1: Binary & Electron Anatomy
Inspection of the Windows desktop installation (`%LOCALAPPDATA%\Programs\Antigravity\`) revealed:
* `Antigravity.exe` (246 MB): An Electron wrapper based on Chromium.
* `resources/app.asar` (4.5 MB): Packed Electron main and preload scripts.
* `resources/bin/language_server.exe` (174 MB): A compiled Go binary with embedded Google RPC services and V8 engine.

Using custom extraction tools, we unpacked `app.asar` and discovered `dist/languageServer.js`:
```javascript
const args = [
    '--standalone',
    '--override_ide_name', 'antigravity',
    '--subclient_type', 'hub',
    '--override_ide_version', app.getVersion(),
    '--https_server_port', String(port),
    '--csrf_token', csrf,
    '--app_data_dir', 'antigravity',
    '--api_server_url', 'https://generativelanguage.googleapis.com',
    '--cloud_code_endpoint', 'https://daily-cloudcode-pa.googleapis.com',
    '--enable_sidecars',
];
```
This demonstrated that `language_server.exe` is a loopback ConnectRPC/HTTP server listening on `127.0.0.1:<dynamic_port>`. Electron merely creates a `BrowserWindow` loading `https://127.0.0.1:<port>/`.

---

## 3. Phase 2: Decoding the Wire Protocol
Electron's `preload.js` was surprisingly lightweight:
* It only handled window chrome (minimize, maximize, close), `nativeStorage`, notifications, and deep links.
* **All file reads, file writes, terminal executions, and AI prompts bypass Electron completely and talk directly to the HTTP port via ConnectRPC!**

By probing the live running desktop process on port `61573`, we mapped out **347 RPC methods** under `/exa.language_server_pb.LanguageServerService/`:
* `ReadFile`: Takes `{"uri": "file:///..."}`, returns base64 content and language ID.
* `WriteFile`: Takes `{"uri": "file:///...", "content": "<base64>"}`.
* `RunCommand`: Takes `{"command": "...", "workingDirectoryUri": "..."}`, executes shell commands and returns output with exit code.
* `GetLocalUserInfo`: Returns `{"username": "...", "homeDirUri": "..."}`.
* `GetUserStatus`: Returns subscription tier, credit quotas, and available model definitions.
* `GetServerConfiguration`: Provides feature gating flags.

---

## 4. Phase 3: The Secret Mobile UI in Desktop Code
When analyzing the 10.1 MB frontend bundle (`main.js`), we made a startling discovery:
```javascript
Jja = () => /android|iphone|ipad|ipod|blackberry|iemobile|opera mini/i.test(
    (typeof navigator !== "undefined" ? navigator.userAgent : "") || ""
);
// In UI Router:
a = Jja() ? Kh.Web.Mobile : Kh.Web.Desktop;
```
Google's engineers had **already authored complete mobile-responsive layouts (`Kh.Web.Mobile`) inside the desktop bundle!** The desktop app was secretly designed from day one to be responsive on touchscreens.

---

## 5. Phase 4: Constructing the On-Device Android Host
Rather than writing a rudimentary third-party client, we engineered a native Android application (`com.google.antigravity.mobile`) that replicates the desktop architecture:
1. **Loopback Server (`LocalServer.kt`):** A high-performance multi-threaded HTTP server running directly on `127.0.0.1` inside the Android process.
2. **Asset Streaming:** Serves the extracted official desktop bundle (`index.html`, `main.js`, `jetbox.css`, `compiled_tailwind.css`, `prism_bundle.js`) directly from APK assets.
3. **Hardware Storage & Shell Engine:**
   - Files are stored in `/sdcard/Projects`.
   - Commands are dispatched to `/system/bin/sh` using Android's native `ProcessBuilder`.
4. **Desktop Polyfill Bridge:** Emulates `window.electronNative` and `window.nativeStorage` using Android's `JavascriptInterface` backed by `SharedPreferences`.

---

## 6. Phase 5: Diagnosing the "Black Screen of Death"
When the initial APK was installed on a real phone, the screen opened, asked for storage permissions, and then went completely black. 

Deep debugging revealed two critical root causes:
1. **Asset Truncation Bug:** In Android, `InputStream.available()` on compressed APK assets returns only the active decompression buffer size (e.g. 8192 bytes) rather than total uncompressed length. Setting `Content-Length: 8192` truncated `main.js` from 10.1 MB down to 8 KB, causing a fatal JS syntax error.
2. **Missing `GetLocalUserInfo`:** The startup sequence called `getLocalUserInfo()`. When unhandled, it returned `{}`, causing `Oe(undefined.homeDirUri)` to throw `TypeError: Cannot read properties of undefined` and crashing the React root before `(0, Ov.createRoot)(t)` could mount.

---

## 7. Phase 6: The ConnectRPC Protocol & gRPC-Web Binary Framing Breakthrough
After fixing asset truncation and basic mock schemas, the application was still stuck on the bootloader screen. By connecting to a live physical device (**iQOO Z9s Pro 5G** on **Android 16**) via Wireless Debugging (`adb connect 192.168.31.126:42335`), we caught the exact exception in real time:

```text
ConnectError: [invalid_argument] protocol error: incomplete envelope
at Object.pull (http://127.0.0.1:40389/main.js:2344:238)
```

### The Root Cause:
Antigravity's ConnectRPC client (`bBc`) initializes transport using `g3a({ baseUrl, useBinaryFormat: false })`, which operates in **gRPC-Web mode** (`application/grpc-web+json`). 
In gRPC-Web:
- All messages (including unary responses) must be wrapped inside a **5-byte binary framing header**:
  - `Byte 0`: Flags (`0x00` for data)
  - `Bytes 1..4`: 4-byte big-endian payload length
  - `Bytes 5..5+len-1`: The JSON payload bytes
- The response MUST terminate with a **Trailers frame**:
  - `Byte 0`: Flags (`0x80` = 128 for Trailers)
  - `Bytes 1..4`: 4-byte big-endian trailer length
  - `Bytes 5..5+tlen-1`: `grpc-status: 0\r\ngrpc-message: OK\r\n`

Because `LocalServer.kt` was previously replying with unframed raw JSON (`{"username": ...}`), the client read the initial `{` (0x7B = 123) as the flag and the following 4 bytes as a huge 578 MB length, triggering `incomplete envelope` when the stream terminated.

### The Fix (`frameGrpcWeb`):
```kotlin
private fun frameGrpcWeb(jsonBytes: ByteArray): ByteArray {
    val len = jsonBytes.size
    val dataFrame = ByteArray(5 + len)
    dataFrame[0] = 0 // Flags: 0 = data
    dataFrame[1] = ((len shr 24) and 0xFF).toByte()
    dataFrame[2] = ((len shr 16) and 0xFF).toByte()
    dataFrame[3] = ((len shr 8) and 0xFF).toByte()
    dataFrame[4] = (len and 0xFF).toByte()
    System.arraycopy(jsonBytes, 0, dataFrame, 5, len)

    val trailer = "grpc-status: 0\r\ngrpc-message: OK\r\n".toByteArray(StandardCharsets.UTF_8)
    val tLen = trailer.size
    val trailerFrame = ByteArray(5 + tLen)
    trailerFrame[0] = 0x80.toByte() // Flags: 0x80 = 128 = Trailers
    trailerFrame[1] = ((tLen shr 24) and 0xFF).toByte()
    trailerFrame[2] = ((tLen shr 16) and 0xFF).toByte()
    trailerFrame[3] = ((tLen shr 8) and 0xFF).toByte()
    trailerFrame[4] = (tLen and 0xFF).toByte()
    System.arraycopy(trailer, 0, trailerFrame, 5, tLen)

    return dataFrame + trailerFrame
}
```
With this fix applied, all startup RPCs (`GetServerConfiguration`, `GetLocalUserInfo`, `GetMendelFlags`, `JetboxSubscribeToState`, `JetboxSubscribeToSummaries`) succeeded with `200 OK`!

---

## 8. Phase 7: The AudioWorklet 404 & Audio Engine Emulation
Once gRPC-Web framing was resolved, a new rejection appeared:
```text
Failed to load worklet module script:
http://127.0.0.1:40361/audio_processor.js?v=2.25.0 (HTTP status: 404)
```

### Analysis:
In `main.js`:
```javascript
let b = window.__APP_CONFIG__?.appVersion,
    c = typeof window !== "undefined" && typeof AudioContext !== "undefined" ? new AudioContext({sampleRate: 48E3}) : void 0,
    e = si() || "";
b = c && c.audioWorklet ? c.audioWorklet.addModule(`${e}/audio_processor.js?v=${b ?? "dev"}`) : Promise.resolve();
return { audioContext: c, audioWorkletPromise: b, enabled: di(a, "enable-audio-transcription") };
```
Antigravity initializes real-time voice streaming using the Web Audio API and expects `/audio_processor.js` to register two processors:
1. `audio-streaming-processor`
2. `voice-mic-processor`

Because `audio_processor.js` was missing, `addModule()` rejected with a 404 `DOMException`, pausing React mounting.

### The Fix:
Created `app/src/main/assets/web/audio_processor.js` with compliant processor definitions and added zero-latency asset interception in `MainActivity.kt`.

---

## 9. Current Project State & Checkpoint Analysis
As of **v1.6**, the app has reached a major milestone:
* **Zero JavaScript errors.**
* **Zero unhandled promise rejections.**
* All loopback RPCs return valid gRPC-Web framed data.
* Web Audio Worklets load cleanly.
* React 18 `createRoot(root).render(...)` has executed, successfully unmounting the initial loader container (`hasLoader=false`).

### State at which work was paused:
* The `#root` element has been initialized by React. 
* Continuous polling reports:
  ```text
  [Antigravity POLL] rootChildren=0, hasLoader=false, hasError=false, hasRejection=false
  ```
* React router is active and waiting for the active project/workspace selection or Google OAuth authentication state to populate the main view tree.

---

## 10. Roadmap for Future Resurrection
When returning to this codebase, the next steps are:
1. **Google OAuth Login Flow:**
   - Implement `/exa.language_server_pb.LanguageServerService/LoginWithBrowser` to open `accounts.google.com/o/oauth2/auth` via Chrome Custom Tabs using Google's Client ID (`1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com`).
2. **Gemini AI Streaming Engine:**
   - Implement `SendUserCascadeMessage` / `StartCascade` in `LocalServer.kt` to forward prompts to `https://generativelanguage.googleapis.com/v1beta/models/...:streamGenerateContent` using the authenticated token.
   - Stream trajectory frames back into `main.js` via Connect streaming.
3. **Initial Workspace Seeding:**
   - Seed a default project in `GetWorkingDirectories` and `ListProjects` so React router immediately mounts the Mobile Workspace View instead of an empty root.
