# Antigravity Mobile: Troubleshooting & Bug Post-Mortems

## Summary of Solved Bugs

| Bug | Symptoms | Root Cause | Resolution |
| :--- | :--- | :--- | :--- |
| **Bug 1: Asset Truncation** | Black screen on launch; JS syntax error | `AssetInputStream.available()` reported active decompression buffer (8 KB) instead of full file size | Stream assets using full byte read (`it.readBytes()`) |
| **Bug 2: ConnectRPC Incomplete Envelope** | Stuck on loader; `ConnectError: [invalid_argument] protocol error: incomplete envelope` | Client uses `application/grpc-web+json`; server returned unframed JSON | Implemented `frameGrpcWeb` with 5-byte data headers & `0x80` gRPC trailer frames |
| **Bug 3: AudioWorklet 404** | Error overlay; `Failed to load worklet module script: .../audio_processor.js` | Missing `audio_processor.js` asset for Web Audio voice transcription | Created `audio_processor.js` implementing `audio-streaming-processor` and `voice-mic-processor` |
| **Bug 4: Missing Schema RPCs** | React crashes on undefined props | `GetLocalUserInfo` returned `{}` without required `homeDirUri` | Added mock responses for `GetLocalUserInfo`, `GetMendelFlags`, `GetUserStatus`, `ListProjects` |

---

## Detailed Post-Mortems

### Post-Mortem 1: ConnectRPC Framing Error (`incomplete envelope`)
* **Symptom:**
  ```text
  ConnectError: [invalid_argument] protocol error: incomplete envelope
  at Object.pull (http://127.0.0.1:40389/main.js:2344:238)
  ```
* **Mechanism:** In Connect/gRPC-Web, each response must contain a 5-byte envelope header (`[flag][4-byte big-endian length]`). When raw JSON was sent, byte 0 (`{` = `0x7B` = `123`) was parsed as the flag, and the next 4 bytes (`"use`) were parsed as a 578 MB payload length. When the socket closed after 50 bytes, `Nxa.decode` detected leftover buffer bytes (`byteLength > 0`) and threw `incomplete envelope`.
* **Fix:** Enforced `frameGrpcWeb()` for all `application/grpc-web+json` requests in `LocalServer.kt`:
  ```kotlin
  val trailer = "grpc-status: 0\r\ngrpc-message: OK\r\n".toByteArray(StandardCharsets.UTF_8)
  val trailerFrame = ByteArray(5 + trailer.size).apply {
      this[0] = 0x80.toByte() // 0x80 = Trailers flag in gRPC-web
      this[1] = ((trailer.size shr 24) and 0xFF).toByte()
      this[2] = ((trailer.size shr 16) and 0xFF).toByte()
      this[3] = ((trailer.size shr 8) and 0xFF).toByte()
      this[4] = (trailer.size and 0xFF).toByte()
      System.arraycopy(trailer, 0, this, 5, trailer.size)
  }
  ```

---

### Post-Mortem 2: AudioWorklet 404 (`Failed to load worklet module script`)
* **Symptom:**
  ```text
  Failed to load worklet module script:
  http://127.0.0.1:40361/audio_processor.js?v=2.25.0 (HTTP status: 404)
  ```
* **Mechanism:** `main.js` boots the audio engine for voice inputs by requesting `c.audioWorklet.addModule('/audio_processor.js?v=2.25.0')`. Because this static asset was missing from the APK, the server returned 404, rejecting the promise and halting React execution.
* **Fix:** Provided `app/src/main/assets/web/audio_processor.js` and added asset routing in `MainActivity.kt` and `LocalServer.kt`.

---

## State at Checkpoint
* **Current Version:** `Antigravity-Mobile-v1.6.apk`
* **Log Status:** Zero runtime errors (`hasError=false`, `hasRejection=false`).
* **Next Action on Resume:** Wire Google OAuth sign-in and Gemini cascade streaming.
