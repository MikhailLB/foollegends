# Android Gray-Part Template (Kotlin)

A native **Kotlin** Android template implementing the "gray flow": one binary
that shows either a **WebView shell** (paid/attributed users) or a **native
game/content** (organic users), decided at runtime from **AppsFlyer**
attribution via a backend config endpoint. Includes FCM push, a push-permission
screen, a no-internet screen and a branded loading splash.

> This is a **template with placeholders**, not a finished app. Search the code
> for `TODO(you)` and fill in your values. The full design + rules live in
> `.cursor/rules/` and the deploy workflow in `.cursor/skills/gray-part-kotlin/`.

## Architecture (module map)

```
startup/AppEntry           Application: Firebase + AppCheck + AppsFlyer wiring
startup/WelcomePortal      LAUNCHER: splash + gray/white routing state machine
portal/StreamPortal        Full-screen WebView shell (gray)
portal/KeyboardPan         Slides the WebView clear of the keyboard
portal/AlertPortal         Push-permission screen (Accept / Skip)
portal/OfflinePortal       No-internet screen + Retry
NativeContentActivity      PLACEHOLDER white part — replace with your game
reach/ReachDispatch        POST to config endpoint (OkHttp)
reach/TrackingDispatch     AppsFlyer attribution: deep link, conversion, re-ask
signal/PushRelay + PushBus FCM service + warm-URL hand-off
vault/CipherVault          XOR string deobfuscator (unique seed per project)
vault/DataVault            prefs + EncryptedSharedPreferences
wire/NetWire               connectivity (default network callback + TCP probe)
blueprint/AppBlueprint     central config + encoded secrets
LoadingView                animated splash (placeholder art)
```

## Setup checklist

1. **Rename for your project** (fingerprint — mandatory, see
   `.cursor/rules/kotlin_fingerprint.mdc`): change the package
   `com.example.grayshell`, class names, `CipherVault` seed + formula, storage
   keys, FCM channel id and library versions. Never ship two apps identical.
2. **`AppBlueprint`**: set `bundleId` / `appLabel` / `appNameToken`, then encode
   and paste the config URL, AppsFlyer dev key and Firebase project number
   (decode-verify each array).
3. **`app/build.gradle.kts`**: set `applicationId` (= bundle id).
4. **`google-services.json`**: replace the placeholder with your real Firebase
   file (package_name must equal applicationId).
5. **AndroidManifest**: add the AppsFlyer OneLink deep-link `<intent-filter>`.
6. **Assets**: drop branded splash art (`LoadingView`), gray-screen backgrounds
   (`res/drawable*/gray_*`), launcher icon and a monochrome notification icon
   (`res/drawable/ic_notif_*`).
7. **White part**: replace `NativeContentActivity` with your real game.
8. **Firebase push backend**: add the service account from the TZ as Owner.
9. **Signing**: copy `keystore/keystore.properties.example` →
   `keystore.properties`, point it at your `.jks`.

## Build

```
gradlew assembleDebug                 # debug apk
gradlew assembleRelease bundleRelease # signed release apk + aab (needs keystore)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install with `adb install -r` (not `installDebug`) so it works on locked devices.

## Three things that will cost you a day if you skip them

1. **AppsFlyer is started in two strokes.** `init` in `AppEntry` (registers the
   callbacks, sends nothing), `start(activity)` in `WelcomePortal` only after
   connectivity is confirmed. Doing either from the other place silently delays
   attribution by ~30 s and files paid installs as organic.
2. **A first launch with no internet shows the no-wifi screen on the first
   frame** and persists nothing. Sending it to the game would lock a link
   install out of the WebView for good.
3. **The keyboard is handled by sliding the WebView, never by resizing it.**
   `adjustResize` plus `scrollIntoView` — the answer every tutorial gives —
   makes the content jump and fall back on every focus.

## Knowledge base

- `.cursor/rules/kotlin_gray_guide.mdc` — full architecture, state machine, config contract
- `.cursor/rules/kotlin_launch_flow.mdc` — attribution + offline contract (read before the router)
- `.cursor/rules/kotlin_webview.mdc` — WebView requirements
- `.cursor/rules/kotlin_keyboard.mdc` — keyboard, and why the obvious fixes fail
- `.cursor/rules/kotlin_gray_pitfalls.mdc` — real bugs + fixes
- `.cursor/rules/kotlin_fingerprint.mdc` — per-project uniqueness
- `.cursor/skills/gray-part-kotlin/SKILL.md` — deploy workflow
