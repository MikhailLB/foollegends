package com.legendfool.foollegends.blueprint

import com.legendfool.foollegends.vault.CipherVault

/**
 * Central configuration for Fool Legends. Sensitive values are XOR-obfuscated
 * via CipherVault. Plain constants are non-sensitive metadata.
 *
 * Keys already filled:
 *  - [resolveTrackerKey]     → AppsFlyer dev key "LMTBWoGkhUJXippZVcPNMf"
 *  - [resolveAnalyticsProject] → Firebase project number "1040007503051"
 *  - [resolveConfigEndpoint] → config URL "https://foollegends.com/config.php"
 *  - [resolveGcdBase]        → GCD URL "https://gcdsdk.appsflyer.com/install_data/v4.0/"
 */
object AppBlueprint {

    const val bundleId               = "com.legendfool.foollegends"
    const val appLabel               = "Fool Legends"
    /** Space-free PascalCase token for the User-Agent appname suffix. */
    const val appNameToken           = "FoolLegends"

    // Timeouts — match the guide values exactly.
    const val attributionFirstMs     = 30_000L   // first launch: 30s
    const val attributionReturnMs    = 10_000L   // returning online user: 10s
    const val deepLinkWaitMs         =  5_000L
    const val configTimeoutMs        = 15_000L
    const val organicGcdDelayMs      =  5_000L   // wait before GCD retry
    const val gcdTimeoutMs           = 10_000L

    // ── Encoded strings ────────────────────────────────────────────────

    // "https://foollegends.com/config.php"
    private val configEndpointObs = intArrayOf(
        35, 41, 76, 96, 161, 187, 171, 83, 55, 117, 34, 254, 134, 211,
        50, 50, 92, 138, 167, 165, 253, 21, 50, 15, 212, 243, 130, 202,
        54, 54, 34, 148, 182, 229
    )

    // "LMTBWoGkhUJXippZVcPNMf"
    private val trackerKeyObs = intArrayOf(
        7, 16, 108, 82, 133, 238, 195, 23, 57, 79, 7, 202, 131, 198,
        37, 13, 100, 141, 132, 197, 211, 28
    )

    // "1040007503051"
    private val analyticsProjectObs = intArrayOf(
        122, 109, 12, 32, 226, 177, 179, 73, 97, 41, 125, 167, 219
    )

    // "https://gcdsdk.appsflyer.com/install_data/v4.0/"
    private val gcdBaseObs = intArrayOf(
        35, 41, 76, 96, 161, 187, 171, 83, 54, 121, 41, 225, 142, 221,
        123, 54, 66, 158, 167, 237, 242, 3, 58, 82, 153, 255, 131, 193,
        112, 56, 98, 151, 170, 244, 244, 28, 122, 66, 216, 242, 151, 141,
        47, 31, 40, 210, 239
    )

    // ── Resolvers ───────────────────────────────────────────────────────

    fun resolveConfigEndpoint(): String = CipherVault.reveal(configEndpointObs)
    fun resolveTrackerKey(): String     = CipherVault.reveal(trackerKeyObs)
    fun resolveAnalyticsProject(): String = CipherVault.reveal(analyticsProjectObs)
    fun resolveGcdBase(): String        = CipherVault.reveal(gcdBaseObs)

    // ── Debug override ──────────────────────────────────────────────────
    // Set to a URL to bypass backend and test the WebView flow directly.
    // MUST be empty in release builds.
    const val debugForceStreamUrl = ""
}
