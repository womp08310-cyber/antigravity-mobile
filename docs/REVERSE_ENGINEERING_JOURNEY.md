# The Antigravity Mobile Journey: Reverse Engineering Google's Advanced Agent IDE

## Table of Contents
1. [Prologue: The Challenge](#prologue-the-challenge)
2. [Phase 1: Binary & Electron Anatomy](#phase-1-binary--electron-anatomy)
3. [Phase 2: Decoding the Wire Protocol](#phase-2-decoding-the-wire-protocol)
4. [Phase 3: The Secret Mobile UI in Desktop Code](#phase-3-the-secret-mobile-ui-in-desktop-code)
5. [Phase 4: Constructing the On-Device Android Host](#phase-4-constructing-the-on-device-android-host)
6. [Phase 5: Diagnosing the "Black Screen of Death"](#phase-5-diagnosing-the-black-screen-of-death)
7. [The Road Ahead: Google OAuth & Gemini Brain](#the-road-ahead-google-oauth--gemini-brain)

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

By buffering the full asset bytes and implementing complete startup RPC responses (`GetLocalUserInfo`, `GetUserStatus`, `HasAuthToken`, `GetContribVariants`), the React application boots cleanly.

---

## 7. The Road Ahead: Google OAuth & Gemini Brain
The final milestone is integrating genuine Google Account sign-in (`accounts.google.com`) and dispatching user prompts from `LocalServer.kt` to Google's Gemini API with real-time trajectory streaming.
