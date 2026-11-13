# YouTube Music song recognition — reverse-engineering findings

**Date:** 2026-11-11
**Question:** Can YTMP reuse YouTube Music's own "Song search" (song recognition)
instead of Shazam, and can it be done without a Google account?
**Short answer:** No. The recognizer is gated behind the YouTube Music app's
**signing identity**, not a user account. There is no legitimate path to that
identity for a separate project, so `ShazamLookup` stays the recognizer.

---

## Method

- Pulled the APK off a Pixel 7 with adb: YouTube Music **9.45.51**
  (`com.google.android.apps.youtube.music`), `base.apk`, ~85 MB.
- Decompiled with **jadx 1.5.6** (`--no-res --show-bad-code`).
- Reconstructed the request protobuf by hand from the decompiled message layouts.
- Wrote a minimal pure-Python gRPC client and ran probes against the live endpoint.
  (Probes were run by the device owner, not from the agent sandbox.)

## How the feature works in the app

- The song-search button opens `VoiceSearchFragment` (`jkc`), which drives a class
  Google calls `EmbeddedAssistant` (`aeqj`).
- Audio is recorded at **16 kHz, mono, 16-bit PCM** (Ogg Opus on Android 11+) and
  **streamed live** as it records.
- Transport: **gRPC over TLS** to `embeddedassistant.googleapis.com:443`.
- Service/method: `google.assistant.embedded.v1.EmbeddedAssistant/**YTAssist**`
  — a YouTube-only method on Google's Assistant Embedded service (the public one is
  `Assist`).
- The result comes back as a YouTube Music search/navigation response.

### Recognition is server-side

The phone sends **raw audio**, not an on-device fingerprint. There is no local
recognition model in this path. (This corrected an earlier wrong assumption that a
TFLite model on the phone was the blocker.)

## Request format (reconstructed proto)

Field numbers taken straight from the APK's protobuf-lite message layouts:

| Decompiled class | Proto message | Fields |
|---|---|---|
| `agef` | `AudioInConfig` | 1 = encoding (enum, LINEAR16 = 1), 2 = sample_rate_hertz |
| `ageh` | `AudioOutConfig` | 1 = encoding, 2 = sample_rate_hertz, 3 = volume_percentage |
| `agei` | `DeviceConfig` | 1 = device_id, 2 = device_model_id |
| `ageb` | `AssistConfig` | 1 = audio_in_config, 2 = audio_out_config, 4 = device_config, 6 = text_query |
| `aged` | `AssistRequest` | oneof { 1 = audio_in (bytes), 2 = config (AssistConfig) } |

Stream shape: first message = `AssistRequest{config}`, then repeated
`AssistRequest{audio_in = <PCM chunk>}`.

A large YouTube-specific wrapper (`alrx`) carries the InnerTube client context
(same client/version/language that `ytmusicapi` sends) to shape the result into a
YT Music card. It is **not required** to reach the recognizer — the backend only
demands `audio_in_config` or `text_query`.

## Authentication / authorization

- **No user account is required.** Signed out, the app does **not** send an
  `Authorization` header.
- Instead it carries a hard-coded `x-goog-api-key` (a Google API key compiled into
  the app, in `aeqk.java`; not reproduced here — it is restricted to the app's
  package + signing certificate anyway, see below).
- Signed in, it adds `Authorization: Bearer <account token>` (interceptor `aeqn`)
  plus `X-Goog-PageId` for brand accounts. Also `X-Goog-Visitor-Id` when known.
- **The real gate:** a GMS/Cronet layer (`psy.java`) automatically attaches
  **`X-Android-Package`** (package name) and **`X-Android-Cert`** (the app's
  **signing-certificate hash**) to every Google API call. The anonymous API key is
  **restricted to that package + certificate pair.** So an anonymous request still
  proves it comes from the genuine, Google-signed YouTube Music app.
- No Play Integrity / DroidGuard call sits *inside* the recognizer classes
  themselves — the identity check is the package + cert header pair added upstream.

### `device_model_id` vs `device_id`

- `device_model_id` is the **product type** — one shared value registered to
  Google's YouTube Cloud project; every install sends the same one. In the static
  APK it is only `"PLACEHOLDER"`; the real value is injected at runtime.
- `device_id` is the **per-install instance**, provisioned at runtime through
  Play Services. A `device_id` is only valid relative to the project/account that
  registered it.

## Probe results (live endpoint)

Run order, each got one step further:

1. API key + **empty** body →
   `INVALID_ARGUMENT: Invalid 'AssistConfig': audio_in_config or text_query must be provided.`
   → **key authenticates; reveals the real proto field names.**
2. API key + audio, **no device_config** →
   `INVALID_ARGUMENT: Invalid 'device_config': device_model_id must be set.`
   → audio accepted; config validation wants device_config.
3. API key + audio + **made-up device_config** →
   `PERMISSION_DENIED: The caller does not have permission.`
   → structurally complete, but the backend denies the operation because the
   caller is not the authorized app (no `X-Android-Package` / `X-Android-Cert`
   for YouTube Music's package + signing cert).

## Why YTMP cannot use it

To get past step 3, a request must present YouTube Music's **package name + signing
certificate fingerprint** (and/or its registered device identity). The cert hash is
derivable from the APK, so it is mechanically reachable — but sending it means
asserting *"I am the app Google signed."* That is impersonating the first-party app
at the identity level. It is different in kind from the InnerTube access that
`ytmusicapi` / `yt-dlp` use (those identify as the public web/app client with a key
the web page ships to every browser; they do **no** audio recognition and touch a
different API entirely).

Additional reasons it is a poor foundation even setting impersonation aside:

- Private, undocumented endpoint — Google changes it without notice on app updates.
- The anonymous key is restricted and revocable; non-app traffic on it is the first
  thing flagged, and callers/IPs get banned.
- It is a **public, released** project; shipping YouTube Music's identity is a far
  larger exposure than a private experiment.
- ReVanced does not help: it patches the real app and runs the genuine code with the
  real runtime identity; it exposes no reusable anonymous recognizer, and
  repackaging changes the signing cert (breaking signature-gated features).

## Conclusion

Feasible only by impersonating YouTube Music's signed app identity, which is out of
bounds and fragile. **Keep `ShazamLookup`** as YTMP's recognizer — it is the
buildable, stable, legitimate path. If recognition needs to improve, improve the
Shazam path (speed, podcasts, live audio), not this.

See also: [`module-api.md#recognize-a-song`](module-api.md#recognize-a-song).
