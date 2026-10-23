package com.storeme.rbac;

/**
 * Enterprise Role-Based Access Control Role definitions.
 * Three tiers: ADMIN, UPLOADER, VIEWER.
 */
public enum Role {
    ADMIN("admin"),
    UPLOADER("uploader"),
    VIEWER("viewer");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Role fromString(String str) {
        if (str == null) return VIEWER;
        String s = str.trim().toLowerCase();
        if ("admin".equals(s)) return ADMIN;
        if ("uploader".equals(s)) return UPLOADER;
        if ("viewer".equals(s)) return VIEWER;
        return VIEWER;
    }

    public static boolean isValidRole(String str) {
        if (str == null) return false;
        String s = str.trim().toLowerCase();
        return "admin".equals(s) || "uploader".equals(s) || "viewer".equals(s);
    }

    @Override
    public String toString() {
        return value;
    }
}
