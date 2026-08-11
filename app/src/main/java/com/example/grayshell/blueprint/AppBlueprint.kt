package com.example.grayshell.blueprint

import com.example.grayshell.BuildConfig
import com.example.grayshell.vault.Secrets

/**
 * Read-only view over the per-project fingerprint that the build encoded into
 * BuildConfig. Nothing here is hand-edited any more: to change any of these
 * values, change `gray.properties` (or the seed) and rebuild.
 *
 * The historical name "AppBlueprint" is kept here for compatibility, but the
 * class carries no logic of its own — every getter is a thin bridge to
 * BuildConfig or [Secrets]. rebrand.py can rename the file and its class name
 * across a project without touching semantics.
 */
object AppBlueprint {

    // ── Identity ────────────────────────────────────────────────────────────
    val bundleId: String     = BuildConfig.GRAY_BUNDLE_ID
    val appLabel: String     = BuildConfig.GRAY_APP_LABEL
    val appNameToken: String = BuildConfig.GRAY_UA_TOKEN

    // ── Timings — every one drawn from the per-project seed ─────────────────
    val attributionFirstMs: Long  = BuildConfig.ATTRIBUTION_FIRST_MS
    val attributionReturnMs: Long = BuildConfig.ATTRIBUTION_RETURN_MS
    val deepLinkWaitMs: Long      = BuildConfig.DEEP_LINK_WAIT_MS
    val configTimeoutMs: Long     = BuildConfig.CONFIG_TIMEOUT_MS
    val connectGraceMs: Long      = BuildConfig.CONNECT_GRACE_MS
    val safeAreaDelayMs: Long     = BuildConfig.SAFE_AREA_DELAY_MS
    val heartbeatMs: Long         = BuildConfig.HEARTBEAT_MS
    val pushSnoozeSeconds: Long   = BuildConfig.PUSH_SNOOZE_SEC
    val redirectRetryMax: Int     = BuildConfig.REDIRECT_RETRY_MAX

    // ── Secrets ─────────────────────────────────────────────────────────────
    fun resolveConfigEndpoint(): String  = Secrets.reveal(BuildConfig.SEC_CFG_ENDPOINT)
    fun resolveTrackerKey(): String      = Secrets.reveal(BuildConfig.SEC_AF_KEY)
    fun resolveAnalyticsProject(): String = Secrets.reveal(BuildConfig.SEC_FB_PROJECT)
    // ── Debug override ──────────────────────────────────────────────────────
    // Empty in release under all circumstances; the build script forces it.
    val debugForceStreamUrl: String get() = BuildConfig.DEBUG_FORCE_URL
}
