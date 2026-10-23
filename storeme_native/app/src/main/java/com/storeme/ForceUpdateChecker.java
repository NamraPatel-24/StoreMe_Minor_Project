package com.storeme;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * ForceUpdateChecker
 * ------------------
 * On every app start, reads  /app_config  from Firebase Realtime Database.
 * If the device's versionCode < min_version_code  →  a NON-DISMISSIBLE dialog
 * blocks the entire app and shows a "Download Update" button.
 *
 * HOW TO FORCE AN UPDATE (no code change needed):
 * 1. Open Firebase Console → storemeapp-ef3bd → Realtime Database
 * 2. Set  /app_config/min_version_code  to the new required version number
 * 3. Set  /app_config/download_url     to the APK download link
 * 4. (Optional) Set  /app_config/update_message  for a custom message
 *
 * That's it — every old user will immediately see the update dialog.
 */
public class ForceUpdateChecker {

    /** Call this at the very start of OnboardingActivity and MainActivity. */
    public static void check(Activity activity, Runnable onAllowProceed) {
        // Show a lightweight loading state while we query Firebase
        // (usually < 500ms on a live connection)
        FirebaseDatabase.getInstance()
                .getReference("app_config")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (activity.isFinishing() || activity.isDestroyed()) return;

                        try {
                            // Safely parse minVersion (works whether it's stored as Number or String)
                            long minVersion = 0;
                            Object minVal = snapshot.child("min_version_code").getValue();
                            if (minVal != null) {
                                try {
                                    minVersion = Long.parseLong(String.valueOf(minVal));
                                } catch (Exception ignored) {}
                            }
                            
                            String downloadUrl = snapshot.child("download_url").getValue(String.class);
                            String message = snapshot.child("update_message").getValue(String.class);

                            if (downloadUrl == null || downloadUrl.isEmpty()) {
                                downloadUrl = "https://storemeweb.vercel.app/download";
                            }
                            if (message == null || message.isEmpty()) {
                                message = "A new version of StoreMe is available with improvements and new features. Please download the update to continue using the app.";
                            }

                            int currentVersion = getCurrentVersionCode(activity);

                            if (currentVersion < minVersion) {
                                // Block the app — show non-dismissible update dialog
                                showUpdateDialog(activity, message, downloadUrl);
                            } else {
                                // Up to date — let the app proceed normally
                                onAllowProceed.run();
                            }
                        } catch (Exception e) {
                            // If anything fails parsing, don't crash the app, just let them in
                            onAllowProceed.run();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // Firebase error (no internet etc.) — allow app to open
                        // We don't block offline users who can't reach Firebase
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            onAllowProceed.run();
                        }
                    }
                });
    }

    private static int getCurrentVersionCode(Activity activity) {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static void showUpdateDialog(Activity activity, String message, String downloadUrl) {
        try {
            // Build the dialog programmatically for full control over non-dismissibility
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            int pad = dp(activity, 28);
            root.setPadding(pad, pad, pad, pad);

            // 🔄 Icon
            TextView iconView = new TextView(activity);
            iconView.setText("🔄");
            iconView.setTextSize(52);
            iconView.setGravity(Gravity.CENTER);
            root.addView(iconView);

            // Title
            TextView title = new TextView(activity);
            title.setText("Update Required");
            title.setTextSize(20);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setTextColor(0xFF00172E);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleParams.setMargins(0, dp(activity, 12), 0, dp(activity, 12));
            title.setLayoutParams(titleParams);
            root.addView(title);

            // Message
            TextView msg = new TextView(activity);
            msg.setText(message);
            msg.setTextSize(14);
            msg.setTextColor(0xFF444444);
            msg.setGravity(Gravity.CENTER);
            msg.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            msgParams.setMargins(0, 0, 0, dp(activity, 24));
            msg.setLayoutParams(msgParams);
            root.addView(msg);

            // Version badge
            TextView versionBadge = new TextView(activity);
            versionBadge.setText("Current version: " + getCurrentVersionCode(activity));
            versionBadge.setTextSize(11);
            versionBadge.setTextColor(0xFF888888);
            versionBadge.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMargins(0, 0, 0, dp(activity, 20));
            versionBadge.setLayoutParams(badgeParams);
            root.addView(versionBadge);

            // Download button
            final String finalDownloadUrl = downloadUrl;
            Button downloadBtn = new Button(activity);
            downloadBtn.setText("⬇️  Download Update");
            downloadBtn.setTextColor(0xFFFFFFFF);
            downloadBtn.setTextSize(15);
            downloadBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
            btnBg.setColor(0xFF004A77);
            btnBg.setCornerRadius(dp(activity, 26));
            downloadBtn.setBackground(btnBg);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 52));
            downloadBtn.setLayoutParams(btnParams);
            downloadBtn.setOnClickListener(v -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(finalDownloadUrl));
                activity.startActivity(browserIntent);
            });
            root.addView(downloadBtn);

            // Build the dialog
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(root)
                    .setCancelable(false) // Cannot be dismissed by back button or outside touch
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.white);
            }

            if (!activity.isFinishing() && !activity.isDestroyed()) {
                dialog.show();

                // Double-lock: override back button on the Activity to do nothing
                activity.runOnUiThread(() -> {
                    View decorView = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
                    if (decorView != null) {
                        decorView.setOnKeyListener((v, keyCode, event) -> {
                            // Consume back key — user CANNOT dismiss
                            return keyCode == android.view.KeyEvent.KEYCODE_BACK;
                        });
                    }
                });
            }
        } catch (Exception e) {
            // Failsafe: if dialog rendering crashes due to context issues, don't crash app
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
