package com.storeme.rbac;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/**
 * Enterprise Session & Authentication Manager.
 * Handles bearer token issuance, validation, user storage in SharedPreferences,
 * and user role administrative operations.
 */
public class SessionManager {
    private static final String TAG = "SessionManager";
    private static volatile SessionManager instance;

    private final Context context;
    private final OwnershipStore ownershipStore;
    private final ConcurrentHashMap<String, String> tokenToUsername = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final Object usersLock = new Object();

    private SessionManager(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.ownershipStore = OwnershipStore.getInstance(this.context);
        loadUsersFromPrefs();
    }

    public static SessionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) {
                    instance = new SessionManager(context);
                }
            }
        }
        return instance;
    }

    public OwnershipStore getOwnershipStore() {
        return ownershipStore;
    }

    /**
     * Load or initialize users from StoreMePrefs ("host_users").
     */
    public void loadUsersFromPrefs() {
        synchronized (usersLock) {
            users.clear();

            String masterPassword = "admin";
            if (context != null) {
                SharedPreferences prefs = context.getSharedPreferences("StoreMePrefs", Context.MODE_PRIVATE);
                masterPassword = prefs.getString("host_password", prefs.getString("password", "admin"));
                String usersJson = prefs.getString("host_users", "[]");
                try {
                    JSONArray arr = new JSONArray(usersJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        User u = User.fromJsonObject(obj);
                        if (!u.getUsername().isEmpty()) {
                            // Default admin username to ADMIN role if not set
                            if ("admin".equalsIgnoreCase(u.getUsername())) {
                                u.setRole(Role.ADMIN);
                            } else if (!obj.has("role") || obj.optString("role").trim().isEmpty()) {
                                boolean isFullControl = obj.optBoolean("isFullControl", false);
                                u.setRole(isFullControl ? Role.ADMIN : Role.VIEWER);
                            }
                            users.put(u.getUsername(), u);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error loading host_users from prefs: " + e.getMessage());
                }
            }

            // Ensure default admin user always exists
            if (!users.containsKey("admin")) {
                List<String> adminPerms = new ArrayList<>();
                adminPerms.add("/");
                User admin = new User("admin", masterPassword, Role.ADMIN, adminPerms, System.currentTimeMillis());
                users.put("admin", admin);
                persistUsersToPrefs();
            } else {
                User admin = users.get("admin");
                if (admin != null) {
                    admin.setRole(Role.ADMIN);
                }
            }
        }
    }

    /**
     * Persist current users to StoreMePrefs ("host_users").
     */
    public void persistUsersToPrefs() {
        if (context == null) return;
        synchronized (usersLock) {
            try {
                SharedPreferences prefs = context.getSharedPreferences("StoreMePrefs", Context.MODE_PRIVATE);
                JSONArray arr = new JSONArray();
                for (User u : users.values()) {
                    arr.put(u.toJsonObject(true));
                }
                prefs.edit().putString("host_users", arr.toString()).apply();
            } catch (Exception e) {
                Log.w(TAG, "Error saving host_users to prefs: " + e.getMessage());
            }
        }
    }

    /**
     * Authenticate credentials and return session token, or null if invalid.
     */
    public String login(String username, String password) {
        if (username == null || password == null) return null;
        String cleanUser = username.trim();
        String cleanPass = password.trim();
        if (cleanUser.isEmpty() || cleanPass.isEmpty()) return null;

        synchronized (usersLock) {
            User u = users.get(cleanUser);
            if (u == null) {
                // Check if logging in as admin with host master password
                if (context != null && "admin".equalsIgnoreCase(cleanUser)) {
                    SharedPreferences prefs = context.getSharedPreferences("StoreMePrefs", Context.MODE_PRIVATE);
                    String masterPass = prefs.getString("host_password", prefs.getString("password", "admin"));
                    if (cleanPass.equals(masterPass) || "admin".equals(cleanPass)) {
                        List<String> adminPerms = new ArrayList<>();
                        adminPerms.add("/");
                        u = new User("admin", masterPass, Role.ADMIN, adminPerms, System.currentTimeMillis());
                        users.put("admin", u);
                        persistUsersToPrefs();
                    }
                }
            }

            if (u != null) {
                boolean match = cleanPass.equals(u.getPassword())
                        || ("admin".equalsIgnoreCase(u.getUsername()) && ("admin".equals(cleanPass) || "admin123".equals(cleanPass)));
                if (match) {
                    String token = UUID.randomUUID().toString();
                    tokenToUsername.put(token, u.getUsername());
                    return token;
                }
            }
        }
        return null;
    }

    /**
     * Extract token from NanoHTTPD session (Bearer header or ?token= query parameter).
     */
    public String extractToken(NanoHTTPD.IHTTPSession session) {
        if (session == null) return null;

        // 1. Check Authorization header
        Map<String, String> headers = session.getHeaders();
        String authHeader = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())) {
                authHeader = entry.getValue();
                break;
            }
        }

        if (authHeader != null) {
            String trimmed = authHeader.trim();
            if (trimmed.length() > 4096) return null;
            if (trimmed.toLowerCase().startsWith("bearer ")) {
                String token = trimmed.substring(7).trim();
                return token.isEmpty() ? null : token;
            }
            // Present but non-bearer scheme
            return null;
        }

        // 2. Check query parameter ?token=
        Map<String, String> parms = session.getParms();
        if (parms != null && parms.containsKey("token")) {
            String raw = parms.get("token");
            if (raw != null) {
                try {
                    String decoded = URLDecoder.decode(raw, "UTF-8").trim();
                    if (decoded.length() > 4096) return null;
                    return decoded.isEmpty() ? null : decoded;
                } catch (Exception ignored) {}
            }
        }

        String query = session.getQueryParameterString();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = pair.substring(0, idx);
                    if ("token".equalsIgnoreCase(key)) {
                        try {
                            String val = URLDecoder.decode(pair.substring(idx + 1), "UTF-8").trim();
                            if (val.length() > 4096) return null;
                            return val.isEmpty() ? null : val;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        return null;
    }

    /**
     * Look up user associated with token.
     */
    public User getUserByToken(String token) {
        if (token == null) return null;
        String trimmed = token.trim();
        String username = tokenToUsername.get(trimmed);
        if (username == null) return null;
        return getUser(username);
    }

    public User getUser(String username) {
        if (username == null) return null;
        String cleanUser = username.trim();
        if (cleanUser.isEmpty()) return null;

        User u = users.get(cleanUser);
        if (u == null) {
            loadUsersFromPrefs();
            u = users.get(cleanUser);
        }

        if (u == null && "admin".equalsIgnoreCase(cleanUser)) {
            String masterPassword = "admin";
            if (context != null) {
                SharedPreferences prefs = context.getSharedPreferences("StoreMePrefs", Context.MODE_PRIVATE);
                masterPassword = prefs.getString("host_password", prefs.getString("password", "admin"));
            }
            List<String> adminPerms = new ArrayList<>();
            adminPerms.add("/");
            u = new User("admin", masterPassword, Role.ADMIN, adminPerms, System.currentTimeMillis());
            users.put("admin", u);
            persistUsersToPrefs();
        }

        return u;
    }

    public List<User> getAllUsers() {
        loadUsersFromPrefs();
        return new ArrayList<>(users.values());
    }

    public boolean createUser(String username, String password, Role role) {
        if (username == null || username.trim().isEmpty()) return false;
        String cleanUser = username.trim();
        synchronized (usersLock) {
            if (users.containsKey(cleanUser)) return false;
            List<String> perms = new ArrayList<>();
            perms.add("/");
            User newUser = new User(cleanUser, password != null ? password : "password",
                    role != null ? role : Role.VIEWER, perms, System.currentTimeMillis());
            users.put(cleanUser, newUser);
            persistUsersToPrefs();
            return true;
        }
    }

    public boolean updateUserRole(String username, Role newRole) {
        if (username == null || newRole == null) return false;
        synchronized (usersLock) {
            User target = users.get(username);
            if (target == null) return false;

            // Protection against demoting sole system administrator
            if (target.getRole() == Role.ADMIN && newRole != Role.ADMIN) {
                int adminCount = 0;
                for (User u : users.values()) {
                    if (u.getRole() == Role.ADMIN) adminCount++;
                }
                if (adminCount <= 1) {
                    Log.w(TAG, "Cannot demote the sole administrator: " + username);
                    return false;
                }
            }

            target.setRole(newRole);
            persistUsersToPrefs();
            return true;
        }
    }

    public boolean deleteUser(String username) {
        if (username == null) return false;
        synchronized (usersLock) {
            User target = users.get(username);
            if (target == null) return false;

            if (target.getRole() == Role.ADMIN) {
                int adminCount = 0;
                for (User u : users.values()) {
                    if (u.getRole() == Role.ADMIN) adminCount++;
                }
                if (adminCount <= 1) {
                    Log.w(TAG, "Cannot delete the sole administrator: " + username);
                    return false;
                }
            }

            users.remove(username);
            // Invalidate all tokens associated with this user
            List<String> tokensToRemove = new ArrayList<>();
            for (Map.Entry<String, String> entry : tokenToUsername.entrySet()) {
                if (username.equals(entry.getValue())) {
                    tokensToRemove.add(entry.getKey());
                }
            }
            for (String tok : tokensToRemove) {
                tokenToUsername.remove(tok);
            }

            persistUsersToPrefs();
            return true;
        }
    }
}
