package com.storeme;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

public class StorageHelper {
    public static JSONArray getStorageBreakdown(Context context) {
        JSONArray res = new JSONArray();
        try {
            res.put(queryCategory(context, "Images", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "#c084fc", "image"));
            res.put(queryCategory(context, "Videos", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "#f87171", "movie"));
            res.put(queryCategory(context, "Audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "#A142F4", "music_note"));
            res.put(queryCategory(context, "Downloads", MediaStore.Downloads.EXTERNAL_CONTENT_URI, "#fbbf24", "download"));
        } catch (Exception e) {}
        return res;
    }
    
    private static JSONObject queryCategory(Context context, String name, Uri uri, String color, String icon) {
        JSONObject obj = new JSONObject();
        long totalSize = 0;
        int count = 0;
        try {
            String[] projection = { MediaStore.MediaColumns.SIZE };
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                while (cursor.moveToNext()) {
                    totalSize += cursor.getLong(sizeCol);
                    count++;
                }
                cursor.close();
            }
        } catch (Exception e) {}
        
        try {
            obj.put("category", name);
            obj.put("size", totalSize);
            obj.put("count", count);
            obj.put("color", color);
            obj.put("icon", icon);
        } catch (Exception e) {}
        return obj;
    }
    
    public static JSONArray listCategory(Context context, String category) {
        JSONArray files = new JSONArray();
        Uri uri = null;
        if ("Images".equals(category)) uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        else if ("Videos".equals(category)) uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        else if ("Audio".equals(category)) uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        else if ("Downloads".equals(category)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            }
        }
        
        if (uri == null) {
            try {
                JSONObject err = new JSONObject();
                err.put("name", "DEBUG: Unknown category '" + category + "'");
                err.put("path", ""); err.put("size", 0); err.put("isDir", false);
                files.put(err);
            } catch (Exception e) {}
            return files;
        }
        
        try {
            String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                "relative_path",  // MediaStore.MediaColumns.RELATIVE_PATH = "relative_path"
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            };
            
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            
            if (cursor == null) {
                JSONObject err = new JSONObject();
                err.put("name", "DEBUG: Cursor is null for " + uri);
                err.put("path", ""); err.put("size", 0); err.put("isDir", false);
                files.put(err);
                return files;
            }
            
            if (cursor.getCount() == 0) {
                JSONObject err = new JSONObject();
                err.put("name", "DEBUG: Cursor is empty (count=0) for " + uri);
                err.put("path", ""); err.put("size", 0); err.put("isDir", false);
                files.put(err);
                cursor.close();
                return files;
            }
            
            int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int relPathCol = cursor.getColumnIndex("relative_path");
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            
            String storageRoot = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            
            while (cursor.moveToNext()) {
                try {
                    String name = nameCol != -1 ? cursor.getString(nameCol) : null;
                    String relPath = relPathCol != -1 ? cursor.getString(relPathCol) : null;
                    long size = sizeCol != -1 ? cursor.getLong(sizeCol) : 0;
                    long modified = dateCol != -1 ? cursor.getLong(dateCol) * 1000L : 0;
                    
                    if (name == null || name.isEmpty()) continue;
                    
                    // Build absolute path from relative_path + name
                    String path;
                    if (relPath != null && !relPath.isEmpty()) {
                        // relPath already has trailing slash like "DCIM/Camera/"
                        path = storageRoot + "/" + relPath + name;
                    } else {
                        // fallback: use content URI string as path (for download endpoint)
                        long id = idCol != -1 ? cursor.getLong(idCol) : 0;
                        path = Uri.withAppendedPath(uri, String.valueOf(id)).toString();
                    }
                    
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", name);
                    fileObj.put("path", path);
                    fileObj.put("size", size);
                    fileObj.put("isDir", false);
                    fileObj.put("modified", modified);
                    files.put(fileObj);
                } catch (Exception innerE) {
                    // skip single broken row
                }
            }
            cursor.close();
            
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("name", "DEBUG ERROR: " + e.getMessage());
                err.put("path", ""); err.put("size", 0); err.put("isDir", false);
                files.put(err);
            } catch (Exception ex) {}
        }
        return files;
    }
}
