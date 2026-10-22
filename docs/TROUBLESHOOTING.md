# Antigravity Mobile: Troubleshooting & Bug Post-Mortems

## Bug Report: The "Black Screen of Death"

### Symptoms
When `Antigravity-Mobile-v1.0.apk` is installed on an Android device:
1. Application launches and requests All Files Access (`MANAGE_EXTERNAL_STORAGE`).
2. After granting permission and returning to the app, the display is completely black.
3. Relaunching the application does not fix the issue.

---

### Root Cause Analysis

#### 1. Asset Streaming Truncation (`InputStream.available()`)
* **Problem:** In [`LocalServer.kt`](../app/src/main/java/com/example/antigravity/server/LocalServer.kt), the asset streaming method originally queried `stream.available()` to calculate the `Content-Length` header.
* **Mechanism:** In the Android runtime, assets packaged inside an APK are compressed. The `AssetInputStream.available()` implementation does not report total uncompressed file size; it reports only the number of bytes available in the active decompression buffer (typically `8192` bytes).
* **Impact:** For `main.js` (which is `10,178,381` bytes / 10.1 MB), the server sent `Content-Length: 8192`. The Android WebView read the first 8 KB and closed the socket. The JavaScript engine failed with `SyntaxError: Unexpected end of input`, and React failed to mount.
* **Fix:** Replaced buffer-based length calculation with `stream.use { it.readBytes() }`, providing the exact byte count in the header.

#### 2. Startup RPC Dependency Failure (`GetLocalUserInfo`)
* **Problem:** During the initialization sequence in `main.js`, `RJc()` executes:
  ```javascript
  let e = await a.lsClient.getLocalUserInfo({});
  return { homeDirUri: Oe(e.homeDirUri), username: e.username };
  ```
* **Mechanism:** Our loopback server did not implement `GetLocalUserInfo`, returning an empty `{}` object. `e.homeDirUri` evaluated to `undefined`. `Oe(undefined)` threw an uncaught `TypeError: Cannot read properties of undefined`, causing the startup promise to reject before reaching `createRoot(root).render(...)`.
* **Fix:** Added a comprehensive mock for `GetLocalUserInfo` returning `{"username": "mobile_developer", "homeDirUri": "file:///storage/emulated/0"}` alongside `GetUserStatus`, `HasAuthToken`, and `GetGrantedScopes`.

#### 3. Bridge Injection Race Condition
* **Problem:** Injecting native bridge polyfills (`window.nativeStorage`, `window.electronNative`) asynchronously via `WebViewClient.onPageStarted()` introduced a race condition with script evaluation.
* **Fix:** Embedded the polyfill directly as a synchronous inline script in `<head>` of `index.html`, guaranteeing all APIs are defined before `main.js` evaluates.

#### 4. Surface Background Contrast
* **Problem:** The default web page style was `background-color: transparent`, which defaulted to black when the WebView was empty.
* **Fix:** Explicitly set `#131313` background styling on `html`, `body`, and Android's `FrameLayout` and `WebView` to prevent visual flashes.
