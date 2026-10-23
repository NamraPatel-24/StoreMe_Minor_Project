package com.storeme;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import com.storeme.rbac.Role;
import com.storeme.rbac.User;
import com.storeme.rbac.SessionManager;
import com.storeme.rbac.OwnershipStore;

public class HostForegroundService extends Service {
    private static final String CHANNEL_ID = "StoreMeNativeChannel";
    private DatabaseReference dbRef;
    private String hostId;
    private String password;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private HostStreamServer streamServer;
    private LocalHttpServer localHttpServer;
    private static java.util.concurrent.ExecutorService rangeExecutor;
    private static volatile String cancelledRangeId = "";
    private java.util.Timer heartbeatTimer;
    private com.google.firebase.database.DatabaseReference heartbeatRef;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        hostId = intent.getStringExtra("HOST_ID");
        password = intent.getStringExtra("PASSWORD");

        // Tap notification → open DashboardActivity
        Intent openIntent = new Intent(this, DashboardActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = buildNotification(
                "🟢 StoreMe Server is Running",
                "ID: " + hostId + "  •  Tap to open dashboard",
                pendingIntent);

        // Android 10+ (API 29) requires the foreground service type.
        // Android 9 (API 28) only supports the basic 2-argument form.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        Log.i("StoreMeNative", "Native Background Service Started. Host: " + hostId);

        // Acquire WakeLock for robust background transfers
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "StoreMe::HostingWakeLock");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) wakeLock.acquire();
        }
        
        android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StoreMe::WifiLock");
            wifiLock.acquire();
        }
        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        if (prefs.getLong("host_start_time", 0) == 0) {
            prefs.edit().putLong("host_start_time", System.currentTimeMillis()).commit();
        }

        try {
            streamServer = new HostStreamServer(8081);
            streamServer.start();
        } catch (Exception e) {}
        
        try {
            localHttpServer = new LocalHttpServer(this);
            localHttpServer.start();
            android.util.Log.i("StoreMe", "Local HTTP server started on port 8080");
        } catch (Exception e) {
            android.util.Log.e("StoreMe", "Failed to start local HTTP server: " + e.getMessage());
        }

        // Initialize Firebase Listeners
        dbRef = FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("connections");
        
        // Clean up any stale sessions from previous crash or disconnect
        FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("connections").removeValue();
        FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("status").setValue("online");
        // Set onDisconnect handler - auto-cleans Firebase if app dies
        FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("status")
                .onDisconnect().setValue("offline");
        FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("connections")
                .onDisconnect().removeValue();

        startFirebaseHeartbeat();

        // Monitor Firebase connection state and auto-reconnect
        FirebaseDatabase.getInstance()
            .getReference(".info/connected")
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    Boolean connected = snapshot.getValue(Boolean.class);
                    if (Boolean.TRUE.equals(connected)) {
                        android.util.Log.d("StoreMe", "Firebase reconnected! Refreshing room status.");
                        // Re-register online status whenever we reconnect
                        if (hostId != null) {
                            FirebaseDatabase.getInstance()
                                .getReference("rooms").child(hostId).child("status")
                                .setValue("online");
                            FirebaseDatabase.getInstance()
                                .getReference("rooms").child(hostId).child("status")
                                .onDisconnect().setValue("offline");
                        }
                    } else {
                        android.util.Log.w("StoreMe", "Firebase disconnected (Doze/network loss). Will auto-reconnect.");
                    }
                }
                @Override
                public void onCancelled(DatabaseError error) {
                    android.util.Log.e("StoreMe", "Firebase connection listener cancelled: " + error.getMessage());
                }
            });

        listenForClients();

        return START_STICKY;
    }

    private void startFirebaseHeartbeat() {
        if (hostId == null || hostId.isEmpty()) return;
        
        heartbeatRef = FirebaseDatabase.getInstance()
            .getReference("rooms").child(hostId).child("last_active");
        
        // Keep Firebase connection alive (prevents silent WebSocket drop)
        FirebaseDatabase.getInstance()
            .getReference("rooms").child(hostId).keepSynced(true);
        
        // Cancel any existing heartbeat timer
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        
        heartbeatTimer = new java.util.Timer("StoreMe-Heartbeat", true);
        heartbeatTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    // 1. Write heartbeat timestamp - proves Firebase is still connected
                    long now = System.currentTimeMillis();
                    heartbeatRef.setValue(now);
                    
                    // 2. Force Firebase connection check
                    FirebaseDatabase.getInstance()
                        .getReference(".info/connected")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot snapshot) {
                                Boolean connected = snapshot.getValue(Boolean.class);
                                if (connected == null || !connected) {
                                    // Firebase disconnected! Force reconnect by going offline then online
                                    android.util.Log.w("StoreMe", "Heartbeat: Firebase disconnected! Forcing reconnect...");
                                    FirebaseDatabase.getInstance().goOffline();
                                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                                    FirebaseDatabase.getInstance().goOnline();
                                    
                                    // Re-write status so clients know we're online
                                    FirebaseDatabase.getInstance()
                                        .getReference("rooms").child(hostId).child("status")
                                        .setValue("online");
                                } else {
                                    android.util.Log.d("StoreMe", "Heartbeat: Firebase OK at " + now);
                                }
                            }
                            @Override
                            public void onCancelled(DatabaseError error) {
                                // Force reconnect on error
                                FirebaseDatabase.getInstance().goOffline();
                                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                                FirebaseDatabase.getInstance().goOnline();
                            }
                        });
                } catch (Exception e) {
                    android.util.Log.e("StoreMe", "Heartbeat error: " + e.getMessage());
                }
            }
        }, 10000L, 60000L); // Start after 10s, repeat every 60 seconds
        
        android.util.Log.d("StoreMe", "Firebase heartbeat started (every 60 seconds)");
    }

    private void stopFirebaseHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        if (heartbeatRef != null) {
            heartbeatRef.removeValue();
        }
    }

    private void listenForClients() {
        Log.i("StoreMeNative", "Listening for incoming WebRTC connections...");
        
        dbRef.addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot clientSnapshot, String previousChildName) {
                String clientId = clientSnapshot.getKey();
                if (clientId == null) return;
                Log.i("StoreMeNative", "New client detected: " + clientId);
                handleClientConnection(clientId);
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private java.util.Map<String, Thread> activeTransfers = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.Map<String, Boolean> transferStatus = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.Map<String, WebRTCManager> activePeers = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.Map<String, java.io.RandomAccessFile> openUploads = new java.util.concurrent.ConcurrentHashMap<>();
    private String currentStreamPathHost = null;

    public static int getActiveConnectionCount() {
        return activePeers.size();
    }

    private void handleClientConnection(String clientId) {
        final WebRTCManager[] rtcHolder = new WebRTCManager[1];
        final String[] connectedUsername = new String[1];
        final String[] currentBinaryUploadPath = new String[1];
        
        WebRTCManager rtcManager = new WebRTCManager(this, new WebRTCManager.WebRTCListener() {
            @Override
            public void onIceCandidate(org.webrtc.IceCandidate candidate) {
                dbRef.child(clientId).child("candidates").child("host").push().setValue(new CandidatePayload(
                        candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                ));
            }

            private boolean isPathAllowed(String checkPath) {
                if ("VirtualShare".equals(checkPath) && connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) return true;
                if (connectedUsername[0] == null) return false;

                android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                String usersJson = prefs.getString("host_users", "[]");
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                    boolean userFound = false;
                    for (int i=0; i<arr.length(); i++) {
                        org.json.JSONObject u = arr.getJSONObject(i);
                        if (u.optString("username").equals(connectedUsername[0])) {
                            userFound = true;
                            org.json.JSONArray perms = u.optJSONArray("permissions");
                            if (perms != null) {
                                for (int j=0; j<perms.length(); j++) {
                                    String allowed = perms.getString(j);
                                    if (allowed.equals("/") || checkPath.startsWith(allowed)) return true;
                                }
                            }
                            break;
                        }
                    }
                    // If user was not found in prefs yet (race condition on first connect),
                    // grant access — they already passed WebRTC authentication.
                    if (!userFound) return true;
                } catch (Exception e) {}
                return false;
            }


            @Override
            public void onDataChannelMessage(String message) {
                Log.i("StoreMeNative", "Message from client: " + message);
                try {
                    org.json.JSONObject req = new org.json.JSONObject(message);
                    String action = req.optString("action");
                    String path = req.optString("path");

                    // 1. WebRTC Auth action
                    if ("auth".equals(action)) {
                        String authUser = req.optString("username", "").trim();
                        String authPass = req.optString("password", "").trim();
                        String token = SessionManager.getInstance(HostForegroundService.this).login(authUser, authPass);
                        org.json.JSONObject authRes = new org.json.JSONObject();
                        if (token != null) {
                            connectedUsername[0] = authUser;
                            User au = SessionManager.getInstance(HostForegroundService.this).getUser(authUser);
                            authRes.put("action", "auth_success");
                            authRes.put("role", au != null ? au.getRole().getValue() : "viewer");
                            authRes.put("token", token);
                        } else {
                            authRes.put("action", "auth_failed");
                            authRes.put("status", 401);
                            authRes.put("message", "Invalid credentials");
                        }
                        if (rtcHolder[0] != null) rtcHolder[0].sendData(authRes.toString());
                        return;
                    }

                    if (path.isEmpty() || path.equals("/")) {
                        // Resolve empty or root path → first allowed path or internal storage root
                        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                        boolean foundPerm = false;
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("host_users", "[]"));
                            for (int i=0; i<arr.length(); i++) {
                                org.json.JSONObject u = arr.getJSONObject(i);
                                if (u.optString("username").equals(connectedUsername[0])) {
                                    org.json.JSONArray perms = u.optJSONArray("permissions");
                                    if (perms != null && perms.length() > 0) {
                                        path = perms.getString(0);
                                        // If root permission, redirect straight to /storage/emulated/0
                                        if (path.equals("/") || path.equals("/storage") || path.equals("/storage/emulated")) {
                                            path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                                        }
                                        foundPerm = true;
                                    }
                                    break;
                                }
                            }
                        } catch (Exception e) {}
                        if (!foundPerm || path.isEmpty()) {
                            path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                        }
                    }

                    // Auto-correct system dirs that Android blocks — redirect to /storage/emulated/0
                    if (path.equals("/storage") || path.equals("/storage/emulated")) {
                        path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                    }

                    final String effectivePath = path;

                    // Enforce RBAC roles (Viewer, Uploader, Admin)
                    String currentClientUser = connectedUsername[0];
                    Role clientRole = Role.VIEWER;
                    if (currentClientUser != null) {
                        if (currentClientUser.startsWith("share_user_")) {
                            clientRole = Role.VIEWER;
                        } else {
                            User u = SessionManager.getInstance(HostForegroundService.this).getUser(currentClientUser);
                            if (u != null) {
                                clientRole = u.getRole();
                            } else if ("admin".equalsIgnoreCase(currentClientUser)) {
                                clientRole = Role.ADMIN;
                            }
                        }
                    }

                    // Enforce path prefix permissions
                    if (clientRole != Role.ADMIN && !isPathAllowed(effectivePath)) {
                        org.json.JSONObject res = new org.json.JSONObject();
                        res.put("action", "access_denied");
                        res.put("path", effectivePath);
                        if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        return;
                    }

                    if (clientRole == Role.VIEWER) {
                        if ("upload_start".equals(action) || "upload_chunk".equals(action) || "delete".equals(action) ||
                            "delete_permanent".equals(action) || "rename".equals(action) || "move".equals(action) ||
                            "zip".equals(action) || "unzip".equals(action)) {
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "access_denied");
                            res.put("status", 403);
                            res.put("message", "Viewer role is not permitted to perform this operation");
                            res.put("path", effectivePath);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            return;
                        }
                    }

                    if (clientRole == Role.UPLOADER) {
                        if ("update_settings".equals(action) || "modify_settings".equals(action) ||
                            "manage_users".equals(action) || "list_users".equals(action)) {
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "access_denied");
                            res.put("status", 403);
                            res.put("message", "Only Admin can modify server settings");
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            return;
                        }
                        if ("delete".equals(action) || "delete_permanent".equals(action) || "rename".equals(action) || "move".equals(action)) {
                            String owner = OwnershipStore.getInstance(HostForegroundService.this).getOwner(effectivePath);
                            if (!currentClientUser.equals(owner)) {
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "access_denied");
                                res.put("status", 403);
                                res.put("message", "Cannot delete files owned by another user");
                                res.put("path", effectivePath);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                                return;
                            }
                        }
                    }

                    if ("list_dir".equals(action)) {
                        if ("VirtualShare".equals(effectivePath)) {
                            org.json.JSONArray perms = null;
                            android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", android.content.Context.MODE_PRIVATE);
                            try {
                                org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("host_users", "[]"));
                                for (int i=0; i<arr.length(); i++) {
                                    org.json.JSONObject u = arr.getJSONObject(i);
                                    if (u.optString("username").equals(connectedUsername[0])) { perms = u.optJSONArray("permissions"); break; }
                                }
                            } catch(Exception e){}
                            
                            org.json.JSONArray filesArr = new org.json.JSONArray();
                            if (perms != null) {
                                for (int i=0; i<perms.length(); i++) {
                                    try {
                                        java.io.File f = new java.io.File(perms.getString(i));
                                        if (f.exists()) {
                                            org.json.JSONObject fileObj = new org.json.JSONObject();
                                            fileObj.put("name", f.getName());
                                            fileObj.put("path", f.getAbsolutePath());
                                            fileObj.put("isDir", f.isDirectory());
                                            fileObj.put("size", f.length());
                                            fileObj.put("modified", f.lastModified());
                                            fileObj.put("owner", OwnershipStore.getInstance(HostForegroundService.this).getOwner(f.getAbsolutePath()));
                                            filesArr.put(fileObj);
                                        }
                                    } catch(Exception e){}
                                }
                            }
                            try {
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "directory_listing");
                                res.put("path", "VirtualShare");
                                res.put("files", filesArr);
                                res.put("chunk", 1);
                                res.put("total_chunks", 1);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            } catch(Exception e){}
                            return;
                        }
                        java.io.File dir = new java.io.File(effectivePath);
                        Log.i("StoreMeNative", "list_dir: path=" + effectivePath + " exists=" + dir.exists() + " isDir=" + dir.isDirectory());
                        
                        if (dir.exists() && dir.isDirectory()) {
                            java.io.File[] files = dir.listFiles();
                            Log.i("StoreMeNative", "listFiles result: " + (files == null ? "NULL (no permission!)" : files.length + " files"));
                            
                            if (files == null) {
                                // listFiles() returned null — either permission denied or unreadable system dir.
                                // Fall back to showing all accessible storage volumes.
                                org.json.JSONArray volumeArray = new org.json.JSONArray();

                                // Primary internal storage
                                java.io.File primary = android.os.Environment.getExternalStorageDirectory();
                                if (primary.exists()) {
                                    org.json.JSONObject f0 = new org.json.JSONObject();
                                    f0.put("name", "Internal Storage");
                                    f0.put("path", primary.getAbsolutePath());
                                    f0.put("isDir", true);
                                    f0.put("size", 0);
                                    volumeArray.put(f0);
                                }

                                // External SD cards / USB drives
                                java.io.File[] externalDirs = getExternalFilesDirs(null);
                                if (externalDirs != null) {
                                    for (java.io.File extDir : externalDirs) {
                                        if (extDir == null) continue;
                                        // getExternalFilesDirs gives app-private dir; get the volume root
                                        String extPath = extDir.getAbsolutePath();
                                        // Trim to volume root: everything up to /Android/data/...
                                        int androidIdx = extPath.indexOf("/Android/data/");
                                        if (androidIdx > 0) extPath = extPath.substring(0, androidIdx);
                                        java.io.File extRoot = new java.io.File(extPath);
                                        if (!extRoot.getAbsolutePath().equals(primary.getAbsolutePath()) && extRoot.exists()) {
                                            org.json.JSONObject fx = new org.json.JSONObject();
                                            fx.put("name", "SD Card / External (" + extRoot.getName() + ")");
                                            fx.put("path", extRoot.getAbsolutePath());
                                            fx.put("isDir", true);
                                            fx.put("size", 0);
                                            volumeArray.put(fx);
                                        }
                                    }
                                }

                                org.json.JSONObject errRes = new org.json.JSONObject();
                                errRes.put("action", "directory_listing");
                                errRes.put("path", primary.getAbsolutePath()); // navigate to primary
                                errRes.put("files", volumeArray);
                                errRes.put("chunk", 1);
                                errRes.put("total_chunks", 1);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(errRes.toString());
                            } else if (files.length == 0) {
                                // Directory is genuinely empty
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "directory_listing");
                                res.put("path", dir.getAbsolutePath());
                                res.put("files", new org.json.JSONArray());
                                res.put("chunk", 1);
                                res.put("total_chunks", 1);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            } else {
                                // Build full file list, and if this is the primary storage root,
                                // append external SD cards / USB drives so users can browse them.
                                java.util.List<java.io.File> allFiles = new java.util.ArrayList<>(java.util.Arrays.asList(files));

                                // Detect external volumes and inject at the top when listing storage root
                                String primaryPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                                if (dir.getAbsolutePath().equals(primaryPath)) {
                                    java.io.File[] externalDirs = getExternalFilesDirs(null);
                                    if (externalDirs != null) {
                                        for (java.io.File extDir : externalDirs) {
                                            if (extDir == null) continue;
                                            String extPath = extDir.getAbsolutePath();
                                            int androidIdx = extPath.indexOf("/Android/data/");
                                            if (androidIdx > 0) extPath = extPath.substring(0, androidIdx);
                                            java.io.File extRoot = new java.io.File(extPath);
                                            if (!extRoot.getAbsolutePath().equals(primaryPath) && extRoot.exists() && extRoot.canRead()) {
                                                // Inject at front so it's visible immediately
                                                allFiles.add(0, extRoot);
                                            }
                                        }
                                    }
                                }

                                int chunkSize = 50;
                                int totalChunks = (int) Math.ceil((double) allFiles.size() / chunkSize);
                                if (totalChunks == 0) totalChunks = 1;

                                for (int c = 0; c < totalChunks; c++) {
                                    org.json.JSONArray batchArray = new org.json.JSONArray();
                                    int start = c * chunkSize;
                                    int end = Math.min(start + chunkSize, allFiles.size());

                                    for (int i = start; i < end; i++) {
                                        java.io.File f = allFiles.get(i);
                                        org.json.JSONObject fileObj = new org.json.JSONObject();
                                        // Give external roots a friendly name
                                        String fname = f.getName();
                                        if (!f.getAbsolutePath().startsWith(primaryPath) && f.isDirectory()) {
                                            fname = "💾 SD Card (" + fname + ")";
                                        }
                                        fileObj.put("name", fname);
                                        fileObj.put("path", f.getAbsolutePath());
                                        fileObj.put("isDir", f.isDirectory());
                                        fileObj.put("size", f.length());
                                        fileObj.put("owner", OwnershipStore.getInstance(HostForegroundService.this).getOwner(f.getAbsolutePath()));
                                        batchArray.put(fileObj);
                                    }

                                    org.json.JSONObject res = new org.json.JSONObject();
                                    res.put("action", "directory_listing");
                                    res.put("path", dir.getAbsolutePath());
                                    res.put("files", batchArray);
                                    res.put("chunk", c + 1);
                                    res.put("total_chunks", totalChunks);
                                    if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                                }
                            }
                        } else if (dir.exists() && dir.isFile()) {
                            try {
                                org.json.JSONArray batchArray = new org.json.JSONArray();
                                org.json.JSONObject fileObj = new org.json.JSONObject();
                                fileObj.put("name", dir.getName());
                                fileObj.put("path", dir.getAbsolutePath());
                                fileObj.put("isDir", false);
                                fileObj.put("size", dir.length());
                                fileObj.put("owner", OwnershipStore.getInstance(HostForegroundService.this).getOwner(dir.getAbsolutePath()));
                                batchArray.put(fileObj);

                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "directory_listing");
                                res.put("path", dir.getParent() != null ? dir.getParent() : dir.getAbsolutePath());
                                res.put("files", batchArray);
                                res.put("chunk", 1);
                                res.put("total_chunks", 1);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            } catch (Exception e) {}
                        } else {
                            // Directory does not exist
                            Log.e("StoreMeNative", "list_dir: path does not exist: " + effectivePath);
                            org.json.JSONObject errRes = new org.json.JSONObject();
                            errRes.put("action", "directory_listing");
                            errRes.put("path", effectivePath);
                            errRes.put("files", new org.json.JSONArray());
                            errRes.put("chunk", 1);
                            errRes.put("total_chunks", 1);
                            errRes.put("error", "Path does not exist: " + effectivePath);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(errRes.toString());
                        }
                    } else if ("stream".equals(action) && !effectivePath.isEmpty()) {
                        long offset = req.optLong("offset", 0);
                        if (rtcHolder[0] != null) {
                            Thread old = activeTransfers.remove(effectivePath);
                            if (old != null) {
                                transferStatus.put(effectivePath, false);
                                old.interrupt();
                                try { old.join(200); } catch (Exception e) {}
                            }
                            transferStatus.put(effectivePath, true);
                            Thread t = new Thread(() -> streamFileOverWebRTC(effectivePath, rtcHolder[0], "stream", offset));
                            activeTransfers.put(effectivePath, t);
                            t.start();
                        }
                    
                    } else if ("upload_start".equals(action) && !effectivePath.isEmpty()) {
                        if (connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) {
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "access_denied");
                            res.put("path", effectivePath);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            return;
                        }
                            try {
                                java.io.File f = new java.io.File(effectivePath);
                                if (!f.exists()) {
                                    f.getParentFile().mkdirs();
                                    f.createNewFile();
                                } else {
                                    String currentOwner = OwnershipStore.getInstance(HostForegroundService.this).getOwner(effectivePath);
                                    if (clientRole == Role.UPLOADER && !currentClientUser.equals(currentOwner)) {
                                        org.json.JSONObject res = new org.json.JSONObject();
                                        res.put("action", "access_denied");
                                        res.put("status", 403);
                                        res.put("message", "Cannot overwrite files owned by another user");
                                        res.put("path", effectivePath);
                                        if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                                        return;
                                    }
                                }
                                long existingSize = f.length();
                            
                            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(effectivePath, "rw");
                            raf.seek(existingSize);
                            openUploads.put(effectivePath, raf);
                            currentBinaryUploadPath[0] = effectivePath;
                            
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "upload_ack");
                            res.put("path", effectivePath);
                            res.put("offset", existingSize); // Tell client to resume from end of file
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    } else if ("upload_chunk".equals(action) && !effectivePath.isEmpty()) {
                        if (connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) {
                            return;
                        }
                        try {
                            long offset = req.optLong("offset");
                            String base64 = req.optString("data");
                            byte[] data = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                            
                            java.io.RandomAccessFile raf = openUploads.get(effectivePath);
                            if (raf == null) {
                                raf = new java.io.RandomAccessFile(effectivePath, "rw");
                                openUploads.put(effectivePath, raf);
                            }
                            
                            raf.seek(offset);
                            raf.write(data);
                            // Do not close raf and do not send upload_ack to allow continuous streaming
                        } catch (Exception e) {}

                    } else if ("upload_end".equals(action) && !effectivePath.isEmpty()) {
                        try {
                            java.io.RandomAccessFile raf = openUploads.remove(effectivePath);
                            if (raf != null) {
                                raf.close();
                            }
                            java.io.File upFile = new java.io.File(effectivePath);
                            OwnershipStore.getInstance(HostForegroundService.this).setOwner(
                                effectivePath,
                                currentClientUser != null ? currentClientUser : "admin",
                                clientRole,
                                upFile.exists() ? upFile.length() : 0
                            );
                        } catch (Exception e) {}

                    } else if ("upload_cancel".equals(action) && !effectivePath.isEmpty()) {
                        // Client cancelled upload — close and delete the partial file
                        try {
                            java.io.RandomAccessFile raf = openUploads.remove(effectivePath);
                            if (raf != null) raf.close();
                            new java.io.File(effectivePath).delete();
                            currentBinaryUploadPath[0] = null;
                        } catch (Exception e) {}
                    } else if ("download".equals(action) && !effectivePath.isEmpty()) {
                        long offset = req.optLong("offset", 0);
                        if (rtcHolder[0] != null) {
                            Thread old = activeTransfers.remove(effectivePath);
                            if (old != null) {
                                transferStatus.put(effectivePath, false);
                                old.interrupt();
                                try { old.join(200); } catch (Exception e) {}
                            }
                            transferStatus.put(effectivePath, true);
                            Thread t = new Thread(() -> streamFileOverWebRTC(effectivePath, rtcHolder[0], "download", offset));
                            activeTransfers.put(effectivePath, t);
                            t.start();
                        }
                    } else if ("download_zip".equals(action)) {
                        org.json.JSONArray paths = req.optJSONArray("paths");
                        if (paths != null && paths.length() > 0 && rtcHolder[0] != null) {
                            java.io.File tempZip = new java.io.File(getCacheDir(), "multi_download_" + System.currentTimeMillis() + ".zip");
                            new Thread(() -> {
                                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(tempZip))) {
                                    for (int i = 0; i < paths.length(); i++) {
                                        String fp = paths.optString(i);
                                        java.io.File f = new java.io.File(fp);
                                        if (f.exists() && f.isFile()) {
                                            java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(f.getName());
                                            zos.putNextEntry(zipEntry);
                                            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                                                byte[] buffer = new byte[65536];
                                                int length;
                                                while ((length = fis.read(buffer)) >= 0) {
                                                    zos.write(buffer, 0, length);
                                                }
                                            }
                                            zos.closeEntry();
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                streamFileOverWebRTC(tempZip.getAbsolutePath(), rtcHolder[0], "download", 0);
                            }).start();
                        }
                    } else if ("get_storage_breakdown".equals(action)) {
                        if (rtcHolder[0] != null) {
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "storage_breakdown");
                            res.put("data", StorageHelper.getStorageBreakdown(HostForegroundService.this));
                            rtcHolder[0].sendData(res.toString());
                        }
                    } else if ("list_category".equals(action)) {
                        String category = req.optString("category");
                        if (rtcHolder[0] != null) {
                            new Thread(() -> {
                                try {
                                    org.json.JSONArray allFiles = StorageHelper.listCategory(HostForegroundService.this, category);
                                    if (allFiles == null || allFiles.length() == 0) {
                                        org.json.JSONObject res = new org.json.JSONObject();
                                        res.put("action", "directory_listing");
                                        res.put("path", "Category: " + category);
                                        res.put("files", new org.json.JSONArray());
                                        res.put("chunk", 1);
                                        res.put("total_chunks", 1);
                                        rtcHolder[0].sendData(res.toString());
                                    } else {
                                        int BATCH_SIZE = 50;
                                        int totalChunks = (int) Math.ceil((double) allFiles.length() / BATCH_SIZE);
                                        for (int c = 0; c < totalChunks; c++) {
                                            org.json.JSONArray batchArray = new org.json.JSONArray();
                                            int start = c * BATCH_SIZE;
                                            int end = Math.min(start + BATCH_SIZE, allFiles.length());
                                            for (int i = start; i < end; i++) {
                                                batchArray.put(allFiles.getJSONObject(i));
                                            }
                                            org.json.JSONObject res = new org.json.JSONObject();
                                            res.put("action", "directory_listing");
                                            res.put("path", "Category: " + category);
                                            res.put("files", batchArray);
                                            res.put("chunk", c + 1);
                                            res.put("total_chunks", totalChunks);
                                            rtcHolder[0].sendData(res.toString());
                                            Thread.sleep(10); // Prevent overwhelming WebRTC buffer
                                        }
                                    }
                                } catch (Exception e) {}
                            }).start();
                        }
                    } else if ("download_range".equals(action) && !effectivePath.isEmpty()) {
                        // Range-based streaming for instant video preview
                        final long rangeStart  = req.optLong("start", 0);
                        final long rangeLength = req.optLong("length", 2 * 1024 * 1024); // default 2MB
                        final String requestId = req.optString("requestId", "");
                        if (rtcHolder[0] != null && !requestId.isEmpty()) {
                            final WebRTCManager rtcFinal = rtcHolder[0];
                            if (rangeExecutor == null) {
                                rangeExecutor = java.util.concurrent.Executors.newCachedThreadPool();
                            }
                            rangeExecutor.execute(() -> {
                                try {
                                    java.io.File file = new java.io.File(effectivePath);
                                    // Signal start of range data
                                    org.json.JSONObject rangeStart2 = new org.json.JSONObject();
                                    rangeStart2.put("action", "range_data");
                                    rangeStart2.put("requestId", requestId);
                                    rangeStart2.put("start", rangeStart);
                                    rangeStart2.put("length", rangeLength);
                                    rtcFinal.sendData(rangeStart2.toString());
                                    Thread.sleep(20); // let JSON arrive before binary
                                    
                                    // Read and send requested range
                                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                                        raf.seek(rangeStart);
                                        long remaining = rangeLength;
                                        byte[] buf = new byte[65536]; // 64KB chunks
                                        while (remaining > 0 && !requestId.equals(cancelledRangeId)) {
                                            int toRead = (int) Math.min(buf.length, remaining);
                                            int n = raf.read(buf, 0, toRead);
                                            if (n <= 0) break;
                                            if (n == buf.length) {
                                                rtcFinal.sendBinaryData(buf.clone());
                                            } else {
                                                byte[] chunk = new byte[n];
                                                System.arraycopy(buf, 0, chunk, 0, n);
                                                rtcFinal.sendBinaryData(chunk);
                                            }
                                            remaining -= n;
                                            // Backpressure: pause if DataChannel buffer is getting full
                                            while (rtcFinal.getBufferedAmount() > 4 * 1024 * 1024 && !requestId.equals(cancelledRangeId)) {
                                                Thread.sleep(5);
                                            }
                                        }
                                    }
                                    
                                    // Signal end of range
                                    org.json.JSONObject rangeEnd = new org.json.JSONObject();
                                    rangeEnd.put("action", "range_end");
                                    rangeEnd.put("requestId", requestId);
                                    rtcFinal.sendData(rangeEnd.toString());
                                    
                                } catch (Exception e) {
                                    Log.e("StoreMeNative", "Range read failed: " + e.getMessage());
                                }
                            });
                        }
                    } else if ("cancel_range".equals(action)) {
                        cancelledRangeId = req.optString("requestId", "");
                    } else if ("pause_transfer".equals(action) && !effectivePath.isEmpty()) {
                        transferStatus.put(effectivePath, false);
                    } else if ("delete".equals(action) && !effectivePath.isEmpty()) {
                        java.io.File f = new java.io.File(effectivePath);
                        if (f.exists()) {
                            java.io.File trashDir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), ".storeme_trash");
                            if (!trashDir.exists()) trashDir.mkdirs();
                            f.renameTo(new java.io.File(trashDir, f.getName()));
                            OwnershipStore.getInstance(HostForegroundService.this).removeOwner(effectivePath);
                        }
                    } else if ("delete_permanent".equals(action) && !effectivePath.isEmpty()) {
                        java.io.File f = new java.io.File(effectivePath);
                        if (f.exists()) {
                            deleteRecursive(f);
                            OwnershipStore.getInstance(HostForegroundService.this).removeOwner(effectivePath);
                        }
                    } else if ("restore".equals(action) && !effectivePath.isEmpty()) {
                        java.io.File f = new java.io.File(effectivePath);
                        if (f.exists()) {
                            java.io.File parent = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Restored");
                            if (!parent.exists()) parent.mkdirs();
                            f.renameTo(new java.io.File(parent, f.getName()));
                        }
                    } else if ("rename".equals(action) && !effectivePath.isEmpty()) {
                        String newName = req.optString("newName");
                        java.io.File oldFile = new java.io.File(effectivePath);
                        if (oldFile.exists() && !newName.isEmpty()) {
                            java.io.File destFile = new java.io.File(oldFile.getParent(), newName);
                            String prevOwner = OwnershipStore.getInstance(HostForegroundService.this).getOwner(effectivePath);
                            if (oldFile.renameTo(destFile)) {
                                OwnershipStore.getInstance(HostForegroundService.this).setOwner(destFile.getAbsolutePath(), prevOwner, clientRole, destFile.length());
                                OwnershipStore.getInstance(HostForegroundService.this).removeOwner(effectivePath);
                            }
                        }
                    } else if ("move".equals(action) && !effectivePath.isEmpty()) {
                        String newPath = req.optString("newPath");
                        java.io.File oldFile = new java.io.File(effectivePath);
                        if (oldFile.exists() && !newPath.isEmpty()) {
                            java.io.File destFile = new java.io.File(newPath);
                            String prevOwner = OwnershipStore.getInstance(HostForegroundService.this).getOwner(effectivePath);
                            if (oldFile.renameTo(destFile)) {
                                OwnershipStore.getInstance(HostForegroundService.this).setOwner(destFile.getAbsolutePath(), prevOwner, clientRole, destFile.length());
                                OwnershipStore.getInstance(HostForegroundService.this).removeOwner(effectivePath);
                            }
                        }
                    } else if ("zip".equals(action) && !effectivePath.isEmpty()) {
                        new Thread(() -> zipDirectory(new java.io.File(effectivePath))).start();
                    } else if ("unzip".equals(action) && !effectivePath.isEmpty()) {
                        new Thread(() -> unzipFile(new java.io.File(effectivePath))).start();
                    } else if ("create_link".equals(action)) {
                        String token = java.util.UUID.randomUUID().toString().substring(0, 8);
                        long expiresAt = System.currentTimeMillis() + (24L * 60 * 60 * 1000); // 24 hours
                        
                        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", android.content.Context.MODE_PRIVATE);
                        org.json.JSONArray links;
                        try { links = new org.json.JSONArray(prefs.getString("share_links", "[]")); }
                        catch (Exception e) { links = new org.json.JSONArray(); }
                        
                        try {
                            org.json.JSONObject linkObj = new org.json.JSONObject();
                            linkObj.put("token", token);
                            
                            org.json.JSONArray paths = req.optJSONArray("paths");
                            if (paths == null) {
                                if (!effectivePath.isEmpty()) { paths = new org.json.JSONArray(); paths.put(effectivePath); }
                                else { return; }
                            }
                            linkObj.put("paths", paths);
                            
                            linkObj.put("expiresAt", expiresAt);
                            links.put(linkObj);
                            prefs.edit().putString("share_links", links.toString()).apply();
                            
                            if (streamServer != null) {
                                streamServer.addShareToken(token, effectivePath);
                            }
                            
                            String publicUrl = "https://storemeweb.vercel.app/?host=" + hostId + "&token=" + token;
                            
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "link_created");
                            res.put("token", publicUrl);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    } else if ("sync_diff".equals(action) && !effectivePath.isEmpty()) {
                        new Thread(() -> {
                            try {
                                org.json.JSONArray files = new org.json.JSONArray();
                                java.io.File root = new java.io.File(effectivePath);
                                buildSyncManifest(root, root.getAbsolutePath(), files);
                                
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "sync_manifest");
                                res.put("path", effectivePath);
                                res.put("files", files);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            } catch (Exception e) {}
                        }).start();

                    } else if ("search".equals(action)) {
                        String query = req.optString("query", "").toLowerCase();
                        String searchPath = req.optString("path", "");
                        if (searchPath.isEmpty()) searchPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                        
                        if (!query.isEmpty() && rtcHolder[0] != null) {
                            final String finalSearchPath = searchPath;
                            new Thread(() -> {
                                java.util.List<org.json.JSONObject> results = new java.util.ArrayList<>();
                                searchRecursive(new java.io.File(finalSearchPath), query, results, 0);
                                
                                org.json.JSONObject response = new org.json.JSONObject();
                                try {
                                    response.put("action", "search_results");
                                    org.json.JSONArray arr = new org.json.JSONArray();
                                    for (org.json.JSONObject r : results) arr.put(r);
                                    response.put("results", arr);
                                    rtcHolder[0].sendData(response.toString());
                                } catch (Exception e) { e.printStackTrace(); }
                            }).start();
                        }
                    } else if ("get_storage_stats".equals(action)) {
                        try {
                            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
                            long totalBytes = stat.getTotalBytes();
                            long freeBytes = stat.getAvailableBytes();
                            long usedBytes = totalBytes - freeBytes;
                            
                            long imageBytes = getMediaStoreSize(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null);
                            long videoBytes = getMediaStoreSize(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null);
                            long audioBytes = getMediaStoreSize(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null);
                            long docsBytes  = getMediaStoreSize(android.provider.MediaStore.Files.getContentUri("external"), "mime_type LIKE 'application/pdf' OR mime_type LIKE 'text/%'");
                            
                            long otherBytes = usedBytes - (imageBytes + videoBytes + audioBytes + docsBytes);
                            if (otherBytes < 0) otherBytes = 0;

                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "storage_stats");
                            res.put("total", totalBytes / (1024*1024*1024));
                            res.put("used", usedBytes / (1024*1024*1024));
                            res.put("images", imageBytes / (1024*1024*1024));
                            res.put("videos", videoBytes / (1024*1024*1024));
                            res.put("docs", (docsBytes + audioBytes) / (1024*1024*1024));
                            res.put("other", otherBytes / (1024*1024*1024));
                            
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e){}
                    } else if ("get_host_info".equals(action)) {
                        try {
                            android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                            android.content.Intent batteryStatus = registerReceiver(null, ifilter);
                            int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
                            int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) : -1;
                            int status = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) : -1;
                            boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                                 status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                            float batteryPct = level * 100 / (float)scale;
                            
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "host_info");
                            res.put("manufacturer", android.os.Build.MANUFACTURER);
                            res.put("model", android.os.Build.MODEL);
                            res.put("batteryLevel", (int)batteryPct);
                            res.put("isCharging", isCharging);
                            
                            String allowedPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                            android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                            try {
                                if (connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) {
                                    allowedPath = "VirtualShare";
                                } else {
                                    org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("host_users", "[]"));
                                    for (int i=0; i<arr.length(); i++) {
                                        org.json.JSONObject u = arr.getJSONObject(i);
                                        if (u.optString("username").equals(connectedUsername[0])) {
                                            org.json.JSONArray perms = u.optJSONArray("permissions");
                                            if (perms != null && perms.length() > 0) {
                                                allowedPath = perms.getString(0);
                                                if (allowedPath.equals("/") || allowedPath.equals("/storage") || allowedPath.equals("/storage/emulated")) {
                                                    allowedPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {}
                            res.put("allowed_path", allowedPath);
                            
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    } else if ("get_share_info".equals(action)) {
                        if (connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) {
                            String token = connectedUsername[0].replace("share_user_", "");
                            android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                            try {
                                org.json.JSONArray links = new org.json.JSONArray(prefs.getString("share_links", "[]"));
                                for (int i=0; i<links.length(); i++) {
                                    org.json.JSONObject link = links.getJSONObject(i);
                                    if (link.optString("token").equals(token)) {
                                        java.io.File f = new java.io.File(link.optString("path"));
                                        org.json.JSONObject res = new org.json.JSONObject();
                                        res.put("action", "share_info");
                                        res.put("path", f.getAbsolutePath());
                                        res.put("name", f.getName());
                                        res.put("size", f.length());
                                        res.put("isDir", f.isDirectory());
                                        if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                                        break;
                                    }
                                }
                            } catch (Exception e) {}
                        }
                    }
                } catch (Exception e) {
                    Log.e("StoreMeNative", "Failed to parse WebRTC msg", e);
                }
            }

            @Override
            public void onDataChannelBinary(java.nio.ByteBuffer buffer) {
                if (currentBinaryUploadPath[0] != null) {
                    java.io.RandomAccessFile raf = openUploads.get(currentBinaryUploadPath[0]);
                    if (raf != null) {
                        try {
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            raf.write(bytes);
                        } catch (Exception e) {}
                    }
                }
            }

            @Override
            public void onDataChannelOpen() {
                activePeers.put(clientId, rtcHolder[0]);
                int count = activePeers.size();
                Log.i("StoreMeNative", "DataChannel Open for client: " + clientId + " | Total: " + count);
                updateNotification("🟢 StoreMe Server is Running",
                        "ID: " + hostId + "  •  " + count + " client" + (count == 1 ? "" : "s") + " connected");
            }

            @Override
            public void onDisconnected() {
                // Close any open upload file handles for this disconnected client
                if (currentBinaryUploadPath != null && currentBinaryUploadPath.length > 0 && currentBinaryUploadPath[0] != null) {
                    try {
                        java.io.RandomAccessFile raf = openUploads.remove(currentBinaryUploadPath[0]);
                        if (raf != null) raf.close();
                    } catch (Exception ignored) {}
                }
                
                activePeers.remove(clientId);
                int count = activePeers.size();
                Log.i("StoreMeNative", "DataChannel Closed for client: " + clientId);
                String text = count == 0
                        ? "ID: " + hostId + "  •  Waiting for clients…"
                        : "ID: " + hostId + "  •  " + count + " client" + (count == 1 ? "" : "s") + " connected";
                updateNotification("🟢 StoreMe Server is Running", text);
            }
        });

        rtcHolder[0] = rtcManager;
        rtcManager.createPeerConnection();

        // Listen for Client ICE Candidates
        dbRef.child(clientId).child("candidates").child("client").addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String prev) {
                String candidate = snapshot.child("candidate").getValue(String.class);
                String sdpMid = snapshot.child("sdpMid").getValue(String.class);
                Integer sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Integer.class);
                if (candidate != null && sdpMid != null && sdpMLineIndex != null) {
                    rtcManager.addRemoteIceCandidate(new org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate));
                }
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        });

        // Listen for Client Offer
        dbRef.child(clientId).child("offer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                String clientPassword = snapshot.child("password").getValue(String.class);
                String clientUsername = snapshot.child("username").getValue(String.class);
                if (clientUsername == null) clientUsername = "admin"; // backwards compatibility
                
                boolean authenticated = false;
                
                android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                
                if (clientPassword != null && clientPassword.equals(password)) {
                    authenticated = true;
                    connectedUsername[0] = clientUsername;
                    
                    // Add admin to Existing Users
                    try {
                        String usersJson = prefs.getString("host_users", "[]");
                        org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                        boolean found = false;
                        for (int i=0; i<arr.length(); i++) {
                            if (arr.getJSONObject(i).optString("username").equals(clientUsername)) {
                                found = true; break;
                            }
                        }
                        if (!found) {
                            org.json.JSONObject newAdmin = new org.json.JSONObject();
                            newAdmin.put("username", clientUsername);
                            newAdmin.put("password", clientPassword);
                            org.json.JSONArray perms = new org.json.JSONArray();
                            perms.put("/");
                            newAdmin.put("permissions", perms);
                            arr.put(newAdmin);
                            prefs.edit().putString("host_users", arr.toString()).apply();
                        }
                    } catch (Exception e) {}
                } else {
                    String usersJson = prefs.getString("host_users", "[]");
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                        for (int i=0; i<arr.length(); i++) {
                            org.json.JSONObject u = arr.getJSONObject(i);
                            if (u.optString("username").equals(clientUsername) && u.optString("password").equals(clientPassword)) {
                                authenticated = true;
                                connectedUsername[0] = clientUsername;
                                break;
                            }
                        }
                    } catch (Exception e) {}
                }
                
                // 3. Check Share Links
                if (!authenticated) {
                    try {
                        org.json.JSONArray links = new org.json.JSONArray(prefs.getString("share_links", "[]"));
                        long now = System.currentTimeMillis();
                        org.json.JSONArray newLinks = new org.json.JSONArray();
                        for (int i=0; i<links.length(); i++) {
                            org.json.JSONObject link = links.getJSONObject(i);
                            if (now > link.optLong("expiresAt", 0)) continue; // Expired, remove it
                            
                            if (link.optString("token").equals(clientPassword)) {
                                authenticated = true;
                                connectedUsername[0] = "share_user_" + clientPassword;
                                // Add virtual temporary user
                                org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("host_users", "[]"));
                                org.json.JSONObject vUser = new org.json.JSONObject();
                                vUser.put("username", connectedUsername[0]);
                                org.json.JSONArray perms = new org.json.JSONArray();
                                if (link.has("paths")) {
                                    org.json.JSONArray pArr = link.getJSONArray("paths");
                                    for(int p=0; p<pArr.length(); p++) perms.put(pArr.getString(p));
                                } else if (link.has("path")) {
                                    perms.put(link.optString("path"));
                                }
                                vUser.put("permissions", perms);
                                arr.put(vUser);
                                prefs.edit().putString("host_users", arr.toString()).apply();
                            }
                            newLinks.put(link);
                        }
                        prefs.edit().putString("share_links", newLinks.toString()).apply();
                    } catch (Exception e) {}
                }

                if (authenticated) {
                    Log.i("StoreMeNative", "User authenticated: " + clientUsername + " for client: " + clientId);
                    String sdp = snapshot.child("sdp").getValue(String.class);
                    String type = snapshot.child("type").getValue(String.class);
                    
                    org.webrtc.SessionDescription offer = new org.webrtc.SessionDescription(
                            org.webrtc.SessionDescription.Type.fromCanonicalForm(type), sdp);
                            
                    rtcManager.setRemoteOfferAndCreateAnswer(offer, new org.webrtc.SdpObserver() {
                        @Override
                        public void onCreateSuccess(org.webrtc.SessionDescription answer) {
                            dbRef.child(clientId).child("answer").setValue(new SessionPayload(answer.type.canonicalForm(), answer.description));
                        }
                        @Override public void onSetSuccess() {}
                        @Override public void onSetFailure(String s) {}
                        @Override public void onCreateFailure(String s) {}
                    });
                } else {
                    Log.e("StoreMeNative", "Invalid credentials from client: " + clientId);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {}
        });
    }

        private void streamFileOverWebRTC(String path, WebRTCManager rtcManager, String intentStr, long offset) {
        java.io.File file = new java.io.File(path);
        try {
            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("action", "file_transfer_start");
            meta.put("name", file.getName());
            meta.put("path", path);
            meta.put("size", file.length());
            meta.put("offset", offset);
            meta.put("intent", intentStr);
            rtcManager.sendData(meta.toString());
            
            // Sleep slightly to ensure JSON arrives before binary chunks
            Thread.sleep(100);
            
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                if (offset > 0) {
                    raf.seek(offset);
                }
                
                byte[] buffer = new byte[32768]; // WebRTC DataChannel safe chunk size
                int bytesRead;
                while ((bytesRead = raf.read(buffer)) != -1) {
                    if (Boolean.FALSE.equals(transferStatus.get(path))) {
                        android.util.Log.i("StoreMeNative", "Stream paused/stopped: " + path);
                        break;
                    }
                    
                    if (bytesRead < buffer.length) {
                        byte[] exactChunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, exactChunk, 0, bytesRead);
                        rtcManager.sendBinaryData(exactChunk);
                    } else {
                        rtcManager.sendBinaryData(buffer);
                    }

                    while (rtcManager.getBufferedAmount() > 2 * 1024 * 1024) {
                        Thread.sleep(10);
                        if (Boolean.FALSE.equals(transferStatus.get(path))) break;
                    }
                }
                
                org.json.JSONObject endMeta = new org.json.JSONObject();
                endMeta.put("action", "file_transfer_end");
                endMeta.put("path", path);
                endMeta.put("intent", intentStr);
                rtcManager.sendData(endMeta.toString());
                
                android.util.Log.i("StoreMeNative", "File streaming complete: " + path);
            }
        } catch (Exception e) {
            android.util.Log.e("StoreMeNative", "Streaming failed", e);
        } finally {
            activeTransfers.remove(path);
        }
    }

    public static class CandidatePayload {
        public String candidate;
        public String sdpMid;
        public int sdpMLineIndex;
        public CandidatePayload(String candidate, String sdpMid, int sdpMLineIndex) {
            this.candidate = candidate; this.sdpMid = sdpMid; this.sdpMLineIndex = sdpMLineIndex;
        }
    }

    public static class SessionPayload {
        public String type;
        public String sdp;
        public SessionPayload(String type, String sdp) {
            this.type = type; this.sdp = sdp;
        }
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "StoreMe Server",
                    NotificationManager.IMPORTANCE_LOW  // Silent but always visible — never auto-dismissed
            );
            serviceChannel.setDescription("Persistent notification while StoreMe server is running");
            serviceChannel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /** Build a rich foreground service notification. */
    private Notification buildNotification(String title, String text, PendingIntent contentIntent) {
        Intent openIntent = new Intent(this, DashboardActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tapIntent = contentIntent != null ? contentIntent :
                PendingIntent.getActivity(this, 0, openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_server)
                .setContentIntent(tapIntent)
                .setOngoing(true)          // Cannot be swiped away by user
                .setSilent(true)           // No sound or vibration
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    /** Update the persistent notification text while the service is running. */
    private void updateNotification(String title, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, buildNotification(title, text, null));
        }
    }


    @Nullable
    private void deleteRecursively(java.io.File f) {
        if (f.isDirectory()) {
            for (java.io.File child : f.listFiles()) {
                deleteRecursively(child);
            }
        }
        f.delete();
    }

    private void zipDirectory(java.io.File dirToZip) {
        if (!dirToZip.exists() || !dirToZip.isDirectory()) return;
        try {
            java.io.File zipFile = new java.io.File(dirToZip.getParent(), dirToZip.getName() + ".zip");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos);
            zipDirRecursive(dirToZip, dirToZip, zos);
            zos.close();
            fos.close();
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    private void zipDirRecursive(java.io.File rootDir, java.io.File currDir, java.util.zip.ZipOutputStream zos) throws Exception {
        java.io.File[] files = currDir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                zipDirRecursive(rootDir, f, zos);
            } else {
                String path = f.getAbsolutePath().substring(rootDir.getAbsolutePath().length() + 1);
                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(path);
                zos.putNextEntry(ze);
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                fis.close();
                zos.closeEntry();
            }
        }
    }

    private void unzipFile(java.io.File zipFile) {
        if (!zipFile.exists() || !zipFile.getName().endsWith(".zip")) return;
        try {
            java.io.File destDir = new java.io.File(zipFile.getParent(), zipFile.getName().replace(".zip", ""));
            if (!destDir.exists()) destDir.mkdirs();
            
            java.io.FileInputStream fis = new java.io.FileInputStream(zipFile);
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(fis);
            java.util.zip.ZipEntry ze = zis.getNextEntry();
            
            while (ze != null) {
                java.io.File newFile = new java.io.File(destDir, ze.getName());
                if (ze.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new java.io.File(newFile.getParent()).mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(newFile);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    fos.close();
                }
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            zis.close();
            fis.close();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void searchRecursive(java.io.File dir, String query, java.util.List<org.json.JSONObject> results, int depth) {
        if (depth > 6 || results.size() >= 200) return; // safety limits
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.getName().toLowerCase().contains(query)) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("name", f.getName());
                    obj.put("path", f.getAbsolutePath());
                    obj.put("isDir", f.isDirectory());
                    obj.put("size", f.isDirectory() ? 0 : f.length());
                    results.add(obj);
                } catch (Exception e) { /* ignore */ }
            }
            if (f.isDirectory()) searchRecursive(f, query, results, depth + 1);
        }
    }

    private void buildSyncManifest(java.io.File dir, String rootPath, org.json.JSONArray files) {
        java.io.File[] list = dir.listFiles();
        if (list == null) return;
        for (java.io.File f : list) {
            if (f.isDirectory()) {
                buildSyncManifest(f, rootPath, files);
            } else {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    String relativePath = f.getAbsolutePath().substring(rootPath.length());
                    if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                    obj.put("relPath", relativePath);
                    obj.put("size", f.length());
                    obj.put("lastModified", f.lastModified());
                    files.put(obj);
                } catch (Exception e) {}
            }
        }
    }

    private void scanFilesForCleaning(java.io.File dir, org.json.JSONArray files, long now) {
        if (files.length() > 50) return; // Limit for remote
        java.io.File[] list = dir.listFiles();
        if (list == null) return;
        
        long sixMonths = 180L * 24 * 60 * 60 * 1000;
        
        for (java.io.File f : list) {
            if (f.isDirectory()) {
                if (f.getName().equalsIgnoreCase("thumbnails") || f.getName().equalsIgnoreCase("cache") || f.getName().equalsIgnoreCase("tmp")) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "App Cache / Junk Directory");
                        files.put(obj);
                    } catch (Exception e) {}
                } else {
                    scanFilesForCleaning(f, files, now);
                }
            } else {
                String name = f.getName().toLowerCase();
                try {
                    if (name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak")) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "Temporary / Junk File");
                        files.put(obj);
                    } else if (f.length() > 50 * 1024 * 1024 && (now - f.lastModified()) > sixMonths) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "Large old file (>50MB, >6 months)");
                        files.put(obj);
                    } else if (name.contains("copy") || name.contains("(1)")) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "Likely Duplicate");
                        files.put(obj);
                    }
                } catch (Exception e) {}
            }
        }
    }

    private void deleteRecursive(java.io.File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            java.io.File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (java.io.File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * Called when user swipes the app from the Recent Apps list.
     * DO NOT call stopSelf() here — the foreground service must keep running!
     * The notification will update to show the server is still active.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i("StoreMeNative", "App swiped from recents — service continues running in background");
        // Update notification to reassure user server is still alive
        updateNotification("🟢 Server running in background", "Tap to return to the dashboard");
        // Do NOT call stopSelf() — service must keep running!
        super.onTaskRemoved(rootIntent);
    }

    /** Notify all connected clients that the host is going offline, then clean up. */
    private void notifyClientsHostOffline() {
        try {
            // Send a JSON "host_offline" message to every connected client via DataChannel
            String offlineMsg = "{\"action\":\"host_offline\",\"reason\":\"Host closed the app\"}";
            for (WebRTCManager peer : activePeers.values()) {
                try {
                    peer.sendData(offlineMsg);
                } catch (Exception ignored) {}
            }
            // Remove Firebase presence so clients watching status see it go offline instantly
            if (hostId != null) {
                FirebaseDatabase.getInstance()
                    .getReference("rooms").child(hostId).child("status")
                    .setValue("offline");
            }
        } catch (Exception e) {
            Log.e("StoreMeNative", "Error notifying clients: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFirebaseHeartbeat();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (streamServer != null) streamServer.stop();
        if (localHttpServer != null) localHttpServer.stop();
        if (rangeExecutor != null) {
            rangeExecutor.shutdownNow();
            rangeExecutor = null;
        }
        if (dbRef != null && hostId != null) {
            FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("status").removeValue();
            FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("connections").removeValue();
        }
    }
}

