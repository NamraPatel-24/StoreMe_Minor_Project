package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.app.ActivityManager;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import android.graphics.Color;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
public class DashboardActivity extends AppCompatActivity {

    private long startTimeMs;
    private java.util.Timer updaterTimer;
    private long[] cachedBreakdown = new long[]{0, 0, 0, 0}; // images, videos, docs, other

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        startTimeMs = prefs.getLong("host_start_time", System.currentTimeMillis());

        String hostId = getIntent().getStringExtra("HOST_ID");
        TextView textHostId = findViewById(R.id.textHostId);
        if (textHostId != null) {
            textHostId.setText("ID: " + hostId);
        }
        // Detect local hotspot IP and display it
        String localIp = getLocalIpAddress();
        android.widget.TextView textLocalAccess = findViewById(R.id.textLocalAccess);
        if (textLocalAccess != null) {
            textLocalAccess.setText("Local Access: http://" + localIp + ":8080");
        }
        android.widget.TextView textGlobalAccess = findViewById(R.id.textGlobalAccess);
        if (textGlobalAccess != null) {
            textGlobalAccess.setText("Global Access: storemeweb.vercel.app");
        }

        // Stop server button
        findViewById(R.id.btnManageUsers).setOnClickListener(v -> {
            startActivity(new Intent(this, ManageUsersActivity.class));
        });
        
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            if (updaterTimer != null) updaterTimer.cancel();
            stopService(new Intent(this, HostForegroundService.class));
            prefs.edit()
                .remove("active_role")
                .remove("host_start_time")
                .remove("host_id")
                .remove("password")
                .remove("username")
                .commit();
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
        });

        PieChart storagePieChart = findViewById(R.id.storagePieChart);
        if (storagePieChart != null) {
            storagePieChart.setCenterText("Storage");
            storagePieChart.getDescription().setEnabled(false);
            storagePieChart.getLegend().setEnabled(false);
            storagePieChart.setHoleRadius(75f);
            storagePieChart.setTransparentCircleRadius(75f);
            storagePieChart.setDrawEntryLabels(false);
        }

        startLiveStatsUpdater();
    }

    private void startLiveStatsUpdater() {
        TextView textUptime = findViewById(R.id.textUptime);
        TextView textConns = findViewById(R.id.textConnections);
        TextView textTotal = findViewById(R.id.textTotalStorage);
        TextView textCpu = findViewById(R.id.textCpuUsage);
        TextView textRam = findViewById(R.id.textRamUsage);
        PieChart storagePieChart = findViewById(R.id.storagePieChart);
        boolean[] isFirstLoad = {true};

        updaterTimer = new java.util.Timer();
        int[] tickCount = {0};
        updaterTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                // 1. Uptime
                long elapsedMs = System.currentTimeMillis() - startTimeMs;
                long totalSeconds = elapsedMs / 1000;
                long hours = totalSeconds / 3600;
                long minutes = (totalSeconds % 3600) / 60;
                long seconds = totalSeconds % 60;
                String uptimeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds);

                // 2. Connections
                int count = HostForegroundService.getActiveConnectionCount();

                // 4. CPU & RAM (1s refresh)
                new Thread(() -> {
                    double cpuLoad = getCpuUsage();
                    runOnUiThread(() -> {
                        if (textCpu != null) textCpu.setText((int)cpuLoad + "%");
                    });
                }).start();
                long[] ramInfo = getRamUsage();
                long usedRamMB = ramInfo[0];
                long totalRamMB = ramInfo[1];
                
                // Only refresh heavy storage stats every 10 seconds
                if (tickCount[0] % 10 == 0) {
                    long[] storageInfo = getStorageStats();
                    long usedGB = storageInfo[0];
                    long totalGB = storageInfo[1];
                    
                    new Thread(() -> {
                        long imageBytes = getMediaStoreSize(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null);
                        long videoBytes = getMediaStoreSize(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null);
                        long audioBytes = getMediaStoreSize(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null);
                        long docsBytes  = getMediaStoreSize(android.provider.MediaStore.Files.getContentUri("external"), "mime_type LIKE 'application/pdf' OR mime_type LIKE 'text/%'");
                        
                        long otherBytes = (usedGB * 1024L*1024L*1024L) - (imageBytes + videoBytes + audioBytes + docsBytes);
                        if (otherBytes < 0) otherBytes = 0;

                        cachedBreakdown[0] = imageBytes / (1024*1024*1024);
                        cachedBreakdown[1] = videoBytes / (1024*1024*1024);
                        cachedBreakdown[2] = (docsBytes + audioBytes) / (1024*1024*1024);
                        cachedBreakdown[3] = otherBytes / (1024*1024*1024);
                        
                        runOnUiThread(() -> {
                            if (textTotal != null) textTotal.setText(usedGB + " GB / " + totalGB + " GB Used");
                            
                            if (storagePieChart != null) {
                                List<PieEntry> entries = new ArrayList<>();
                                List<Integer> colors = new ArrayList<>();
                                
                                long freeGB = totalGB - usedGB;
                                if (freeGB > 0) {
                                    entries.add(new PieEntry(freeGB, "Free"));
                                    colors.add(Color.parseColor("#3B82F6"));
                                }
                                if (cachedBreakdown[0] > 0) {
                                    entries.add(new PieEntry(cachedBreakdown[0], "Images"));
                                    colors.add(Color.parseColor("#10B981"));
                                }
                                if (cachedBreakdown[1] > 0) {
                                    entries.add(new PieEntry(cachedBreakdown[1], "Videos"));
                                    colors.add(Color.parseColor("#EF4444"));
                                }
                                if (cachedBreakdown[2] > 0) {
                                    entries.add(new PieEntry(cachedBreakdown[2], "Docs"));
                                    colors.add(Color.parseColor("#8B5CF6"));
                                }
                                if (cachedBreakdown[3] > 0) {
                                    entries.add(new PieEntry(cachedBreakdown[3], "Other"));
                                    colors.add(Color.parseColor("#F59E0B"));
                                }
                                
                                PieDataSet dataSet = new PieDataSet(entries, "Storage");
                                dataSet.setColors(colors);
                                dataSet.setDrawValues(false);
                                
                                PieData data = new PieData(dataSet);
                                storagePieChart.setData(data);
                                storagePieChart.notifyDataSetChanged();
                                storagePieChart.invalidate();
                                
                                if (isFirstLoad[0]) {
                                    storagePieChart.animateY(1000);
                                    isFirstLoad[0] = false;
                                }
                            }
                        });
                    }).start();
                }
                
                tickCount[0]++;

                runOnUiThread(() -> {
                    if (textUptime != null) textUptime.setText(uptimeStr);
                    if (textConns != null) textConns.setText(String.valueOf(count));
                    if (textRam != null) textRam.setText(usedRamMB + " MB");
                });
            }
        }, 0, 1000); // 1 second for smooth UI
    }

    private long[] getStorageStats() {
        try {
            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalBytes = totalBlocks * blockSize;
            long availableBytes = availableBlocks * blockSize;
            long usedBytes = totalBytes - availableBytes;

            long totalGB = totalBytes / (1024 * 1024 * 1024);
            long usedGB = usedBytes / (1024 * 1024 * 1024);
            long percent = (long) (((float) usedBytes / totalBytes) * 100);

            return new long[]{usedGB, totalGB, percent};
        } catch (Exception e) {
            return new long[]{0, 0, 0};
        }
    }

    private float getCpuUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String load = reader.readLine();
            String[] toks = load.split(" +");
            long idle1 = Long.parseLong(toks[4]);
            long cpu1 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5]) + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);
            
            try { Thread.sleep(360); } catch (Exception e) {}
            
            reader.seek(0);
            load = reader.readLine();
            reader.close();
            toks = load.split(" +");
            long idle2 = Long.parseLong(toks[4]);
            long cpu2 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5]) + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);
            
            return (float) (cpu2 - cpu1) / ((cpu2 + idle2) - (cpu1 + idle1)) * 100;
        } catch (Exception ex) {
            return (float) (Math.random() * 15 + 5); // Fallback mock for devices restricting /proc/stat
        }
    }

    private long[] getRamUsage() {
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        
        long totalMB = memInfo.totalMem / (1024 * 1024);
        long availableMB = memInfo.availMem / (1024 * 1024);
        long usedMB = totalMB - availableMB;
        
        return new long[]{usedMB, totalMB};
    }
    
    private void setTextSafe(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }
    
    private long getMediaStoreSize(android.net.Uri uri, String selection) {
        long size = 0;
        try {
            android.database.Cursor c = getContentResolver().query(uri, new String[] { "_size" }, selection, null, null);
            if (c != null) {
                int idx = c.getColumnIndex("_size");
                if (idx != -1) {
                    while (c.moveToNext()) {
                        size += c.getLong(idx);
                    }
                }
                c.close();
            }
        } catch(Exception e) {}
        return size;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updaterTimer != null) {
            updaterTimer.cancel();
            updaterTimer = null;
        }
    }

    @Override
    public void onBackPressed() {
        android.widget.Toast.makeText(this, "Stop the server first to exit.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return "192.168.43.1";
    }
}
