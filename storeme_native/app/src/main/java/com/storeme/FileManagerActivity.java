package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Context;
import androidx.core.content.ContextCompat;

public class FileManagerActivity extends AppCompatActivity {

    // State
    private JSONArray allFiles    = new JSONArray();
    private JSONArray accumulated = new JSONArray();
    private String    currentPath = "";
    private String    activeChip  = "all";
    private boolean   sortAZ      = true;
    private boolean   gridMode    = false;
    private boolean   searchOpen  = false;

    // Views
    private RecyclerView recycler;
    private FileAdapter  adapter;
    private TextView     textStatus, textCount, btnSort;
    private View         searchBar;
    private EditText     editSearch;
    private String       currentRole = "viewer";

    // Download state
    private java.io.File             currentFile;
    private String                   currentIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String activeRole = prefs.getString("active_role", "");
        if ("host".equalsIgnoreCase(activeRole)) {
            currentRole = "admin";
        } else {
            currentRole = prefs.getString("user_role", "viewer");
        }

        String hostId = getIntent().getStringExtra("HOST_ID");

        // Bind views
        textStatus = findViewById(R.id.textStatus);
        searchBar  = findViewById(R.id.searchBar);
        editSearch = findViewById(R.id.editSearch);
        recycler   = findViewById(R.id.recyclerFiles);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.addItemDecoration(new androidx.recyclerview.widget.DividerItemDecoration(this, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL));

        // Adapter
        adapter = new FileAdapter(new FileAdapter.OnFileClickListener() {
            @Override
            public void onFileClick(JSONObject file) {
                if (file.optBoolean("isDir")) {
                    loadDirectory(file.optString("path"));
                } else {
                    openFile(file);
                }
            }
            @Override
            public void onMenuClick(View anchor, JSONObject file) {
                showFileMenu(anchor, file);
            }
        });
        recycler.setAdapter(adapter);
        applyRbacUI();

