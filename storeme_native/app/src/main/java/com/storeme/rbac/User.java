package com.storeme.rbac;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Enterprise User model representing an authenticated identity with role and scope.
 */
public class User {
    private String username;
    private String password;
    private Role role;
    private List<String> permissions;
    private long createdAt;

    public User(String username, String password, Role role, List<String> permissions, long createdAt) {
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
        this.role = role != null ? role : Role.VIEWER;
        this.permissions = permissions != null ? permissions : new ArrayList<>();
        if (this.permissions.isEmpty()) {
            this.permissions.add("/");
        }
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role != null ? role : Role.VIEWER;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public JSONObject toJsonObject(boolean includePassword) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("username", username);
            if (includePassword) {
                obj.put("password", password);
            }
            obj.put("role", role.getValue());
            JSONArray perms = new JSONArray();
            if (permissions != null) {
                for (String p : permissions) {
                    perms.put(p);
                }
            } else {
                perms.put("/");
            }
            obj.put("permissions", perms);
            obj.put("created_at", createdAt);
        } catch (JSONException ignored) {}
        return obj;
    }

    public static User fromJsonObject(JSONObject obj) {
        String username = obj.optString("username", "");
        String password = obj.optString("password", "");
        String roleStr = obj.optString("role", "");
        if (roleStr.isEmpty()) {
            boolean isFullControl = obj.optBoolean("isFullControl", false);
            roleStr = isFullControl ? "admin" : "viewer";
        }
        Role role = Role.fromString(roleStr);
        List<String> perms = new ArrayList<>();
        JSONArray pArray = obj.optJSONArray("permissions");
        if (pArray != null) {
            for (int i = 0; i < pArray.length(); i++) {
                perms.add(pArray.optString(i));
            }
        } else {
            perms.add("/");
        }
        long created = obj.optLong("created_at", System.currentTimeMillis());
        return new User(username, password, role, perms, created);
    }
}
