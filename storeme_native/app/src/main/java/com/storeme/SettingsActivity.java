package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        String hostId = getIntent().getStringExtra("HOST_ID");

        // Host ID card - safe
        TextView textHostId = findViewById(R.id.textHostId);
        if (textHostId != null) textHostId.setText("Host ID: " + hostId);

        // Phone model - safe
        TextView textHostPhone = findViewById(R.id.textHostPhone);
        TextView textHostBattery = findViewById(R.id.textHostBattery);
        
        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String activeRole = prefs.getString("active_role", null);
        
        if ("host".equals(activeRole)) {
            if (textHostPhone != null) textHostPhone.setText(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            if (textHostBattery != null) {
                try {
                    android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                    android.content.Intent batteryStatus = registerReceiver(null, ifilter);
                    int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
                    int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) : -1;
                    int status = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) : -1;
                    boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                    float pct = level * 100 / (float)scale;
                    textHostBattery.setText((int)pct + "% " + (isCharging ? "⚡ Charging" : ""));
                } catch (Exception e) {}
            }
        } else {
            // Client - fetch from Host
            ClientConnectionManager.getInstance().addListener(new ClientConnectionManager.ClientListener() {
                @Override
                public void onConnected() {
                    try {
                        org.json.JSONObject req = new org.json.JSONObject();
                        req.put("action", "get_device_info");
                        ClientConnectionManager.getInstance().sendData(req.toString());
                    } catch (Exception e) {}
                }
                
                @Override
                public void onMessage(org.json.JSONObject msg) {
                    if ("device_info".equals(msg.optString("action"))) {
                        final String model = msg.optString("model", "Unknown Host");
                        final int battery = msg.optInt("battery", -1);
                        final boolean charging = msg.optBoolean("charging", false);
                        
                        runOnUiThread(() -> {
                            if (textHostPhone != null) textHostPhone.setText(model);
                            if (textHostBattery != null && battery >= 0) {
                                textHostBattery.setText(battery + "% " + (charging ? "⚡ Charging" : ""));
                            }
                        });
                    }
                }
                
                @Override public void onBinary(java.nio.ByteBuffer buffer) {}
                @Override public void onDisconnected() {}
            });
        }

        // Stop server button
        View btnStop = findViewById(R.id.btnStopServer);
        if (btnStop != null) {
            btnStop.setOnClickListener(v -> executeDisconnectLogic());
        }

        android.widget.Switch switchBiometric = findViewById(R.id.switchBiometric);
        if (switchBiometric != null) {
            switchBiometric.setChecked(prefs.getBoolean("require_biometric", false));
            switchBiometric.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("require_biometric", isChecked).apply();
            });
        }

        setupBottomNav(hostId);
    }

    private void setupBottomNav(String hostId) {
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_settings);
            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_files) {
                    Intent i = new Intent(this, FileManagerActivity.class);
                    i.putExtra("HOST_ID", hostId); startActivity(i); overridePendingTransition(0,0); finish();
                    return true;
                } else if (id == R.id.nav_activity) {
                    Intent i = new Intent(this, ActivityLogActivity.class);
                    i.putExtra("HOST_ID", hostId); startActivity(i); overridePendingTransition(0,0); finish();
                    return true;
                } else if (id == R.id.nav_info) {
                    SharedPreferences p = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                    Intent i = "host".equals(p.getString("active_role",null))
                        ? new Intent(this, DashboardActivity.class)
                        : new Intent(this, ClientDashboardActivity.class);
                    i.putExtra("HOST_ID", hostId); startActivity(i); overridePendingTransition(0,0); finish();
                    return true;
                }
                return true;
            });
        }
    }

    @Override
    public void onBackPressed() {
        android.widget.Toast.makeText(this,
                "Tap 'Stop Using Server' to disconnect.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void executeDisconnectLogic() {
        SharedPreferences btnPrefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String role = btnPrefs.getString("active_role", null);
        if ("host".equals(role)) {
            stopService(new Intent(this, HostForegroundService.class));
        } else {
            // Client: disconnect WebRTC
            ClientConnectionManager.getInstance().disconnect();
        }
        btnPrefs.edit()
                .remove("active_role")
                .remove("host_start_time")
                .remove("host_id")
                .remove("password")
                .remove("username")
                .apply();
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }
}
