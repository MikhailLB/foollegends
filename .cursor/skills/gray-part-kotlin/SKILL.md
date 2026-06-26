---
name: gray-part-kotlin
description: >-
  Build or port a native Kotlin (non-Flutter) Android "gray part": AppsFlyer
  attribution gate, config endpoint, WebView shell, FCM push, no-internet and
  push-permission screens, with a unique per-project fingerprint. Use when the
  user asks to add a gray part / WebView shell / attribution flow to a Kotlin
  Android app, port the gray template to a new app, or fix gray-flow bugs
  (blank WebView, attribution returns native, push URL not opening, safe-area,
  no-wifi screen, slow loads, loading bar).
---

# Gray Part — Native Kotlin

This skill deploys the gray flow into a native Kotlin Android app. Always read
the project rules first; they contain the authoritative detail:

- `.cursor/rules/kotlin_gray_guide.mdc` — architecture, state machine, config
  contract, push, requirements. **Read first.**
- `.cursor/rules/kotlin_webview.mdc` — WebView shell spec.
- `.cursor/rules/kotlin_gray_pitfalls.mdc` — real bugs + exact fixes.
- `.cursor/rules/kotlin_fingerprint.mdc` — mandatory per-project uniqueness.

## Workflow

1. **Gather inputs** (ask the user if missing):
   - bundle id, app name (store), config endpoint URL
   - AppsFlyer dev key, Firebase `google-services.json` + project number
   - OneLink host (for the manifest deep-link filter)
   - gray screen art (notification + no-wifi, portrait + landscape) and a
     monochrome notification icon glyph

2. **Fingerprint FIRST** (`kotlin_fingerprint.mdc`): pick a fresh codec seed +
   mixing formula, package layout, class names, storage keys, channel id and
   library versions. NEVER copy the template 1:1.

3. **Scaffold** the modules per the guide layout (Application, router/splash,
   WebView host, push-permission screen, offline screen, config client,
   AppsFlyer wrapper, FCM service + bus, codec, storage, connectivity, blueprint).

4. **Encode secrets** with the project's codec scheme; decode-verify each array
   equals the original string before committing.

5. **Wire Gradle/Manifest**: `buildConfig=true` if used; release signing from
   `keystore/keystore.properties`; permissions INTERNET / ACCESS_NETWORK_STATE /
   POST_NOTIFICATIONS / VIBRATE; FCM service + icon/channel meta-data;
   `networkSecurityConfig` cleartext; WebView activity `adjustResize` +
   `singleTask` + sensor orientation. **No `applicationIdSuffix`** (pitfalls #1).

6. **Implement the WebView** to satisfy every item in `kotlin_webview.mdc`
   (UA with appid/appname, safe-area insets, keyboard fix, redirect retry,
   onCreateWindow, file chooser, back nav, offline heartbeat, push bus).

7. **Build & verify on device**:
   - `gradlew assembleDebug` then `adb install -r ...apk` (NOT `installDebug` —
     pitfalls #16).
   - Force fresh state: `adb shell pm clear <pkg>`.
   - Capture logs: `adb logcat -v time -s WelcomePortal TrackingDispatch
     ReachDispatch NetWire PushRelay` (use the project's actual class names).
   - Confirm: AppsFlyer fires `onConversionDataSuccess` with `af_status`,
     ReachDispatch logs the request body + `HTTP 200 ok=true url=...`, router
     goes STREAM. A 404/empty attribution → NATIVE is correct.

8. **Test the TZ scenarios**: first-launch gray/white/no-internet; returning
   gray (saved-url fallback on endpoint failure) / native; push permission
   (accept / 3-day re-ask / OS-deny-never-again); push URL opens in WebView and
   is one-time; rotation + lock/unlock safe area; redirects; file upload;
   keyboard; back nav.

## Hard rules

- Never modify/filter AppsFlyer conversion fields — send verbatim.
- Backend HTTP 404 (or non-2xx) = negative answer → native/offline, never a crash.
- Once NATIVE, stay native; once STREAM, persist url+expires and fall back to
  the saved url when the endpoint fails.
- Push URLs are one-time (cold → save+consume; warm → live load, never persist).
- Keystore and `keystore.properties` stay gitignored.
- Re-verify the build on a real device after every gray change; rely on logs,
  not assumptions, for the gray/white decision.