        // Back button → go up directory
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> goUp());

        // Search toggle
        View btnSearchIcon = findViewById(R.id.btnSearch);
        if (btnSearchIcon != null) {
            btnSearchIcon.setOnClickListener(v -> {
                searchOpen = !searchOpen;
                searchBar.setVisibility(searchOpen ? View.VISIBLE : View.GONE);
                if (!searchOpen) { editSearch.setText(""); refresh(); }
            });
        }
        // Search logic
        if (editSearch != null) {
            editSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            });
        }

        // Upload Button
        View btnUpload = findViewById(R.id.btnUpload);
        if (btnUpload != null) {
            btnUpload.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, 2001); // 2001 for uploads
            });
        }

        // Grid/List toggle
        View btnGrid = findViewById(R.id.btnGridList);
        if (btnGrid != null) btnGrid.setOnClickListener(v -> {
            gridMode = !gridMode;
            recycler.setLayoutManager(gridMode
                    ? new GridLayoutManager(this, 3)
                    : new LinearLayoutManager(this));
            refresh();
        });

        // Sort
        if (btnSort != null) btnSort.setOnClickListener(v -> {
            sortAZ = !sortAZ;
            btnSort.setText("Sort: " + (sortAZ ? "A→Z" : "Z→A"));
            refresh();
        });

        // Filter chips
        setupChips();

        // WebRTC listener
        ClientConnectionManager.getInstance().addListener(new ClientConnectionManager.ClientListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    safeText(textStatus, "Connected ✓");
                    loadDirectory(currentPath);
                });
            }

            @Override
            public void onMessage(JSONObject msg) {
                runOnUiThread(() -> handleMessage(msg));
            }

            @Override
            public void onBinary(java.nio.ByteBuffer buf) {
                // Handled in ClientConnectionManager
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    Toast.makeText(FileManagerActivity.this, "Connection lost or host shutdown.", Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
        // addListener will immediately call onConnected if already connected.
        // So we don't need a redundant manual check.

        // Bottom nav
        wireNav(hostId);
    }

    // ─── Directory handling ──────────────────────────────────────────────────

    private void loadDirectory(String path) {
        safeText(textStatus, "Loading...");
        try {
            JSONObject req = new JSONObject();
            req.put("action", "list_dir");
            req.put("path", path);
            ClientConnectionManager.getInstance().sendData(req.toString());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void applyRbacUI() {
        View btnUpload = findViewById(R.id.btnUpload);
        if (btnUpload != null) {
            if ("viewer".equalsIgnoreCase(currentRole)) {
                btnUpload.setVisibility(View.GONE);
            } else {
                btnUpload.setVisibility(View.VISIBLE);
            }
        }
        if (adapter != null) {
            adapter.setUserRole(currentRole);
        }
    }

    private void handleMessage(JSONObject msg) {
        try {
            String action = msg.optString("action");

            if ("auth_success".equals(action)) {
                String role = msg.optString("role", "");
                if (!role.isEmpty()) {
                    currentRole = role.toLowerCase();
                    getSharedPreferences("StoreMePrefs", MODE_PRIVATE)
                            .edit().putString("user_role", currentRole).apply();
                    applyRbacUI();
                }
            }

            if ("access_denied".equals(action)) {
                String deniedPath = msg.optString("path");
                Toast.makeText(this, "⚠ Access denied: " + deniedPath, Toast.LENGTH_SHORT).show();
                // Only terminate if a mid-session path was denied (not initial empty path)
                if (!deniedPath.isEmpty() && !deniedPath.equals("/") && !currentPath.isEmpty()
                        && deniedPath.equals(currentPath)) {
                    ClientConnectionManager.getInstance().disconnect();
                    Toast.makeText(this, "Your access has been revoked by the host.", Toast.LENGTH_LONG).show();
                    finish();
                }
                return;

            }
            
            if ("link_created".equals(action)) {
                String token = msg.optString("token");
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Share Token", token);
                clipboard.setPrimaryClip(clip);
                
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Share Link Created")
                    .setMessage(token + "\n\n(Valid for 24 hours)")
                    .setPositiveButton("Copy", (dialog, which) -> {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", null)
                    .show();
                return;
            }

            if ("directory_listing".equals(action)) {
                int chunk  = msg.optInt("chunk", 1);
                int total  = msg.optInt("total_chunks", 1);
                String path = msg.optString("path", currentPath);
                currentPath = path;

                // Check for error from host
                String error = msg.optString("error", "");
                if (!error.isEmpty()) {
                    Toast.makeText(this, "Host error: " + error, Toast.LENGTH_LONG).show();
                }

                if (chunk == 1) accumulated = new JSONArray();
                JSONArray files = msg.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    accumulated.put(files.getJSONObject(i));
                }

                if (chunk == total) {
                    buildMasterList();
                    refresh();
                    safeText(textStatus, path);
                } else {
                    safeText(textStatus, "Loading " + chunk + "/" + total + "...");
                }

            } else if ("search_results".equals(action)) {
                int chunk  = msg.optInt("chunk", 1);
                int total  = msg.optInt("total_chunks", 1);
                String query = msg.optString("query");
                
                if (chunk == 1) accumulated = new JSONArray();
                JSONArray files = msg.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    accumulated.put(files.getJSONObject(i));
                }

                if (chunk == total) {
                    allFiles = accumulated; // Directly use as master list since we don't have "parent folder" for search
                    refresh();
                    safeText(textStatus, "Search: " + query + " (" + allFiles.length() + ")");
                } else {
                    safeText(textStatus, "Searching " + chunk + "/" + total + "...");
                }

            } else if ("file_transfer_start".equals(action)) {
                String name = msg.optString("name");
                currentIntent = msg.optString("intent");
                currentFile = "preview".equals(currentIntent)
                        ? new java.io.File(getCacheDir(), name)
                        : new java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS), name);
                if ("preview".equals(currentIntent)) safeText(textStatus, "↓ " + name);
            } else if ("file_transfer_end".equals(action)) {
                if ("preview".equals(currentIntent) && currentFile != null) {
                    previewFile(currentFile);
                } else if ("download".equals(currentIntent)) {
                    Toast.makeText(this, "✓ Saved to Downloads", Toast.LENGTH_SHORT).show();
                }
            } else if ("link_created".equals(action)) {
                String tokenUrl = msg.optString("token");
                runOnUiThread(() -> {
                    Toast.makeText(FileManagerActivity.this, "Link Generated: " + tokenUrl, Toast.LENGTH_LONG).show();
                    // Copy to clipboard
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("StoreMe Link", tokenUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(FileManagerActivity.this, "Link copied to clipboard!", Toast.LENGTH_SHORT).show();
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void buildMasterList() {
        allFiles = new JSONArray();
        try {
            boolean isRoot = currentPath.equals("/storage/emulated/0/")
                    || currentPath.equals("/storage/emulated/0")
                    || currentPath.equals("/");
            if (!isRoot) {
                java.io.File f = new java.io.File(currentPath);
                String parent = f.getParent();
                if (parent == null) parent = "/storage/emulated/0/";
                JSONObject back = new JSONObject();
                back.put("name", "⬅  Parent folder");
                back.put("path", parent);
                back.put("isDir", true);
                back.put("isBack", true);
                allFiles.put(back);
            }
            for (int i = 0; i < accumulated.length(); i++) {
                allFiles.put(accumulated.getJSONObject(i));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void goUp() {
        if (!currentPath.equals("/storage/emulated/0/") && !currentPath.equals("/")) {
            java.io.File f = new java.io.File(currentPath);
            String parent = f.getParent();
            if (parent != null) { loadDirectory(parent); return; }
        }
        Toast.makeText(this, "Already at root directory", Toast.LENGTH_SHORT).show();
    }

    // ─── Filter + Sort ──────────────────────────────────────────────────────

    private void refresh() {
        String query = (editSearch != null && editSearch.getText() != null)
                ? editSearch.getText().toString().toLowerCase().trim() : "";

        java.util.List<JSONObject> dirs  = new java.util.ArrayList<>();
        java.util.List<JSONObject> files = new java.util.ArrayList<>();
        JSONObject backEntry = null;

        try {
            for (int i = 0; i < allFiles.length(); i++) {
                JSONObject f = allFiles.getJSONObject(i);
                if (f.optBoolean("isBack")) { backEntry = f; continue; }

                String name  = f.optString("name").toLowerCase();
                boolean isDir = f.optBoolean("isDir");

                // Search
                if (!query.isEmpty() && !name.contains(query)) continue;

                // Category
                if (!passesFilter(name, isDir)) continue;

                if (isDir) dirs.add(f); else files.add(f);
            }
        } catch (Exception e) { e.printStackTrace(); }

        java.util.Comparator<JSONObject> byName = (a, b) -> {
            String na = a.optString("name");
            String nb = b.optString("name");
            return sortAZ ? na.compareToIgnoreCase(nb) : nb.compareToIgnoreCase(na);
        };
        dirs.sort(byName);
        files.sort(byName);

        JSONArray result = new JSONArray();
        try {
            if (backEntry != null) result.put(backEntry);
            for (JSONObject d : dirs)  result.put(d);
            for (JSONObject f : files) result.put(f);
        } catch (Exception e) { e.printStackTrace(); }

        adapter.setFiles(result);

        int count = result.length() - (backEntry != null ? 1 : 0);
        safeText(textCount, count + " item" + (count == 1 ? "" : "s"));

        View emptyState = findViewById(R.id.layoutEmptyState);
        if (emptyState != null) {
            emptyState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private boolean passesFilter(String name, boolean isDir) {
        switch (activeChip) {
            case "images": return isDir || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".gif")
                    || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".heic");
            case "videos": return isDir || name.endsWith(".mp4") || name.endsWith(".mkv")
                    || name.endsWith(".avi") || name.endsWith(".mov")
                    || name.endsWith(".3gp") || name.endsWith(".webm");
            case "docs":   return isDir || name.endsWith(".pdf") || name.endsWith(".doc")
                    || name.endsWith(".docx") || name.endsWith(".xls") || name.endsWith(".xlsx")
                    || name.endsWith(".ppt") || name.endsWith(".pptx") || name.endsWith(".txt")
                    || name.endsWith(".csv");
            case "audio":  return isDir || name.endsWith(".mp3") || name.endsWith(".aac")
                    || name.endsWith(".flac") || name.endsWith(".wav")
                    || name.endsWith(".ogg") || name.endsWith(".m4a");
            default: return true;
        }
    }

    // ─── Chips ──────────────────────────────────────────────────────────────

    private void setupChips() {
        int[][] chips = {
            {R.id.chipAll,    0},
            {R.id.chipImages, 0},
            {R.id.chipVideos, 0},
            {R.id.chipDocs,   0},
            {R.id.chipAudio,  0}
        };
        String[] keys = {"all", "images", "videos", "docs", "audio"};
        int[] chipIds = {R.id.chipAll, R.id.chipImages, R.id.chipVideos, R.id.chipDocs, R.id.chipAudio};

        for (int i = 0; i < chipIds.length; i++) {
            TextView chip = findViewById(chipIds[i]);
            if (chip == null) continue;
            final String key = keys[i];
            chip.setOnClickListener(v -> {
                activeChip = key;
                for (int id : chipIds) {
                    TextView c = findViewById(id);
                    if (c == null) continue;
                    boolean active = c == v;
                    c.setBackgroundColor(active ? ContextCompat.getColor(this, R.color.primary_container) : ContextCompat.getColor(this, R.color.bg_elevated));
                    c.setTextColor(active ? ContextCompat.getColor(this, R.color.on_primary_container) : ContextCompat.getColor(this, R.color.text_secondary));
                }
                refresh();
            });
        }
        
        TextView chipTrash = findViewById(R.id.chipTrash);
        if (chipTrash != null) {
            chipTrash.setOnClickListener(v -> {
                loadDirectory("/storage/emulated/0/.storeme_trash");
                activeChip = "all";
                refresh();
            });
        }

        
        View btnSearch = findViewById(R.id.btnSearch);
        View searchBar = findViewById(R.id.searchBar);
        android.widget.EditText editSearch = findViewById(R.id.editSearch);
        
        if (btnSearch != null && searchBar != null && editSearch != null) {
            btnSearch.setOnClickListener(v -> {
                if (searchBar.getVisibility() == View.VISIBLE) {
                    searchBar.setVisibility(View.GONE);
                    editSearch.setText("");
                    loadDirectory(currentPath); // Reset to current dir
                } else {
                    searchBar.setVisibility(View.VISIBLE);
                    editSearch.requestFocus();
                }
            });
            
            editSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    String query = s.toString().trim();
                    if (query.length() >= 2) {
                        try {
                            JSONObject req = new JSONObject();
                            req.put("action", "search");
                            req.put("query", query);
                            ClientConnectionManager.getInstance().sendData(req.toString());
                            safeText(textStatus, "Searching...");
                        } catch (Exception e) {}
                    } else if (query.isEmpty()) {
                        loadDirectory(currentPath);
                    }
                }
            });
        }
    }

    // ─── File Actions ────────────────────────────────────────────────────────

    private void openFile(JSONObject file) {
        try {
            String path = file.optString("path");
            long size = file.optLong("size", 0);

            // Build stream URL pointing at our local NanoHTTPD server (port 8080)
            // The server relays data from the Host over the existing WebRTC connection
            String encodedPath = android.net.Uri.encode(path);
            String url = "http://127.0.0.1:8080/stream?path=" + encodedPath + "&size=" + size;

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            String mimeType = "*/*";
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = path.substring(dotIndex + 1).toLowerCase();
                String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (m != null) mimeType = m;
            }
            intent.setDataAndType(android.net.Uri.parse(url), mimeType);
            
            try {
                startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ex) {
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show();
        }
    }

    private void showFileMenu(View anchor, JSONObject file) {
        if (file.optBoolean("isBack")) return;
        
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_file_menu, null);
        dialog.setContentView(view);
        
        TextView textTitle = view.findViewById(R.id.textMenuTitle);
        textTitle.setText(file.optString("name"));
        
        boolean inTrash = currentPath.contains(".storeme_trash");
        boolean isDir = file.optBoolean("isDir");
        
        View btnDownload = view.findViewById(R.id.btnMenuDownload);
        View btnShare = view.findViewById(R.id.btnMenuShare);
        View btnZip = view.findViewById(R.id.btnMenuZip);
        TextView textZip = view.findViewById(R.id.textMenuZip);
        View btnDetails = view.findViewById(R.id.btnMenuDetails);
        View btnRestore = view.findViewById(R.id.btnMenuRestore);
        View btnTrash = view.findViewById(R.id.btnMenuTrash);
        TextView textTrash = view.findViewById(R.id.textMenuTrash);
        
        if (inTrash) {
            textTrash.setText("Delete Permanently");
            btnZip.setVisibility(View.GONE);
            btnShare.setVisibility(View.GONE);
            btnDownload.setVisibility(View.GONE);
            btnRestore.setVisibility(View.VISIBLE);
        } else {
            if (isDir) {
                textZip.setText("Compress to Zip");
                btnDownload.setVisibility(View.GONE);
            } else {
                if (file.optString("name", "").toLowerCase().endsWith(".zip")) {
                    textZip.setText("Extract Zip");
                } else {
                    btnZip.setVisibility(View.GONE);
                }
            }
        }

        // RBAC Enforcement: suppress destructive/modifying actions for Viewer
        if ("viewer".equalsIgnoreCase(currentRole)) {
            btnTrash.setVisibility(View.GONE);
            btnRestore.setVisibility(View.GONE);
            btnZip.setVisibility(View.GONE);
        }

        btnDownload.setOnClickListener(v -> {
            dialog.dismiss();
            String fileName = file.optString("name");
            String mimeType = "*/*";
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substring(dotIndex + 1).toLowerCase());
            if (mimeType == null) mimeType = "*/*";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            getSharedPreferences("StoreMePrefs", MODE_PRIVATE).edit().putString("pending_download_path", file.optString("path")).apply();
            startActivityForResult(intent, 1001);
        });

        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", "create_link");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, "Generating secure link...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
        });
        
        btnZip.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", isDir ? "zip" : "unzip");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, isDir ? "Compression started on host" : "Extraction started on host", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
        });

        btnRestore.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", "restore");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, "Restoring file...", Toast.LENGTH_SHORT).show();
                new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1000);
            } catch (Exception e) {}
        });

        btnTrash.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", inTrash ? "delete_permanent" : "delete");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, inTrash ? "Deleted Permanently" : "Moved to Trash", Toast.LENGTH_SHORT).show();
                new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1000);
            } catch (Exception e) {}
        });

        btnDetails.setOnClickListener(v -> {
            dialog.dismiss();
            showFileDetails(file);
        });
        
        dialog.show();
    }
    
    private void showFileDetails(JSONObject file) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_file_details, null);
        dialog.setContentView(view);
        
        TextView txtName = view.findViewById(R.id.detailName);
        TextView txtType = view.findViewById(R.id.detailType);
        TextView txtSize = view.findViewById(R.id.detailSize);
        TextView txtPath = view.findViewById(R.id.detailPath);
        
        txtName.setText(file.optString("name"));
        txtPath.setText(file.optString("path"));
        if (file.optBoolean("isDir")) {
            txtType.setText("Folder");
            txtSize.setText("--");
        } else {
            String name = file.optString("name");
            int dotIndex = name.lastIndexOf('.');
            txtType.setText((dotIndex > 0) ? name.substring(dotIndex + 1).toUpperCase() + " File" : "Unknown File");
            
            long sizeBytes = file.optLong("size");
            if (sizeBytes > 1024 * 1024 * 1024) txtSize.setText(String.format("%.2f GB", sizeBytes / (1024f * 1024f * 1024f)));
            else if (sizeBytes > 1024 * 1024) txtSize.setText(String.format("%.2f MB", sizeBytes / (1024f * 1024f)));
            else if (sizeBytes > 1024) txtSize.setText(String.format("%.2f KB", sizeBytes / 1024f));
            else txtSize.setText(sizeBytes + " Bytes");
        }
        
        view.findViewById(R.id.btnCloseDetails).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void previewFile(java.io.File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getApplicationContext().getPackageName() + ".fileprovider", file);
            
            String mimeType = "*/*";
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
            if (ext != null && !ext.isEmpty()) {
                String possibleMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                if (possibleMime != null) mimeType = possibleMime;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(this, "No app to preview this file", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private void wireNav(String hostId) {
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_files);
            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_activity) {
                    go(ActivityLogActivity.class, hostId);
                    return true;
                } else if (id == R.id.nav_settings) {
                    go(SettingsActivity.class, hostId);
                    return true;
                } else if (id == R.id.nav_info) {
                    go(ClientDashboardActivity.class, hostId);
                    return true;
                }
                return true;
            });
        }
    }

    private void go(Class<?> cls, String hostId) {
        Intent intent = new Intent(this, cls);
        intent.putExtra("HOST_ID", hostId);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (!currentPath.equals("/storage/emulated/0/") && !currentPath.equals("/")) {
            goUp();
        } else {
            Toast.makeText(this, "Stop using server in Settings to exit.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(data.getData(), takeFlags);
            } catch (Exception e) {}
            String path = getSharedPreferences("StoreMePrefs", MODE_PRIVATE)
                    .getString("pending_download_path", null);
            if (path != null) {
                ClientConnectionManager.getInstance().startDownload(this, path, data.getData());
                Toast.makeText(this, "Download started!", Toast.LENGTH_SHORT).show();
                go(ActivityLogActivity.class, getIntent().getStringExtra("HOST_ID"));
            }
        } else if (requestCode == 2001 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            try {
                final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Exception e) {}
            String fileName = "upload_" + System.currentTimeMillis();
            try {
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    fileName = cursor.getString(nameIndex);
                    cursor.close();
                }
            } catch (Exception e) {}
            ClientConnectionManager.getInstance().startUpload(this, uri, fileName, currentPath);
            Toast.makeText(this, "Upload started!", Toast.LENGTH_SHORT).show();
            go(ActivityLogActivity.class, getIntent().getStringExtra("HOST_ID"));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void safeText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }
}
