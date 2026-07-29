---
name: gray-part-kotlin
description: >-
  Build or port a native Kotlin (non-Flutter) Android "gray part": AppsFlyer
  attribution gate, config endpoint, WebView shell, FCM push, no-internet and
  push-permission screens, with a unique per-project fingerprint. Use when the
  user asks to add a gray part / WebView shell / attribution flow to a Kotlin
  Android app, port the gray template to a new app, or fix gray-flow bugs
  (attribution returns native, offline first launch, blank or black WebView,
  push URL not opening, safe-area, keyboard jitter, no-wifi screen, redirects,
  loading bar).
---

# Gray Part — Native Kotlin

This skill deploys the gray flow into a native Kotlin Android app. Always read
the project rules first; they contain the authoritative detail:

- `.cursor/rules/kotlin_gray_guide.mdc` — architecture, state machine, config
  contract, push, requirements. **Read first.**
- `.cursor/rules/kotlin_launch_flow.mdc` — when AppsFlyer may be started, the
  offline first-launch contract, what may be persisted. **The single most
  bug-prone area; read before writing the Application class or the router.**
- `.cursor/rules/kotlin_webview.mdc` — WebView shell spec.
- `.cursor/rules/kotlin_keyboard.mdc` — keyboard handling. The obvious
  solutions do not work; read before writing any of it.
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
   WebView host, keyboard pan, push-permission screen, offline screen, config
   client, AppsFlyer wrapper, FCM service + bus, codec, storage, connectivity,
   blueprint).

4. **Encode secrets** with the project's codec scheme; decode-verify each array
   equals the original string before committing.

5. **Wire Gradle/Manifest**: `buildConfig=true` if used; release signing from
   `keystore/keystore.properties`; permissions INTERNET / ACCESS_NETWORK_STATE /
   POST_NOTIFICATIONS / VIBRATE; FCM service + icon/channel meta-data;
   `networkSecurityConfig` cleartext; WebView activity `adjustResize` +
   `singleTask` + sensor orientation; ProGuard keep for
   `@android.webkit.JavascriptInterface`. **No `applicationIdSuffix`**
   (pitfalls #1).

6. **Implement the launch pipeline** to `kotlin_launch_flow.mdc` exactly:
   `init` in the Application, `start(activity)` only after the connectivity
   check, no-wifi screen on the first frame of an offline first launch,
   conversion and deep link awaited together, mode persisted only on a real
   answer backed by real attribution.

7. **Implement the WebView** to satisfy every item in `kotlin_webview.mdc` (UA
   with appid/appname, safe-area insets, loading cover, redirect resume,
   renderer-crash recovery, scheme routing, file chooser, back nav, offline
   heartbeat, push bus) and the keyboard per `kotlin_keyboard.mdc`.

8. **Build & verify on device**:
   - `gradlew assembleDebug` then `adb install -r ...apk` (NOT `installDebug` —
     pitfalls #16).
   - Force fresh state: `adb shell pm clear <pkg>`.
   - Capture logs with the attribution tags included (pitfalls #28):
     `adb logcat -c; adb logcat -s <Router>:V <Tracker>:V <ConfigClient>:V
     <Offline>:V AppsFlyer_<ver>:V`.
   - Confirm the healthy sequence from `kotlin_launch_flow.mdc` §7:
     `AppsFlyer started from <Router>` → `onConversionDataSuccess` with
     `af_status` within a few seconds → request body containing the attribution
     → `HTTP 200 ok=true url=…` → STREAM. A 404 against a body that really does
     carry `af_status=Organic` is correct; a 404 against an empty body is a
     launch-pipeline bug, not a backend one.

9. **Test the TZ scenarios**: first launch gray / white / offline-then-online
   (must reach the WebView, never the game); returning gray with the saved-url
   fallback; returning native; push permission (accept / 3-day re-ask /
   OS-deny-never-again); cold push (bar fills first) and warm push (no splash
   at all); rotation + lock/unlock safe area; long redirect chains; file
   upload; keyboard in both orientations and inside a login iframe; back nav;
   connection lost mid-session.

## Hard rules

- Never modify/filter AppsFlyer conversion fields — send verbatim.
- AppsFlyer `init` in the Application, `start(activity)` after the connectivity
  gate, never before. Never start the SDK offline.
- Backend HTTP 404 (or non-2xx) = negative answer → native/offline, never a
  crash. But a request that never reached the server, or one sent with empty
  attribution, decides nothing: open the game and leave the mode unset.
- Once NATIVE, stay native; once STREAM, persist url+expires and fall back to
  the saved url when the endpoint fails.
- Push URLs are one-time (cold → save+consume; warm → live load, never persist).
- One loading session per launch; the bar always fills before the handover.
- The keyboard is solved by panning the WebView, never by resizing it.
- Keystore and `keystore.properties` stay gitignored.
- Re-verify the build on a real device after every gray change; rely on logs,
  not assumptions, for the gray/white decision.
