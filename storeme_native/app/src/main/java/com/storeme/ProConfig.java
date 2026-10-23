package com.storeme;

/**
 * ============================================================
 *  StoreMe Pro — Feature Gate Configuration
 *  ============================================================
 *  As a developer, simply set any feature to TRUE to lock it
 *  behind the Pro paywall, or FALSE to give it away for free.
 *
 *  This is the ONLY file you need to edit to change what is
 *  free vs. premium. No other code needs to change.
 * ============================================================
 */
public class ProConfig {

    // ─── SERVER FEATURES ────────────────────────────────────
    /** Lock the advanced Enterprise Dashboard (CPU/RAM/Storage analytics) */
    public static final boolean REQUIRE_PRO_ADVANCED_DASHBOARD = true;

    /** Lock creating more than 1 client user account */
    public static final boolean REQUIRE_PRO_MULTI_USER = true;

    /** Maximum number of users allowed on the FREE tier */
    public static final int FREE_TIER_MAX_USERS = 1;

    // ─── FILE TRANSFER FEATURES ─────────────────────────────
    /** Lock large file streaming (video preview for files over the size below) */
    public static final boolean REQUIRE_PRO_LARGE_FILE_STREAM = true;

    /** Files SMALLER than this (in bytes) can be previewed on Free tier (100 MB) */
    public static final long FREE_TIER_MAX_PREVIEW_BYTES = 100L * 1024 * 1024;

    /** Lock upload from web client (host can still receive from Android client for free) */
    public static final boolean REQUIRE_PRO_WEB_UPLOAD = false;

    // ─── SECURITY FEATURES ──────────────────────────────────
    /** Lock biometric/fingerprint app lock */
    public static final boolean REQUIRE_PRO_BIOMETRIC = true;

    /** Lock the "Share Link" feature (create shareable access tokens) */
    public static final boolean REQUIRE_PRO_SHARE_LINKS = true;

    // ─── PRICING ────────────────────────────────────────────
    /** Your Google Play Billing product ID — set this before publishing */
    public static final String BILLING_PRODUCT_ID = "storeme_pro_lifetime";

    /** Price shown in the upgrade screen (update to match your Play Console price) */
    public static final String DISPLAY_PRICE = "$3.99";

    /** App name shown in upgrade dialogs */
    public static final String APP_NAME = "StoreMe";
}
