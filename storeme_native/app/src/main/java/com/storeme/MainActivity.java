package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.net.Uri;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private static final int STORAGE_PERMISSION_CODE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative_DarkAuth);
        super.onCreate(savedInstanceState);

        // Ensure full storage access before anything else
        if (!hasStoragePermission()) {
            requestStoragePermission();
            return;
        }

        // Request POST_NOTIFICATIONS for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, 
                    android.Manifest.permission.POST_NOTIFICATIONS) 
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        requestBatteryOptimizationExemption();

        com.google.firebase.auth.FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                android.util.Log.d("StoreMeNative", "signInAnonymously:success");
            } else {
                android.util.Log.w("StoreMeNative", "signInAnonymously:failure", task.getException());
            }
        });
        
        initApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check when user returns from Settings
        if (hasStoragePermission()) {
            // Only init if content view not already set
            if (findViewById(android.R.id.content) != null && !isFinishing()) {
                initApp();
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                // Show explanation dialog first
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Keep StoreMe Always Available")
                    .setMessage("To ensure your server stays online for 10+ hours without disconnecting, please allow StoreMe to run without battery restrictions.\n\nTap OK to open Battery settings.")
                    .setPositiveButton("OK", (dialog, which) -> {
                        try {
                            android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(android.net.Uri.parse("package:" + packageName));
                            startActivity(intent);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();
            }
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
            android.widget.Toast.makeText(this,
                    "Please grant 'Allow access to all files' then return to the app",
                    android.widget.Toast.LENGTH_LONG).show();
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, STORAGE_PERMISSION_CODE);
        }
    }

    private void initApp() {
        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String role = prefs.getString("active_role", null);
        String hostId = prefs.getString("host_id", "");
        String password = prefs.getString("password", "");
        String username = prefs.getString("username", "admin");

        boolean requireBiometric = prefs.getBoolean("require_biometric", false);

        if (("host".equals(role) || "client".equals(role)) && hostId != null && !hostId.isEmpty()) {
            if (requireBiometric) {
                showBiometricPrompt(role, hostId, username, password);
            } else {
                proceedToApp(role, hostId, username, password);
            }
            return;
        }

        setContentView(R.layout.activity_landing);

        findViewById(R.id.btnCreateServer).setOnClickListener(v -> {
            startActivity(new Intent(this, CreateServerActivity.class));
        });

        findViewById(R.id.btnUseServer).setOnClickListener(v -> {
            startActivity(new Intent(this, UseServerActivity.class));
        });
    }

    private void showBiometricPrompt(String role, String hostId, String username, String password) {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(MainActivity.this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                // On error, let them re-try or clear preferences to force manual login
                android.widget.Toast.makeText(getApplicationContext(),
                        "Authentication error: " + errString, android.widget.Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                proceedToApp(role, hostId, username, password);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                android.widget.Toast.makeText(getApplicationContext(), "Authentication failed",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock StoreMe")
                .setSubtitle("Enterprise Biometric Security")
                .setNegativeButtonText("Cancel")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void proceedToApp(String role, String hostId, String username, String password) {
        if ("host".equals(role)) {
            Intent intent = new Intent(this, DashboardActivity.class);
            intent.putExtra("HOST_ID", hostId);
            startActivity(intent);
            finish();
        } else if ("client".equals(role)) {
            ClientConnectionManager.getInstance().connect(getApplicationContext(), hostId, username, password);
            

            Intent intent = new Intent(this, ClientDashboardActivity.class);
            intent.putExtra("HOST_ID", hostId);
            intent.putExtra("PASSWORD", password);
            startActivity(intent);
            finish();
        }
    }
}
