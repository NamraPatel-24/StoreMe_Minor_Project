package com.storeme;

import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;

import android.content.Context;
import com.storeme.rbac.Role;
import com.storeme.rbac.User;
import com.storeme.rbac.SessionManager;
import com.storeme.rbac.OwnershipStore;

public class LocalHttpServer extends NanoHTTPD {
    private Context context;
    private SessionManager sessionManager;
    public static final java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject> zipTasks = new java.util.concurrent.ConcurrentHashMap<>();

    private long getFolderSize(File f) {
        long size = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) size += getFolderSize(child);
            }
        } else {
            size = f.length();
        }
        return size;
    }

    private void zipPathRecursive(File file, java.util.zip.ZipOutputStream zos, String base, org.json.JSONObject status) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipPathRecursive(child, zos, base + file.getName() + "/", status);
                }
            }
        } else {
            zos.putNextEntry(new java.util.zip.ZipEntry(base + file.getName()));
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                    synchronized(status) {
                        status.put("processed", status.optLong("processed", 0) + len);
                    }
                }
            }
            zos.closeEntry();
        }
    }

    public LocalHttpServer(Context context) {
        super(8080);
        this.context = context;
        this.sessionManager = SessionManager.getInstance(context);
    }

    private void addCorsHeaders(Response response) {
        if (response != null) {
            response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE, PUT");
            response.addHeader("Access-Control-Allow-Headers", "*");
        }
    }

    private Response createForbiddenResponse(String message) {
        JSONObject err = new JSONObject();
        try {
            err.put("error", "Forbidden");
            err.put("status", 403);
            err.put("message", message);
        } catch (Exception ignored) {}
        Response r = newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", err.toString());
        addCorsHeaders(r);
        return r;
    }

    private Response createUnauthorizedResponse(String message) {
        JSONObject err = new JSONObject();
        try {
            err.put("error", message != null ? message : "Unauthorized");
            err.put("status", 401);
        } catch (Exception ignored) {}
        Response r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", err.toString());
        addCorsHeaders(r);
        return r;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = new java.util.HashMap<>(session.getParms());
        if (session.getQueryParameterString() != null) {
            for (String pair : session.getQueryParameterString().split("&")) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    try {
                        params.put(pair.substring(0, idx), java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                    } catch (Exception e) {
                        params.put(pair.substring(0, idx), pair.substring(idx + 1));
                    }
                }
            }
        }
        Response response;

        try {
            if (session.getMethod() == Method.OPTIONS) {
                response = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
                addCorsHeaders(response);
                return response;
            } else if (uri.equals("/") || uri.equals("/index.html")) {
                response = newFixedLengthResponse(Response.Status.OK, "text/html", buildFullUI());
                addCorsHeaders(response);
                return response;
            } else if (uri.equals("/api/login")) {
                if (session.getMethod() == Method.POST) {
                    Map<String, String> body = new java.util.HashMap<>();
                    session.parseBody(body);
                    String postData = body.get("postData");
                    JSONObject credentials = null;
                    if (postData != null && !postData.isEmpty()) {
                        try {
                            credentials = new JSONObject(postData);
                        } catch (Exception ignored) {}
                    }
                    String username = credentials != null ? credentials.optString("username", "").trim() : "";
                    String password = credentials != null ? credentials.optString("password", "").trim() : "";
                    if (username.isEmpty() || password.isEmpty()) {
                        return createUnauthorizedResponse("Invalid credentials");
                    }
                    String token = sessionManager.login(username, password);
                    if (token == null) {
                        return createUnauthorizedResponse("Invalid credentials");
                    }
                    User user = sessionManager.getUser(username);
                    JSONObject resp = new JSONObject();
                    resp.put("token", token);
                    JSONObject userObj = new JSONObject();
                    userObj.put("username", user.getUsername());
                    userObj.put("role", user.getRole().getValue());
                    JSONArray perms = new JSONArray();
                    for (String p : user.getPermissions()) perms.put(p);
                    userObj.put("permissions", perms);
                    resp.put("user", userObj);
                    response = newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());
                    addCorsHeaders(response);
                    return response;
                } else {
                    response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{\"error\":\"Method not allowed\",\"status\":405}");
                    addCorsHeaders(response);
                    return response;
                }
            }

            // Authentication verification for all protected /api/ endpoints
            String token = sessionManager.extractToken(session);
            User user = token != null ? sessionManager.getUserByToken(token) : null;
            if (user == null) {
                return createUnauthorizedResponse("Unauthorized");
            }

            Role role = user.getRole();
            String username = user.getUsername();

            if (uri.equals("/api/users")) {
                if (role != Role.ADMIN) {
                    return createForbiddenResponse("Only Admin can manage users");
                }
                if (session.getMethod() == Method.GET) {
                    List<User> list = sessionManager.getAllUsers();
                    JSONArray arr = new JSONArray();
                    for (User u : list) {
                        JSONObject uo = new JSONObject();
                        uo.put("username", u.getUsername());
                        uo.put("role", u.getRole().getValue());
                        arr.put(uo);
                    }
                    JSONObject res = new JSONObject();
                    res.put("users", arr);
                    response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                } else if (session.getMethod() == Method.POST) {
                    Map<String, String> body = new java.util.HashMap<>();
                    session.parseBody(body);
                    String postData = body.get("postData");
                    JSONObject uReq = new JSONObject(postData != null ? postData : "{}");
                    String newU = uReq.optString("username", "").trim();
                    String newP = uReq.optString("password", "password").trim();
                    String newR = uReq.optString("role", "viewer").trim();
                    if (newU.isEmpty()) {
                        response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"username required\",\"status\":400}");
                    } else if (!Role.isValidRole(newR)) {
                        response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid role\",\"status\":400}");
                    } else {
                        boolean created = sessionManager.createUser(newU, newP, Role.fromString(newR));
                        JSONObject res = new JSONObject();
                        res.put("success", created);
                        response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                    }
                } else {
                    response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{\"error\":\"Method not allowed\",\"status\":405}");
                }
            } else if (uri.equals("/api/users/update_role")) {
                if (role != Role.ADMIN) {
                    return createForbiddenResponse("Only Admin can manage users");
                }
                Map<String, String> body = new java.util.HashMap<>();
                session.parseBody(body);
                String postData = body.get("postData");
                JSONObject rReq = new JSONObject(postData != null ? postData : "{}");
                String targetUsername = rReq.optString("targetUsername", rReq.optString("username", "")).trim();
                String newRoleStr = rReq.optString("role", "").trim();
                if (targetUsername.isEmpty() || newRoleStr.isEmpty()) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"targetUsername and role required\",\"status\":400}");
                } else if (!Role.isValidRole(newRoleStr)) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid role\",\"status\":400}");
                } else {
                    User targetUser = sessionManager.getUser(targetUsername);
                    if (targetUser == null) {
                        response = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"User not found\",\"status\":404}");
                    } else {
                        boolean updated = sessionManager.updateUserRole(targetUsername, Role.fromString(newRoleStr));
                        if (!updated) {
                            response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Cannot demote the sole system administrator\",\"status\":400}");
                        } else {
                            JSONObject res = new JSONObject();
                            res.put("success", true);
                            response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                        }
                    }
                }
            } else if (uri.equals("/api/users/delete")) {
                if (role != Role.ADMIN) {
                    return createForbiddenResponse("Only Admin can manage users");
                }
                Map<String, String> body = new java.util.HashMap<>();
                session.parseBody(body);
                String postData = body.get("postData");
                JSONObject dReq = new JSONObject(postData != null ? postData : "{}");
                String targetUsername = dReq.optString("targetUsername", dReq.optString("username", "")).trim();
                if (targetUsername.isEmpty()) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"targetUsername required\",\"status\":400}");
                } else {
                    User targetUser = sessionManager.getUser(targetUsername);
                    if (targetUser == null) {
                        response = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"User not found\",\"status\":404}");
                    } else {
                        boolean deleted = sessionManager.deleteUser(targetUsername);
                        if (!deleted) {
                            response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Cannot delete the sole system administrator\",\"status\":400}");
                        } else {
                            JSONObject res = new JSONObject();
                            res.put("success", true);
                            response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                        }
                    }
                }
            } else if (uri.startsWith("/api/settings")) {
                if (role != Role.ADMIN) {
                    return createForbiddenResponse("Only Admin can modify server settings");
                }
                JSONObject res = new JSONObject();
                res.put("success", true);
                res.put("server_name", "StoreMe Enterprise Host");
                response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
            } else if (uri.equals("/api/list")) {
                String path = params.getOrDefault("path", "");
                response = newFixedLengthResponse(Response.Status.OK, "application/json", listDirectory(path));
            } else if (uri.startsWith("/api/preview")) {
                String path = params.getOrDefault("path", "");
                File file = new File(path);
                if (!file.exists() || file.isDirectory()) {
                    response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
                } else {
                    InputStream fis = new FileInputStream(file);
                    String mimeType = getMimeTypeForFileName(file.getName());
                    response = newChunkedResponse(Response.Status.OK, mimeType, fis);
                    response.addHeader("Cache-Control", "no-store");
                    response.addHeader("Accept-Ranges", "bytes");
                }
            } else if (uri.startsWith("/api/download")) {
                String path = params.getOrDefault("path", "");
                File file = new File(path);
                if (!file.exists() || file.isDirectory()) {
                    response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
                } else {
                    InputStream fis = new FileInputStream(file);
                    String mimeType = getMimeTypeForFileName(file.getName());
                    response = newChunkedResponse(Response.Status.OK, mimeType, fis);
                    response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                    response.addHeader("Content-Length", String.valueOf(file.length()));
                    response.addHeader("Cache-Control", "no-store");
                    response.addHeader("Accept-Ranges", "bytes");
                }
            } else if (uri.startsWith("/api/zip_status")) {
                String taskId = params.getOrDefault("taskId", "");
                JSONObject status = zipTasks.get(taskId);
                if (status == null) {
                    response = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}");
                } else {
                    response = newFixedLengthResponse(Response.Status.OK, "application/json", status.toString());
                }
            } else if (uri.startsWith("/api/zip") && session.getMethod() == Method.POST) {
                java.util.Map<String, String> body = new java.util.HashMap<>();
                session.parseBody(body);
                String jsonStr = body.get("postData");
                org.json.JSONArray paths = new org.json.JSONArray(jsonStr);
                
                long totalBytes = 0;
                for (int i = 0; i < paths.length(); i++) {
                    totalBytes += getFolderSize(new File(paths.optString(i)));
                }
                
                String taskId = java.util.UUID.randomUUID().toString();
                JSONObject status = new JSONObject();
                try {
                    status.put("total", totalBytes);
                    status.put("processed", 0L);
                    status.put("done", false);
                } catch(Exception e){}
                zipTasks.put(taskId, status);
                
                new Thread(() -> {
                    try {
                        File tempZip = new File(context.getCacheDir(), "multi_download_" + System.currentTimeMillis() + ".zip");
                        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(tempZip))) {
                            for (int i = 0; i < paths.length(); i++) {
                                String fp = paths.optString(i);
                                File f = new File(fp);
                                if (f.exists()) {
                                    zipPathRecursive(f, zos, "", status);
                                }
                            }
                        }
                        status.put("done", true);
                        status.put("downloadUrl", "/api/download?path=" + java.net.URLEncoder.encode(tempZip.getAbsolutePath(), "UTF-8"));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
                
                JSONObject res = new JSONObject();
                try { res.put("taskId", taskId); } catch(Exception e){}
                response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
            } else if (uri.startsWith("/api/storage_breakdown")) {
                JSONArray data = StorageHelper.getStorageBreakdown(context);
                response = newFixedLengthResponse(Response.Status.OK, "application/json", data.toString());
            } else if (uri.startsWith("/api/list_category")) {
                String category = params.getOrDefault("category", "");
                JSONArray files = StorageHelper.listCategory(context, category);
                response = newFixedLengthResponse(Response.Status.OK, "application/json", files.toString());
            } else if (uri.startsWith("/api/delete")) {
                if (role == Role.VIEWER) {
                    return createForbiddenResponse("Viewer role is not permitted to perform this operation");
                }
                String path = params.getOrDefault("path", "");
                if (path.isEmpty() || path.contains("..")) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\",\"status\":400}");
                } else {
                    File file = new File(path);
                    if (!file.exists()) {
                        response = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not Found\",\"status\":404,\"message\":\"File not found\"}");
                    } else {
                        String owner = sessionManager.getOwnershipStore().getOwner(file.getAbsolutePath());
                        boolean hasRecorded = sessionManager.getOwnershipStore().hasRecordedOwner(file.getAbsolutePath());
                        if (role == Role.UPLOADER && hasRecorded && owner != null && !owner.isEmpty() && !username.equals(owner) && !"admin".equals(owner)) {
                            return createForbiddenResponse("Cannot delete files owned by another user");
                        }
                        boolean deleted = deleteRecursive(file);
                        sessionManager.getOwnershipStore().removeOwner(file.getAbsolutePath());
                        JSONObject res = new JSONObject();
                        res.put("success", deleted);
                        response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                    }
                }
            } else if (uri.startsWith("/api/move")) {
                if (role == Role.VIEWER) {
                    return createForbiddenResponse("Viewer role is not permitted to perform this operation");
                }
                String from = params.getOrDefault("from", "");
                String to = params.getOrDefault("to", "");
                if (from.contains("..") || to.contains("..")) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\",\"status\":400}");
                } else {
                    File srcFile = new File(from);
                    File dstFile = new File(to);
                    if (!srcFile.exists()) {
                        response = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not Found\",\"status\":404}");
                    } else {
                        String owner = sessionManager.getOwnershipStore().getOwner(srcFile.getAbsolutePath());
                        boolean hasRecorded = sessionManager.getOwnershipStore().hasRecordedOwner(srcFile.getAbsolutePath());
                        if (role == Role.UPLOADER && hasRecorded && owner != null && !owner.isEmpty() && !username.equals(owner) && !"admin".equals(owner)) {
                            return createForbiddenResponse("Cannot modify files owned by another user");
                        }
                        boolean moved = srcFile.renameTo(dstFile);
                        if (moved) {
                            sessionManager.getOwnershipStore().setOwner(dstFile.getAbsolutePath(), owner, role, dstFile.length());
                            sessionManager.getOwnershipStore().removeOwner(srcFile.getAbsolutePath());
                        }
                        JSONObject res = new JSONObject();
                        res.put("success", moved);
                        response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                    }
                }
            } else if (uri.startsWith("/api/zip")) {
                String path = params.getOrDefault("path", "");
                File dir = new File(path);
                if (!dir.exists() || !dir.isDirectory()) {
                    response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found");
                } else {
                    java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
                    java.io.PipedInputStream pis = new java.io.PipedInputStream(pos, 1024 * 1024);
                    new Thread(() -> {
                        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(pos)) {
                            zipDirectoryRecursive(zos, dir, "");
                        } catch (Exception e) { e.printStackTrace(); }
                        finally {
                            try { pos.close(); } catch (Exception ignored) {}
                        }
                    }).start();
                    response = newChunkedResponse(Response.Status.OK, "application/zip", pis);
                    response.addHeader("Content-Disposition", "attachment; filename=\"" + dir.getName() + ".zip\"");
                }
            } else if (uri.startsWith("/api/upload")) {
                if (role == Role.VIEWER) {
                    return createForbiddenResponse("Viewer role is not permitted to perform this operation");
                }
                String dirPath = params.getOrDefault("path", "");
                if (dirPath.isEmpty()) dirPath = Environment.getExternalStorageDirectory().getAbsolutePath();
                if (dirPath.contains("..")) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\",\"status\":400}");
                } else {
                    Map<String, String> files = new java.util.HashMap<>();
                    session.parseBody(files);
                    JSONObject res = new JSONObject();
                    boolean saved = false;
                    for (Map.Entry<String, String> entry : files.entrySet()) {
                        String tmpPath = entry.getValue();
                        String fileName = params.getOrDefault(entry.getKey(), "uploaded_file");
                        if (fileName.contains("..")) continue;
                        File tmpFile = new File(tmpPath);
                        File destFile = new File(dirPath, fileName);
                        if (destFile.exists()) {
                            boolean hasRecorded = sessionManager.getOwnershipStore().hasRecordedOwner(destFile.getAbsolutePath());
                            String currentOwner = sessionManager.getOwnershipStore().getOwner(destFile.getAbsolutePath());
                            if (role == Role.UPLOADER && hasRecorded && currentOwner != null && !currentOwner.isEmpty() && !username.equals(currentOwner) && !"admin".equals(currentOwner)) {
                                return createForbiddenResponse("Cannot overwrite files owned by another user");
                            }
                        }
                        if (!tmpFile.renameTo(destFile)) {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(tmpFile);
                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
                                byte[] buffer = new byte[65536];
                                int len;
                                while ((len = fis.read(buffer)) > 0) fos.write(buffer, 0, len);
                                saved = true;
                                tmpFile.delete();
                            } catch (Exception e) { e.printStackTrace(); }
                        } else {
                            saved = true;
                        }
                        if (saved) {
                            sessionManager.getOwnershipStore().setOwner(destFile.getAbsolutePath(), username, role, destFile.length());
                        }
                    }
                    res.put("success", saved);
                    res.put("owner", username);
                    response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                }
            } else if (uri.startsWith("/api/mkdir")) {
                if (role == Role.VIEWER) {
                    return createForbiddenResponse("Viewer role is not permitted to perform this operation");
                }
                String dirPath = params.getOrDefault("path", "");
                String name = params.getOrDefault("name", "");
                if (dirPath.contains("..") || name.contains("..")) {
                    response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid path\",\"status\":400}");
                } else {
                    File newDir = new File(dirPath, name);
                    boolean created = newDir.mkdirs();
                    JSONObject res = new JSONObject();
                    res.put("success", created);
                    response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
                }
            } else if (uri.startsWith("/api/search")) {
                String q = params.getOrDefault("q", "").toLowerCase();
                JSONArray array = new JSONArray();
                if (!q.isEmpty()) {
                    searchFiles(Environment.getExternalStorageDirectory(), q, array);
                }
                JSONObject res = new JSONObject();
                res.put("files", array);
                res.put("path", "Search Results");
                response = newFixedLengthResponse(Response.Status.OK, "application/json", res.toString());
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
        } catch (Exception e) {
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }

        addCorsHeaders(response);
        return response;
    }

    private void searchFiles(File dir, String q, JSONArray array) {
        if (array.length() >= 200) return;
        try {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (array.length() >= 200) return;
                    try {
                        if (f.getName().toLowerCase().contains(q)) {
                            JSONObject obj = new JSONObject();
                            obj.put("name", f.getName());
                            obj.put("path", f.getAbsolutePath());
                            obj.put("isDir", f.isDirectory());
                            obj.put("size", f.isDirectory() ? 0 : f.length());
                            obj.put("modified", f.lastModified());
                            array.put(obj);
                        }
                        if (f.isDirectory()) {
                            searchFiles(f, q, array);
                        }
                    } catch (Exception innerE) {
                    }
                }
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("name", "DEBUG ERROR: " + e.getMessage());
                err.put("path", "");
                err.put("size", 0);
                err.put("isDir", false);
                array.put(err);
            } catch (Exception ex) {}
        }
    }

    private void zipDirectoryRecursive(java.util.zip.ZipOutputStream zos, File file, String base) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipDirectoryRecursive(zos, child, base + file.getName() + "/");
                }
            }
        } else {
            zos.putNextEntry(new java.util.zip.ZipEntry(base + file.getName()));
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                }
            }
            zos.closeEntry();
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return file.delete();
    }

    private String listDirectory(String path) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        File dir = path.isEmpty() ? root : new File(path);
        JSONArray array = new JSONArray();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                JSONObject obj = new JSONObject();
                obj.put("name", f.getName());
                obj.put("path", f.getAbsolutePath());
                obj.put("isDir", f.isDirectory());
                obj.put("size", f.isDirectory() ? 0 : f.length());
                obj.put("modified", f.lastModified());
                obj.put("owner", sessionManager.getOwnershipStore().getOwner(f.getAbsolutePath()));
                array.put(obj);
            }
        }
        JSONObject response = new JSONObject();
        response.put("files", array);
        response.put("path", dir.getAbsolutePath());
        return response.toString();
    }

    private String getMimeTypeForFileName(String filename) {
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf(".") + 1).toLowerCase() : "";
        switch (ext) {
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "mp3": return "audio/mpeg";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    private String buildFullUI() {
        return "<!DOCTYPE html>" +
        "<html lang='en'>" +
        "<head>" +
        "<script async src='https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1234567890123456' crossorigin='anonymous'></script>" +
        "<meta charset='UTF-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>StoreMe Drive</title>" +
        "<link href='https://fonts.googleapis.com/icon?family=Material+Icons' rel='stylesheet'>" +
        "<style>" +
        ":root{--bg:#f8fafc;--surface:#ffffff;--border:#e2e8f0;--primary:#1a73e8;--primary-light:rgba(26,115,232,0.1);--text:#1e293b;--text-muted:#64748b;--danger:#ef4444;--success:#22c55e;}" +
        "*{margin:0;padding:0;box-sizing:border-box;}" +
        "html,body{height:100%;overflow:hidden;font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);}" +
        "#app{display:flex;height:100vh;overflow:hidden;}" +
        ".sidebar{width:220px;min-width:220px;background:#ffffff;border-right:1px solid var(--border);display:flex;flex-direction:column;padding:16px 0;}" +
        ".brand{display:flex;align-items:center;gap:10px;padding:0 20px 20px;border-bottom:1px solid var(--border);margin-bottom:12px;}" +
        ".brand span{font-size:24px;color:var(--primary);}" +
        ".brand h1{font-size:18px;font-weight:700;color:var(--text);}" +
        ".nav-item{display:flex;align-items:center;gap:12px;padding:10px 20px;cursor:pointer;border-radius:0;transition:background 0.2s;color:var(--text-muted);font-size:14px;}" +
        ".nav-item:hover,.nav-item.active{background:var(--primary-light);color:var(--primary);}" +
        ".nav-item .material-icons{font-size:20px;}" +
        ".sidebar-bottom{margin-top:auto;padding:16px 20px;border-top:1px solid var(--border);}" +
        ".access-link{font-size:11px;color:var(--text-muted);margin-bottom:6px;word-break:break-all;}" +
        ".access-link a{color:var(--primary);text-decoration:none;}" +
        ".main{flex:1;display:flex;flex-direction:column;overflow:hidden;}" +
        ".topbar{display:flex;align-items:center;gap:12px;padding:12px 24px;border-bottom:1px solid var(--border);background:#ffffff;}" +
        ".topbar-title{font-size:16px;font-weight:600;}" +
        ".search-bar{display:flex;align-items:center;gap:8px;background:var(--bg);border:1px solid var(--border);border-radius:20px;padding:6px 14px;flex:1;max-width:400px;margin:0 auto;}" +
        ".search-bar input{background:none;border:none;outline:none;color:var(--text);font-size:13px;width:100%;}" +
        ".search-bar .material-icons{font-size:18px;color:var(--text-muted);}" +
        ".upload-btn{display:flex;align-items:center;gap:6px;background:var(--primary);color:#fff;border:none;border-radius:8px;padding:8px 16px;cursor:pointer;font-size:13px;font-weight:600;}" +
        ".upload-btn .material-icons{font-size:18px;}" +
        ".breadcrumb{display:flex;align-items:center;gap:4px;padding:10px 24px;font-size:13px;color:var(--text-muted);}" +
        ".breadcrumb span{cursor:pointer;color:var(--primary);}" +
        ".breadcrumb span:hover{text-decoration:underline;}" +
        ".breadcrumb .sep{color:var(--text-muted);cursor:default;}" +
        ".file-area{flex:1;overflow-y:auto;padding:0 16px 16px;}" +
        ".file-table{width:100%;border-collapse:separate;border-spacing:0 6px;}" +
        ".file-table th{text-align:left;padding:8px 12px;font-size:12px;color:var(--text-muted);font-weight:500;}" +
        ".file-row td{background:var(--surface);padding:10px 12px;font-size:13px;vertical-align:middle;border-top:1px solid var(--border);border-bottom:1px solid var(--border); transition:background 0.2s;}" +
        ".file-row td:first-child{border-left:1px solid var(--border);border-top-left-radius:8px;border-bottom-left-radius:8px;}" +
        ".file-row td:last-child{border-right:1px solid var(--border);border-top-right-radius:8px;border-bottom-right-radius:8px;}" +
        ".file-row:hover td{background:var(--primary-light);}" +
        ".file-name-cell{display:flex;align-items:center;gap:10px;}" +
        ".file-icon{font-size:20px;width:24px;text-align:center;}" +
        ".actions{display:flex;gap:4px;opacity:0;transition:opacity 0.2s;}" +
        ".file-row:hover .actions{opacity:1;}" +
        ".action-btn{background:none;border:none;cursor:pointer;color:var(--text-muted);padding:4px;border-radius:6px;display:flex;align-items:center;transition:background 0.2s,color 0.2s;}" +
        ".action-btn:hover{background:var(--border);color:var(--text);}" +
        ".action-btn.danger:hover{background:rgba(239,68,68,0.2);color:var(--danger);}" +
        ".action-btn .material-icons{font-size:18px;}" +
        ".empty-state{display:flex;flex-direction:column;align-items:center;justify-content:center;height:200px;color:var(--text-muted);gap:12px;}" +
        ".empty-state .material-icons{font-size:48px;opacity:0.4;}" +
        ".modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.4);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:1000;display:none;}" +
        ".modal-overlay.show{display:flex;}" +
        ".modal{background:#ffffff;border:1px solid var(--border);border-radius:12px;max-width:90vw;max-height:90vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 10px 25px rgba(0,0,0,0.1);}" +
        ".modal-header{display:flex;align-items:center;justify-content:space-between;padding:16px 20px;border-bottom:1px solid var(--border);}" +
        ".modal-header h3{font-size:15px;font-weight:600;color:var(--text);}" +
        ".modal-close{background:none;border:none;color:var(--text-muted);cursor:pointer;padding:4px;}" +
        ".modal-close .material-icons{font-size:22px;}" +
        ".modal-body{flex:1;overflow:auto;padding:16px;display:flex;align-items:center;justify-content:center;}" +
        ".modal-body img,.modal-body video{max-width:100%;max-height:70vh;border-radius:8px;}" +
        ".modal-body iframe{width:80vw;height:70vh;border:none;border-radius:8px;}" +
        "#toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:#1e293b;color:#fff;border:none;padding:12px 20px;border-radius:8px;font-size:13px;display:none;z-index:2000;box-shadow:0 4px 6px rgba(0,0,0,0.1);}" +
        "</style>" +
        "</head>" +
        "<body>" +
        "<div id='app'>" +
        "<div class='sidebar'>" +
        "  <div class='brand'><span class='material-icons'>cloud</span><h1>StoreMe</h1></div>" +
        "  <div class='nav-item active' onclick='loadDir(\"\", this)'><span class='material-icons'>devices</span><span>My Drive</span></div>" +
        "  <div class='nav-item' onclick='loadStarred(this)'><span class='material-icons'>star</span><span>Starred</span></div>" +
        "  <div class='nav-item' onclick='loadStorageDashboard(this)'><span class='material-icons'>data_usage</span><span>Storage</span></div>" +
        "  <div class='sidebar-bottom'>" +
        "    <div class='access-link'>📶 Local: <strong>This Device</strong></div>" +
        "    <div class='access-link'>🌍 Global: <a href='https://storemeweb.vercel.app' target='_blank'>storemeweb.vercel.app</a></div>" +
        "  </div>" +
        "</div>" +
        "<div class='main'>" +
        "  <div class='topbar'>" +
        "    <span class='topbar-title'>StoreMe Drive</span>" +
        "    <div class='search-bar'><span class='material-icons'>search</span><input id='searchInput' type='text' placeholder='Search files...' oninput='filterFiles()'></div>" +
        "    <button class='upload-btn' onclick='let n=prompt(\"Folder name:\"); if(n) fetch(\"/api/mkdir?path=\"+encodeURIComponent(currentPath)+\"&name=\"+encodeURIComponent(n)).then(()=>loadDir(currentPath));' style='background:#10b981;'><span class='material-icons'>create_new_folder</span>New Folder</button>" +
        "    <button class='upload-btn' onclick='downloadSelected()' style='background:#f59e0b;'><span class='material-icons'>download</span>Download</button>" +
        "    <button class='upload-btn' onclick='document.getElementById(\"uploadInput\").click()'><span class='material-icons'>upload</span>Upload</button>" +
        "    <input type='file' id='uploadInput' style='display:none' multiple onchange='uploadFiles(event)'>" +
        "  </div>" +
        "  <div class='breadcrumb' id='breadcrumb'></div>" +
        "  <div class='file-area'>" +
        "    <table class='file-table'>" +
        "      <thead><tr><th style='width:30px'><input type='checkbox' id='selectAllCb' onchange='toggleSelectAll()'></th><th>Name</th><th>Modified</th><th>Size</th><th></th></tr></thead>" +
        "      <tbody id='fileList'></tbody>" +
        "    </table>" +
        "  </div>" +
        "</div>" +
        "</div>" +
        "<div class='modal-overlay' id='previewModal'>" +
        "  <div class='modal'>" +
        "    <div class='modal-header'>" +
        "      <h3 id='previewTitle'>Preview</h3>" +
        "      <button class='modal-close' onclick='closeModal()'><span class='material-icons'>close</span></button>" +
        "    </div>" +
        "    <div class='modal-body' id='previewBody'></div>" +
        "  </div>" +
        "</div>" +
        "<div class='modal-overlay' id='moveModal'>" +
        "  <div class='modal' style='min-width:360px'>" +
        "    <div class='modal-header'><h3>Move / Rename</h3><button class='modal-close' onclick='document.getElementById(\"moveModal\").classList.remove(\"show\")'><span class='material-icons'>close</span></button></div>" +
        "    <div class='modal-body' style='flex-direction:column;gap:12px;align-items:stretch'>" +
        "      <label style='font-size:13px;color:var(--text-muted)'>New full path:</label>" +
        "      <input id='moveTarget' type='text' style='background:var(--surface);border:1px solid var(--border);color:var(--text);border-radius:8px;padding:10px 14px;font-size:13px;outline:none;width:100%'>" +
        "      <button onclick='confirmMove()' style='background:var(--primary);color:#fff;border:none;border-radius:8px;padding:10px;cursor:pointer;font-size:14px;font-weight:600'>Move</button>" +
        "    </div>" +
        "  </div>" +
        "</div>" +
        "<div id='progressModal' class='modal' style='display:none;position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);z-index:2000;'>" +
        "  <div class='modal-content' style='max-width:400px; width:90vw; background:white; border-radius:12px; box-shadow:0 10px 25px rgba(0,0,0,0.2); text-align:center; padding:24px;'>" +
        "    <h3 id='progressTitle' style='margin-top:0;'>Processing...</h3>" +
        "    <div style='background:#e2e8f0; border-radius:8px; height:12px; margin:20px 0; overflow:hidden;'>" +
        "      <div id='progressBar' style='background:var(--primary); width:0%; height:100%; transition:width 0.2s;'></div>" +
        "    </div>" +
        "    <div style='display:flex; justify-content:space-between; font-size:13px; color:var(--text-muted);'>" +
        "      <span id='progressText'>0%</span>" +
        "      <span id='progressSpeed'>0 KB/s</span>" +
        "    </div>" +
        "  </div>" +
        "</div>" +
        "<div id='toast'></div>" +
        "<script>" +
        "let currentPath='';" +
        "let allFiles=[];" +
        "let moveFromPath='';" +
        "let isStarredMode=false;" +
        "let localStars = JSON.parse(localStorage.getItem('sm_stars') || '[]');" +
        "async function loadDir(path, el){" +
        "  isStarredMode = false;" +
        "  if(el) {" +
        "    document.querySelectorAll('.nav-item').forEach(e => e.classList.remove('active'));" +
        "    el.classList.add('active');" +
        "  } else {" +
        "    document.querySelectorAll('.nav-item').forEach(e => e.classList.remove('active'));" +
        "    document.querySelector('.nav-item').classList.add('active');" +
        "  }" +
        "  currentPath=path;" +
        "  buildBreadcrumb(path);" +
        "  const r=await fetch('/api/list?path='+encodeURIComponent(path)+'&t='+Date.now());" +
        "  const d=await r.json();" +
        "  allFiles=d.files||[];" +
        "  renderFiles(allFiles);" +
        "}" +
        "function loadStarred(el) {" +
        "  document.querySelectorAll('.nav-item').forEach(e => e.classList.remove('active'));" +
        "  if(el) el.classList.add('active');" +
        "  isStarredMode = true;" +
        "  currentPath = 'Starred';" +
        "  document.getElementById('breadcrumb').innerHTML = '<span class=\"breadcrumb\">Starred</span>';" +
        "  localStars = JSON.parse(localStorage.getItem('sm_stars') || '[]');" +
        "  let fakeFiles = localStars.map(p => {" +
        "    let name = p.split('/').pop();" +
        "    return { name: name, path: p, isDir: !name.includes('.'), size: 0, modified: 0 };" +
        "  });" +
        "  allFiles = fakeFiles;" +
        "  renderFiles(allFiles);" +
        "}" +
        "function renderFiles(files){" +
        "  const tbody=document.getElementById('fileList');" +
        "  tbody.innerHTML='';" +
        "  if(!Array.isArray(files)) files = [];" +
        "  const sorted=files.sort((a,b)=>b.isDir-a.isDir||a.name.localeCompare(b.name));" +
        "  if(sorted.length===0){" +
        "    tbody.innerHTML='<tr><td colspan=5 style=\"border:none\"><div class=empty-state><span class=material-icons>folder_open</span><p>Empty folder</p></div></td></tr>';" +
        "    return;" +
        "  }" +
        "  sorted.forEach(f=>{" +
        "    const tr=document.createElement('tr');" +
        "    tr.className='file-row';" +
        "    const icon=f.isDir?'folder':getIcon(f.name);" +
        "    const iconStyle=f.isDir?'color:#f59e0b':'color:#94a3b8';" +
        "    const date=new Date(f.modified).toLocaleDateString();" +
        "    const size=f.isDir?'--':fmt(f.size);" +
        "    const previewBtn=canPreview(f.name)?`<button class='action-btn' title='Preview' onclick='previewFile(${JSON.stringify(f.path)},${JSON.stringify(f.name)})'><span class='material-icons'>visibility</span></button>`:'';" +
        "    const isStar = localStars.includes(f.path);" +
        "    const starIcon = isStar ? 'star' : 'star_border';" +
        "    const starStyle = isStar ? 'color:#f59e0b' : 'color:var(--text-muted)';" +
        "    const starBtn = `<button class='action-btn' title='Star' onclick='toggleStar(event, ${JSON.stringify(f.path)})'><span class='material-icons' style='${starStyle}'>${starIcon}</span></button>`;" +
        "    tr.innerHTML=`" +
        "      <td><input type='checkbox' class='row-cb' value='${f.path}' onchange='updateMulti()'></td>" +
        "      <td><div class='file-name-cell'><span class='material-icons file-icon' style='${iconStyle}'>${icon}</span><span>${f.name}</span></div></td>" +
        "      <td style='color:var(--text-muted);font-size:12px'>${date}</td>" +
        "      <td style='color:var(--text-muted);font-size:12px'>${size}</td>" +
        "      <td><div class='actions'>" +
        "        ${starBtn}" +
        "        ${previewBtn}" +
        "        ${!f.isDir?`<button class='action-btn' title='Download' onclick='downloadFile(${JSON.stringify(f.path)})'><span class='material-icons'>download</span></button>`:`<button class='action-btn' title='Download as ZIP' onclick='event.stopPropagation(); window.location=\"/api/zip?path=\"+encodeURIComponent(${JSON.stringify(f.path)})'><span class='material-icons'>folder_zip</span></button>`}" +
        "        <button class='action-btn' title='Move/Rename' onclick='openMove(${JSON.stringify(f.path)})'><span class='material-icons'>drive_file_move</span></button>" +
        "        <button class='action-btn danger' title='Delete' onclick='deleteFile(${JSON.stringify(f.path)},${JSON.stringify(f.name)})'><span class='material-icons'>delete</span></button>" +
        "      </div></td>" +
        "    `;" +
        "    if(f.isDir) tr.querySelector('.file-name-cell').style.cursor='pointer';" +
        "    if(f.isDir) tr.querySelector('.file-name-cell').onclick=()=>loadDir(f.path);" +
        "    tbody.appendChild(tr);" +
        "  });" +
        "}" +
        "function toggleStar(e, path) {" +
        "  e.stopPropagation();" +
        "  localStars = JSON.parse(localStorage.getItem('sm_stars') || '[]');" +
        "  if(localStars.includes(path)) localStars = localStars.filter(p=>p!==path);" +
        "  else localStars.push(path);" +
        "  localStorage.setItem('sm_stars', JSON.stringify(localStars));" +
        "  if(isStarredMode) loadStarred(); else renderFiles(allFiles);" +
        "}" +
        "function buildBreadcrumb(path){" +
        "  const bc=document.getElementById('breadcrumb');" +
        "  bc.innerHTML='';" +
        "  const root=document.createElement('span');" +
        "  root.textContent='My Drive';" +
        "  root.onclick=()=>loadDir('');" +
        "  bc.appendChild(root);" +
        "  if(!path)return;" +
        "  const parts=path.split('/').filter(Boolean);" +
        "  let built='';" +
        "  parts.forEach((p,i)=>{" +
        "    built+='/'+p;" +
        "    const sep=document.createElement('span');" +
        "    sep.className='sep';sep.textContent=' › ';" +
        "    bc.appendChild(sep);" +
        "    const seg=document.createElement('span');" +
        "    const capPath=built;" +
        "    seg.textContent=p;" +
        "    seg.onclick=()=>loadDir(capPath);" +
        "    bc.appendChild(seg);" +
        "  });" +
        "}" +
        "let searchTimeout;" +
        "function filterFiles(){" +
        "  clearTimeout(searchTimeout);" +
        "  searchTimeout = setTimeout(async () => {" +
        "    const q=document.getElementById('searchInput').value.toLowerCase();" +
        "    if(!q) { if(isStarredMode) loadStarred(); else renderFiles(allFiles); return; }" +
        "    const r = await fetch('/api/search?q=' + encodeURIComponent(q) + '&t=' + Date.now());" +
        "    const d = await r.json();" +
        "    renderFiles(d.files || []);" +
        "  }, 300);" +
        "}" +
        "function downloadFile(path){window.location='/api/download?path='+encodeURIComponent(path);}" +
        "function canPreview(name){" +
        "  const e=name.split('.').pop().toLowerCase();" +
        "  return['jpg','jpeg','png','gif','webp','mp4','mkv','mp3','pdf','txt'].includes(e);" +
        "}" +
        "function previewFile(path,name){" +
        "  const e=name.split('.').pop().toLowerCase();" +
        "  const body=document.getElementById('previewBody');" +
        "  document.getElementById('previewTitle').textContent=name;" +
        "  const url='/api/preview?path='+encodeURIComponent(path);" +
        "  if(['jpg','jpeg','png','gif','webp'].includes(e)) body.innerHTML=`<img src='${url}'>`;" +
        "  else if(['mp4','mkv'].includes(e)) body.innerHTML=`<video controls src='${url}' style='max-width:80vw;max-height:70vh'></video>`;" +
        "  else if(e==='mp3') body.innerHTML=`<audio controls src='${url}'></audio>`;" +
        "  else if(e==='pdf') body.innerHTML=`<iframe src='${url}'></iframe>`;" +
        "  else if(e==='txt') fetch(url).then(r=>r.text()).then(t=>{body.innerHTML=`<pre style='white-space:pre-wrap;color:var(--text);max-width:80vw;max-height:70vh;overflow:auto'>${t}</pre>`;});" +
        "  document.getElementById('previewModal').classList.add('show');" +
        "}" +
        "function closeModal(){" +
        "  const m=document.getElementById('previewModal');" +
        "  m.classList.remove('show');" +
        "  document.getElementById('previewBody').innerHTML='';" +
        "}" +
        "function openMove(path){" +
        "  moveFromPath=path;" +
        "  document.getElementById('moveTarget').value=path;" +
        "  document.getElementById('moveModal').classList.add('show');" +
        "}" +
        "async function confirmMove(){" +
        "  const to=document.getElementById('moveTarget').value.trim();" +
        "  if(!to||to===moveFromPath)return;" +
        "  await fetch('/api/move?from='+encodeURIComponent(moveFromPath)+'&to='+encodeURIComponent(to));" +
        "  document.getElementById('moveModal').classList.remove('show');" +
        "  showToast('Moved successfully');" +
        "  if(isStarredMode) loadStarred(); else loadDir(currentPath);" +
        "}" +
        "async function deleteFile(path,name){" +
        "  if(!confirm('Delete '+name+'? This cannot be undone.'))return;" +
        "  await fetch('/api/delete?path='+encodeURIComponent(path));" +
        "  showToast('Deleted: '+name);" +
        "  if(isStarredMode) loadStarred(); else loadDir(currentPath);" +
        "}" +
        "function uploadFiles(event){" +
        "  const files=[...event.target.files];" +
        "  if (files.length === 0) return;" +
        "  const progressModal = document.getElementById('progressModal');" +
        "  document.getElementById('progressTitle').textContent = 'Uploading...';" +
        "  document.getElementById('progressBar').style.width = '0%';" +
        "  document.getElementById('progressText').textContent = '0%';" +
        "  document.getElementById('progressSpeed').textContent = '0 KB/s';" +
        "  progressModal.style.display = 'block';" +
        "  const fd = new FormData();" +
        "  let url = '/api/upload?path=' + encodeURIComponent(currentPath);" +
        "  for (let i = 0; i < files.length; i++) {" +
        "    fd.append('file' + i, files[i], files[i].name);" +
        "    url += '&file' + i + '=' + encodeURIComponent(files[i].name);" +
        "  }" +
        "  const xhr = new XMLHttpRequest();" +
        "  xhr.open('POST', url);" +
        "  let startTime = Date.now();" +
        "  let startLoaded = 0;" +
        "  xhr.upload.onprogress = function(e) {" +
        "    if (e.lengthComputable) {" +
        "      let percent = (e.loaded / e.total) * 100;" +
        "      document.getElementById('progressBar').style.width = percent + '%';" +
        "      document.getElementById('progressText').textContent = percent.toFixed(1) + '%';" +
        "      let timeDiff = (Date.now() - startTime) / 1000;" +
        "      if (timeDiff > 0.5) {" +
        "        let bps = (e.loaded - startLoaded) / timeDiff;" +
        "        document.getElementById('progressSpeed').textContent = fmt(bps) + '/s';" +
        "        startTime = Date.now();" +
        "        startLoaded = e.loaded;" +
        "      }" +
        "    }" +
        "  };" +
        "  xhr.onload = function() {" +
        "    progressModal.style.display = 'none';" +
        "    showToast(files.length + ' file(s) uploaded');" +
        "    loadDir(currentPath);" +
        "  };" +
        "  xhr.onerror = function() {" +
        "    progressModal.style.display = 'none';" +
        "    alert('Upload failed');" +
        "  };" +
        "  xhr.send(fd);" +
        "}" +
        "function getIcon(name){" +
        "  const e=name.split('.').pop().toLowerCase();" +
        "  if(['mp4','mkv','avi','mov'].includes(e))return'movie';" +
        "  if(['mp3','aac','flac','wav'].includes(e))return'audio_file';" +
        "  if(['jpg','jpeg','png','gif','webp'].includes(e))return'image';" +
        "  if(['pdf'].includes(e))return'picture_as_pdf';" +
        "  if(['zip','rar','7z'].includes(e))return'folder_zip';" +
        "  if(['txt','md'].includes(e))return'description';" +
        "  if(['apk'].includes(e))return'android';" +
        "  return'insert_drive_file';" +
        "}" +
        "function fmt(b){" +
        "  if(b>1073741824)return(b/1073741824).toFixed(1)+' GB';" +
        "  if(b>1048576)return(b/1048576).toFixed(1)+' MB';" +
        "  if(b>1024)return(b/1024).toFixed(1)+' KB';" +
        "  return b+' B';" +
        "}" +
        "function showToast(msg){" +
        "  const t=document.getElementById('toast');" +
        "  t.textContent=msg;t.style.display='block';" +
        "  setTimeout(()=>t.style.display='none',3000);" +
        "}" +
        "function toggleSelectAll() {" +
        "  const checked = document.getElementById('selectAllCb').checked;" +
        "  document.querySelectorAll('.row-cb').forEach(cb => cb.checked = checked);" +
        "}" +
        "function updateMulti() {}" +
        "async function downloadAsZip(paths) {" +
        "  const res = await fetch('/api/zip', { method:'POST', body: JSON.stringify(paths) });" +
        "  const json = await res.json();" +
        "  const taskId = json.taskId;" +
        "  const progressModal = document.getElementById('progressModal');" +
        "  document.getElementById('progressTitle').textContent = 'Zipping...';" +
        "  document.getElementById('progressBar').style.width = '0%';" +
        "  document.getElementById('progressText').textContent = '0%';" +
        "  document.getElementById('progressSpeed').textContent = '0 KB/s';" +
        "  progressModal.style.display = 'block';" +
        "  let startTime = Date.now();" +
        "  let startProcessed = 0;" +
        "  const timer = setInterval(async () => {" +
        "    const r = await fetch('/api/zip_status?taskId=' + encodeURIComponent(taskId));" +
        "    const s = await r.json();" +
        "    if (s.total && s.total > 0) {" +
        "      let percent = (s.processed / s.total) * 100;" +
        "      document.getElementById('progressBar').style.width = percent + '%';" +
        "      document.getElementById('progressText').textContent = percent.toFixed(1) + '%';" +
        "    } else if (s.done) {" +
        "      document.getElementById('progressBar').style.width = '100%';" +
        "      document.getElementById('progressText').textContent = '100%';" +
        "    }" +
        "    let timeDiff = (Date.now() - startTime) / 1000;" +
        "    if (timeDiff > 0.5) {" +
        "      let bps = (s.processed - startProcessed) / timeDiff;" +
        "      document.getElementById('progressSpeed').textContent = fmt(bps) + '/s';" +
        "      startTime = Date.now();" +
        "      startProcessed = s.processed;" +
        "    }" +
        "    if (s.done) {" +
        "      clearInterval(timer);" +
        "      progressModal.style.display = 'none';" +
        "      if (s.downloadUrl) window.location = s.downloadUrl;" +
        "    }" +
        "  }, 500);" +
        "}" +
        "function downloadSelected() {" +
        "  const cbs = Array.from(document.querySelectorAll('.row-cb:checked'));" +
        "  if(cbs.length === 0) return alert('No files selected');" +
        "  const paths = cbs.map(cb => cb.value);" +
        "  if(paths.length === 1) {" +
        "    window.location = '/api/download?path=' + encodeURIComponent(paths[0]);" +
        "  } else {" +
        "    const doZip = confirm('Download ' + paths.length + ' files as ZIP? (Cancel to download separately)');" +
        "    if(doZip) {" +
        "      downloadAsZip(paths);" +
        "    } else {" +
        "      paths.forEach(p => window.open('/api/download?path=' + encodeURIComponent(p)));" +
        "    }" +
        "  }" +
        "}" +
        "async function loadStorageDashboard(el) {" +
        "  document.querySelectorAll('.nav-item').forEach(e => e.classList.remove('active'));" +
        "  if(el) el.classList.add('active');" +
        "  const bc = document.getElementById('breadcrumb'); bc.innerHTML = '<span class=\"breadcrumb\">Storage Dashboard</span>';" +
        "  const tbody = document.getElementById('fileList'); tbody.innerHTML = '<tr><td colspan=\"5\" style=\"text-align:center;padding:40px;\">Loading storage info...</td></tr>';" +
        "  const r = await fetch('/api/storage_breakdown?t=' + Date.now());" +
        "  const data = await r.json();" +
        "  let html = '<div style=\"display:flex;flex-wrap:wrap;gap:20px;padding:20px;\">';" +
        "  let totalSize = 0;" +
        "  data.forEach(item => totalSize += item.size || 0);" +
        "  data.forEach(item => {" +
        "    const pct = totalSize > 0 ? ((item.size / totalSize) * 100).toFixed(1) : 0;" +
        "    html += `<div style=\"flex:1 1 200px;background:white;border:1px solid #e2e8f0;border-radius:8px;padding:20px;cursor:pointer;box-shadow:0 1px 3px rgba(0,0,0,0.05);\" onclick=\"loadCategory('${item.category}')\">" +
        "        <div style=\"display:flex;align-items:center;margin-bottom:15px;\">" +
        "          <span class=\"material-icons\" style=\"color:${item.color || '#1a73e8'};font-size:32px;background:${item.color ? item.color + '20' : '#e8f0fe'};padding:12px;border-radius:8px;margin-right:15px;\">${item.icon || 'folder'}</span>" +
        "          <div><h3 style=\"margin:0;font-size:16px;color:#1e293b;\">${item.category}</h3><p style=\"margin:4px 0 0;font-size:13px;color:#64748b;\">${item.count} files</p></div>" +
        "        </div>" +
        "        <div>" +
        "          <div style=\"display:flex;justify-content:space-between;font-size:14px;margin-bottom:8px;font-weight:500;\"><span>${fmt(item.size)}</span><span style=\"color:#64748b;\">${pct}%</span></div>" +
        "          <div style=\"width:100%;background:#f1f5f9;height:6px;border-radius:3px;overflow:hidden;\"><div style=\"width:${pct}%;background:${item.color || '#1a73e8'};height:100%;\"></div></div>" +
        "        </div>" +
        "      </div>`;" +
        "  });" +
        "  html += '</div>';" +
        "  tbody.innerHTML = `<tr><td colspan=\"5\" style=\"padding:0;\">${html}</td></tr>`;" +
        "}" +
        "async function loadCategory(cat) {" +
        "  currentPath = 'Category: ' + cat;" +
        "  const bc = document.getElementById('breadcrumb'); bc.innerHTML = `<span class=\"breadcrumb\" onclick=\"loadStorageDashboard()\" style=\"cursor:pointer;\">Storage Dashboard</span><span class=\"sep\"> / </span><span>${cat}</span>`;" +
        "  const tbody = document.getElementById('fileList'); tbody.innerHTML = '<tr><td colspan=\"5\" style=\"text-align:center;padding:40px;\">Loading...</td></tr>';" +
        "  const r = await fetch('/api/list_category?category=' + encodeURIComponent(cat) + '&t=' + Date.now());" +
        "  const d = await r.json();" +
        "  allFiles = d || [];" +
        "  renderFiles(allFiles);" +
        "}" +
        "loadDir('');" +
        "</script>" +
        "</body></html>";
    }
}
