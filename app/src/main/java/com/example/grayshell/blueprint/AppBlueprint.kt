package com.example.grayshell.blueprint

import com.example.grayshell.vault.CipherVault

/**
 * Central configuration facade. Sensitive strings are XOR-obfuscated via
 * [CipherVault]; plain constants are non-sensitive metadata.
 *
 * ░░ SETUP CHECKLIST (do all of these per project) ░░
 *  1. Set [bundleId] / [appNameToken] to your real values (must match the
 *     applicationId in app/build.gradle.kts and your store listing).
 *  2. Encode each secret string with the SAME scheme CipherVault decodes
 *     (see CipherVault for the seed + formula) and paste the int arrays below:
 *       - configEndpointObs  = your "https://yourhost/config.php"
 *       - trackerKeyObs      = your AppsFlyer dev key
 *       - analyticsProjectObs= your Firebase project number
 *       - gcdBaseObs         = "https://gcdsdk.appsflyer.com/install_data/v4.0/"
 *     ALWAYS decode-verify each array equals the original before committing.
 *  3. Change the CipherVault seed + formula per project (fingerprint rule),
 *     then re-encode everything here.
 *  4. Leave [debugForceStreamUrl] EMPTY for release.
 *
 * While any array is empty its resolver returns "" and the gray flow safely
 * falls back to the native part (so the template builds and runs as-is).
 */
object AppBlueprint {

    // TODO(you): real bundle id + store-facing app name (PascalCase, no spaces).
    const val bundleId      = "com.example.grayshell"
    const val appLabel      = "Gray Shell"
    const val appNameToken  = "GrayShell"

    // Timeouts — match the guide. Usually no need to change.
    const val attributionFirstMs  = 30_000L   // first launch
    const val attributionReturnMs = 10_000L   // returning online user
    const val deepLinkWaitMs      =  5_000L
    const val configTimeoutMs     = 15_000L
    const val organicGcdDelayMs   =  5_000L
    const val gcdTimeoutMs        = 10_000L

    // ── Encoded secrets (XOR via CipherVault) — FILL THESE ──────────────
    // Empty = feature disabled (resolver returns ""). Paste your encoded arrays.

    // e.g. "https://yourhost.com/config.php"
    private val configEndpointObs = intArrayOf()

    // your AppsFlyer dev key
    private val trackerKeyObs = intArrayOf()

    // your Firebase project number
    private val analyticsProjectObs = intArrayOf()

    // "https://gcdsdk.appsflyer.com/install_data/v4.0/"
    private val gcdBaseObs = intArrayOf()

    // ── Resolvers ───────────────────────────────────────────────────────

    fun resolveConfigEndpoint(): String =
        if (configEndpointObs.isEmpty()) "" else CipherVault.reveal(configEndpointObs)
    fun resolveTrackerKey(): String =
        if (trackerKeyObs.isEmpty()) "" else CipherVault.reveal(trackerKeyObs)
    fun resolveAnalyticsProject(): String =
        if (analyticsProjectObs.isEmpty()) "" else CipherVault.reveal(analyticsProjectObs)
    fun resolveGcdBase(): String =
        if (gcdBaseObs.isEmpty()) "" else CipherVault.reveal(gcdBaseObs)

    // ── Debug override ──────────────────────────────────────────────────
    // Set to a URL to bypass the backend and open the WebView directly while
    // testing. MUST be empty in release builds.
    const val debugForceStreamUrl = ""
}
