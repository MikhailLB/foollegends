# Source assets (not bundled directly)

Put your **branded source art** here, then export/copy it into `res/` where the
app actually loads it. Nothing in this folder is referenced at runtime.

Required art for a complete gray-part app:

| Purpose | Where the app loads it | Notes |
|---------|------------------------|-------|
| Loading splash (portrait + landscape) | `LoadingView` (`res/drawable*`) | animated bar; must adapt to both orientations |
| Push-permission screen bg (portrait + landscape) | `res/drawable*/gray_notif_*` | replace the placeholder gradients |
| No-internet screen bg (portrait + landscape) | `res/drawable*/gray_nowifi_*` | replace the placeholder gradients |
| Launcher icon | `res/mipmap-*` + `res/drawable/ic_launcher_full.png` | adaptive, fills the shape, no borders |
| Notification icon | `res/drawable/ic_notif_*` | monochrome, NOT the launcher icon |

Prefer **WebP** for full-screen backgrounds (≈10× smaller than PNG). Reference
by resource name (extension-agnostic), so swapping PNG↔WebP needs no code change.
