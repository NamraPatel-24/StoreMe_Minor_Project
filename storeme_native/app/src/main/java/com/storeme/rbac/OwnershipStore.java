package com.storeme.rbac;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata store tracking file ownership across uploaders and administrators.
 * Persists mapping to file_ownership.json.
 * Defaults unrecorded legacy files to "admin".
 */
public class OwnershipStore {
    private static final String TAG = "OwnershipStore";
    private static volatile OwnershipStore instance;

    private final ConcurrentHashMap<String, JSONObject> ownershipMap = new ConcurrentHashMap<>();
    private File storageFile;
    private final Object lock = new Object();

    private OwnershipStore(Context context) {
        initStorage(context);
    }

    public static OwnershipStore getInstance(Context context) {
        if (instance == null) {
            synchronized (OwnershipStore.class) {
                if (instance == null) {
                    instance = new OwnershipStore(context != null ? context.getApplicationContext() : null);
                }
            }
        }
        return instance;
    }

    private void initStorage(Context context) {
        try {
            File baseDir = null;
            if (context != null) {
                File filesDir = context.getFilesDir();
                baseDir = new File(filesDir, ".storeme_metadata");
            } else {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null && ext.exists()) {
                    baseDir = new File(ext, ".storeme_metadata");
                }
            }
            if (baseDir != null) {
                if (!baseDir.exists()) {
                    baseDir.mkdirs();
                }
                storageFile = new File(baseDir, "file_ownership.json");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not initialize metadata directory: " + e.getMessage());
        }

        loadFromFile();
    }

    private void loadFromFile() {
        if (storageFile == null || !storageFile.exists()) {
            return;
        }
        synchronized (lock) {
            try (FileInputStream fis = new FileInputStream(storageFile)) {
                byte[] bytes = new byte[(int) storageFile.length()];
                int read = fis.read(bytes);
                if (read > 0) {
                    String jsonStr = new String(bytes, StandardCharsets.UTF_8);
                    JSONObject root = new JSONObject(jsonStr);
                    Iterator<String> keys = root.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        ownershipMap.put(normalizePath(k), root.getJSONObject(k));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading file_ownership.json: " + e.getMessage());
            }
        }
    }

    private void persistToFile() {
        if (storageFile == null) {
            return;
        }
        synchronized (lock) {
            try {
                JSONObject root = new JSONObject();
                for (Map.Entry<String, JSONObject> entry : ownershipMap.entrySet()) {
                    root.put(entry.getKey(), entry.getValue());
                }
                byte[] data = root.toString(2).getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream fos = new FileOutputStream(storageFile)) {
                    fos.write(data);
                    fos.flush();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error saving file_ownership.json: " + e.getMessage());
            }
        }
    }

    public static String normalizePath(String rawPath) {
        if (rawPath == null) return "/";
        String p = rawPath.replace("\\", "/");
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    /**
     * Look up the owner of a file.
     * Default unrecorded legacy files to "admin".
     */
    public String getOwner(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "admin";
        }
        String norm = normalizePath(filePath);
        JSONObject meta = ownershipMap.get(norm);
        if (meta != null) {
            return meta.optString("owner", "admin");
        }

        // Fuzzy matching for relative paths or file names
        String name = new File(norm).getName();
        for (Map.Entry<String, JSONObject> entry : ownershipMap.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(norm) || norm.endsWith(key) || key.endsWith("/" + name)) {
                return entry.getValue().optString("owner", "admin");
            }
        }

        return "admin";
    }

    /**
     * Record file owner upon upload.
     */
    public void setOwner(String filePath, String username, Role role, long size) {
        if (filePath == null || filePath.isEmpty() || username == null) {
            return;
        }
        String norm = normalizePath(filePath);
        try {
            JSONObject obj = new JSONObject();
            obj.put("owner", username);
            obj.put("role", role != null ? role.getValue() : Role.UPLOADER.getValue());
            obj.put("uploaded_at", System.currentTimeMillis());
            obj.put("size", size);
            ownershipMap.put(norm, obj);
            persistToFile();
        } catch (Exception e) {
            Log.w(TAG, "Failed to record ownership: " + e.getMessage());
        }
    }

    public void removeOwner(String filePath) {
        if (filePath == null) return;
        String norm = normalizePath(filePath);
        boolean removed = (ownershipMap.remove(norm) != null);
        String name = new File(norm).getName();
        Iterator<Map.Entry<String, JSONObject>> iter = ownershipMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, JSONObject> entry = iter.next();
            String key = entry.getKey();
            if (key.endsWith(norm) || norm.endsWith(key) || key.endsWith("/" + name)) {
                iter.remove();
                removed = true;
            }
        }
        if (removed) {
            persistToFile();
        }
    }

    public boolean isOwner(String filePath, String username) {
        if (username == null) return false;
        String owner = getOwner(filePath);
        return username.equals(owner);
    }

    public Map<String, JSONObject> getAllOwnership() {
        return ownershipMap;
    }
}
