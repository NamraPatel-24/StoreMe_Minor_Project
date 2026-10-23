package com.storeme;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * ProManager — Singleton that checks Pro status and enforces feature gates.
 *
 * Usage anywhere in the app:
 *   if (!ProManager.checkFeature(this, ProConfig.REQUIRE_PRO_MULTI_USER)) return;
 *   // ... proceed with the Pro feature
 */
public class ProManager {

    private static final String PREF_NAME   = "StoreMePrefs";
    private static final String PREF_IS_PRO = "is_pro_user";

    // ── Check if user has Pro ────────────────────────────────
    public static boolean isPro(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_IS_PRO, false);
    }

    // ── Activate Pro (call this after successful payment) ────
    public static void activatePro(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(PREF_IS_PRO, true).apply();
    }

    // ── Deactivate Pro (for testing / refunds) ───────────────
    public static void deactivatePro(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean(PREF_IS_PRO, false).apply();
    }

    /**
     * Gate check — call before any Pro feature.
     *
     * @param activity      The current activity (used to launch Upgrade screen)
     * @param requiresPro   The ProConfig.REQUIRE_PRO_* flag for this feature
     * @return  true  → OK to proceed (either feature is free, or user is Pro)
     *          false → Blocked. Upgrade screen is launched automatically.
     */
    public static boolean checkFeature(Activity activity, boolean requiresPro) {
        if (!requiresPro || isPro(activity)) return true;
        // Launch the Upgrade screen
        Intent intent = new Intent(activity, UpgradeProActivity.class);
        activity.startActivity(intent);
        return false;
    }

    /**
     * Gate check for file size (large file streaming).
     * @return true if the file is small enough for Free tier, or user is Pro.
     */
    public static boolean checkFileSize(Activity activity, long fileBytes) {
        if (!ProConfig.REQUIRE_PRO_LARGE_FILE_STREAM) return true;
        if (isPro(activity)) return true;
        if (fileBytes <= ProConfig.FREE_TIER_MAX_PREVIEW_BYTES) return true;
        Intent intent = new Intent(activity, UpgradeProActivity.class);
        intent.putExtra("reason", "large_file");
        activity.startActivity(intent);
        return false;
    }

    /**
     * Gate check for user count.
     * @param currentUserCount  The current number of non-admin users.
     * @return true if adding another user is allowed.
     */
    public static boolean checkUserLimit(Activity activity, int currentUserCount) {
        if (!ProConfig.REQUIRE_PRO_MULTI_USER) return true;
        if (isPro(activity)) return true;
        if (currentUserCount < ProConfig.FREE_TIER_MAX_USERS) return true;
        Intent intent = new Intent(activity, UpgradeProActivity.class);
        intent.putExtra("reason", "multi_user");
        activity.startActivity(intent);
        return false;
    }
}
