package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

public class ManageUsersActivity extends AppCompatActivity {

    private LinearLayout containerRequests;
    private LinearLayout containerUsers;
    private SharedPreferences prefs;
    private DatabaseReference requestsRef;
    
    private static final int REQUEST_CODE_PICK_FOLDER = 101;
    
    // State tracking for folder picker returns
    private String pendingRequestId = null;
    private String pendingRequestUsername = null;
    private String pendingEditUsername = null;
    private String pendingRole = "uploader";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_users);

        prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String hostId = prefs.getString("host_id", "");

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        
        containerRequests = findViewById(R.id.containerRequests);
        containerUsers = findViewById(R.id.containerUsers);

        requestsRef = FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("requests");
        
        // Listen to requests
        requestsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                renderRequests(snapshot);
            }
            @Override
            public void onCancelled(DatabaseError error) {}
        });

        renderUsers();
    }

    private void renderRequests(DataSnapshot snapshot) {
        containerRequests.removeAllViews();
        boolean hasPending = false;
        
        for (DataSnapshot reqSnap : snapshot.getChildren()) {
            String status = reqSnap.child("status").getValue(String.class);
            if (!"pending".equals(status)) continue;
            
            hasPending = true;
            String reqId = reqSnap.getKey();
            String username = reqSnap.child("username").getValue(String.class);
            if (username == null || username.trim().isEmpty()) username = "ClientNode";

            View itemView = LayoutInflater.from(this).inflate(R.layout.item_manage_user_card, containerRequests, false);
            
            TextView textAvatar = itemView.findViewById(R.id.textAvatar);
            TextView text1 = itemView.findViewById(android.R.id.text1);
            TextView text2 = itemView.findViewById(android.R.id.text2);
            TextView badgeRole = itemView.findViewById(R.id.badgeRole);
            ImageButton btnUserAction = itemView.findViewById(R.id.btnUserAction);

            if (textAvatar != null) {
                String initials = username.length() >= 2 ? username.substring(0, 2).toUpperCase(Locale.US) : username.toUpperCase(Locale.US);
                textAvatar.setText(initials);
            }
            if (text1 != null) {
                text1.setText(username);
            }
            if (badgeRole != null) {
                badgeRole.setText("REQUEST");
                badgeRole.setBackgroundResource(R.drawable.bg_badge_uploader);
                badgeRole.setTextColor(ContextCompat.getColor(this, R.color.badge_uploader_fg));
            }
            if (text2 != null) {
                text2.setText("Access requested — Tap to review and grant role");
                text2.setVisibility(View.VISIBLE);
            }

            final String finalUser = username;
            View.OnClickListener clickListener = v -> showRoleBottomSheet(reqId, finalUser, "uploader", "/", true);
            itemView.setOnClickListener(clickListener);
            if (btnUserAction != null) {
                btnUserAction.setOnClickListener(clickListener);
            }
            
            containerRequests.addView(itemView);
        }
        
        if (!hasPending) {
            TextView tv = new TextView(this);
            tv.setText(R.string.empty_requests_desc);
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            tv.setTextSize(13);
            tv.setPadding(8, 8, 8, 16);
            containerRequests.addView(tv);
        }
    }

    private void renderUsers() {
        containerUsers.removeAllViews();
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            int count = 0;
            for (int i = 0; i < usersArr.length(); i++) {
                JSONObject u = usersArr.getJSONObject(i);
                final String username = u.optString("username", "Node");
                final String role = u.optString("role", "admin".equalsIgnoreCase(username) ? "admin" : "uploader").toLowerCase(Locale.US);
                
                JSONArray perms = u.optJSONArray("permissions");
                StringBuilder permStr = new StringBuilder();
                if (perms != null) {
                    for (int j = 0; j < perms.length(); j++) {
                        if (j > 0) permStr.append(", ");
                        permStr.append(perms.getString(j));
                    }
                }
                String scopeDisplay = permStr.toString();
                if (scopeDisplay.isEmpty() || scopeDisplay.equals("/")) {
                    scopeDisplay = "Full Storage (/)";
                }

                View itemView = LayoutInflater.from(this).inflate(R.layout.item_manage_user_card, containerUsers, false);
                
                TextView textAvatar = itemView.findViewById(R.id.textAvatar);
                TextView text1 = itemView.findViewById(android.R.id.text1);
                TextView text2 = itemView.findViewById(android.R.id.text2);
                TextView badgeRole = itemView.findViewById(R.id.badgeRole);
                ImageButton btnUserAction = itemView.findViewById(R.id.btnUserAction);

                if (textAvatar != null) {
                    String initials = username.length() >= 2 ? username.substring(0, 2).toUpperCase(Locale.US) : username.toUpperCase(Locale.US);
                    textAvatar.setText(initials);
                }
                if (text1 != null) {
                    text1.setText(username);
                }
                if (text2 != null) {
                    text2.setText("Scope: " + scopeDisplay);
                    text2.setVisibility(View.VISIBLE);
                }

                if (badgeRole != null) {
                    if ("admin".equals(role)) {
                        badgeRole.setText(R.string.role_admin);
                        badgeRole.setBackgroundResource(R.drawable.bg_badge_admin);
                        badgeRole.setTextColor(ContextCompat.getColor(this, R.color.badge_admin_fg));
                    } else if ("viewer".equals(role)) {
                        badgeRole.setText(R.string.role_viewer);
                        badgeRole.setBackgroundResource(R.drawable.bg_badge_viewer);
                        badgeRole.setTextColor(ContextCompat.getColor(this, R.color.badge_viewer_fg));
                    } else {
                        badgeRole.setText(R.string.role_uploader);
                        badgeRole.setBackgroundResource(R.drawable.bg_badge_uploader);
                        badgeRole.setTextColor(ContextCompat.getColor(this, R.color.badge_uploader_fg));
                    }
                }

                if (!"admin".equalsIgnoreCase(username)) {
                    final String effectivePath = permStr.toString();
                    View.OnClickListener listener = v -> showRoleBottomSheet(null, username, role, effectivePath, false);
                    itemView.setOnClickListener(listener);
                    if (btnUserAction != null) {
                        btnUserAction.setOnClickListener(listener);
                    }
                } else if (btnUserAction != null) {
                    btnUserAction.setVisibility(View.GONE);
                }

                containerUsers.addView(itemView);
                count++;
            }
            
            if (count == 0 || (count == 1 && usersArr.getJSONObject(0).optString("username").equalsIgnoreCase("admin"))) {
                TextView tv = new TextView(this);
                tv.setText(R.string.empty_users_desc);
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tv.setTextSize(13);
                tv.setPadding(8, 8, 8, 16);
                containerUsers.addView(tv);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showRoleBottomSheet(String reqId, String username, String initialRole, String initialPath, boolean isPendingRequest) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_user_role, null);
        dialog.setContentView(view);

        TextView textTitle = view.findViewById(R.id.textRoleDialogTitle);
        TextView textSub = view.findViewById(R.id.textRoleDialogSubtitle);
        RadioGroup radioGroupRoles = view.findViewById(R.id.radioGroupRoles);
        RadioButton radioAdmin = view.findViewById(R.id.radioRoleAdmin);
        RadioButton radioUploader = view.findViewById(R.id.radioRoleUploader);
        RadioButton radioViewer = view.findViewById(R.id.radioRoleViewer);
        RadioGroup radioGroupScope = view.findViewById(R.id.radioGroupScope);
        RadioButton radioScopeEntire = view.findViewById(R.id.radioScopeEntire);
        RadioButton radioScopeSpecific = view.findViewById(R.id.radioScopeSpecific);
        MaterialButton btnSave = view.findViewById(R.id.btnSaveRole);
        MaterialButton btnRemove = view.findViewById(R.id.btnRemoveUser);

        if (textTitle != null) {
            textTitle.setText(isPendingRequest ? "Review Access Request" : "Manage Collaborator Access");
        }
        if (textSub != null) {
            textSub.setText("Configure enterprise permissions for @" + username);
        }

        // Set initial role
        if ("admin".equalsIgnoreCase(initialRole) && radioAdmin != null) {
            radioAdmin.setChecked(true);
        } else if ("viewer".equalsIgnoreCase(initialRole) && radioViewer != null) {
            radioViewer.setChecked(true);
        } else if (radioUploader != null) {
            radioUploader.setChecked(true);
        }

        // Set initial scope
        if (initialPath != null && !initialPath.equals("/") && !initialPath.isEmpty() && radioScopeSpecific != null) {
            radioScopeSpecific.setChecked(true);
        } else if (radioScopeEntire != null) {
            radioScopeEntire.setChecked(true);
        }

        if (isPendingRequest) {
            btnSave.setText("Approve & Grant Role");
            btnRemove.setText("Reject Request");
        } else {
            btnSave.setText("Save Permissions");
            btnRemove.setText("Revoke Access");
        }

        btnSave.setOnClickListener(v -> {
            String selectedRole = "uploader";
            int selectedRoleId = radioGroupRoles.getCheckedRadioButtonId();
            if (selectedRoleId == R.id.radioRoleAdmin) {
                selectedRole = "admin";
            } else if (selectedRoleId == R.id.radioRoleViewer) {
                selectedRole = "viewer";
            }

            boolean isSpecific = radioGroupScope != null && radioGroupScope.getCheckedRadioButtonId() == R.id.radioScopeSpecific;

            if (isSpecific) {
                pendingRole = selectedRole;
                if (isPendingRequest) {
                    pendingRequestId = reqId;
                    pendingRequestUsername = username;
                    pendingEditUsername = null;
                } else {
                    pendingEditUsername = username;
                    pendingRequestId = null;
                    pendingRequestUsername = null;
                }
                dialog.dismiss();
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
            } else {
                if (isPendingRequest) {
                    approveRequest(reqId, username, "/", selectedRole);
                } else {
                    updateUserRoleAndPermission(username, selectedRole, "/");
                }
                dialog.dismiss();
            }
        });

        btnRemove.setOnClickListener(v -> {
            dialog.dismiss();
            if (isPendingRequest) {
                if (reqId != null) {
                    requestsRef.child(reqId).child("status").setValue("rejected");
                }
                Toast.makeText(this, "Request rejected", Toast.LENGTH_SHORT).show();
            } else {
                removeUser(username);
            }
        });

        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                String path = getFullPathFromTreeUri(treeUri);
                if (path == null) {
                    Toast.makeText(this, "Failed to resolve folder path. Ensure it's on internal storage.", Toast.LENGTH_LONG).show();
                    return;
                }
                
                if (pendingRequestId != null) {
                    approveRequest(pendingRequestId, pendingRequestUsername, path, pendingRole);
                    pendingRequestId = null;
                    pendingRequestUsername = null;
                } else if (pendingEditUsername != null) {
                    updateUserRoleAndPermission(pendingEditUsername, pendingRole, path);
                    pendingEditUsername = null;
                }
            }
        }
    }

    private void approveRequest(String reqId, String username, String path, String role) {
        String token = UUID.randomUUID().toString().substring(0, 8); // random password
        
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            
            // Remove existing user if same name
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < usersArr.length(); i++) {
                if (!usersArr.getJSONObject(i).optString("username").equals(username)) {
                    newArr.put(usersArr.getJSONObject(i));
                }
            }
            
            JSONObject newUser = new JSONObject();
            newUser.put("username", username);
            newUser.put("password", token);
            newUser.put("role", role);
            JSONArray permsArray = new JSONArray();
            permsArray.put(path);
            newUser.put("permissions", permsArray);
            newUser.put("created_at", System.currentTimeMillis());
            
            newArr.put(newUser);
            prefs.edit().putString("host_users", newArr.toString()).apply();
            
            // Notify client via Firebase
            requestsRef.child(reqId).child("token").setValue(token);
            requestsRef.child(reqId).child("role").setValue(role);
            requestsRef.child(reqId).child("status").setValue("accepted");
            
            Toast.makeText(this, "Granted role " + role.toUpperCase(Locale.US) + " to " + username, Toast.LENGTH_SHORT).show();
            renderUsers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateUserRoleAndPermission(String username, String role, String path) {
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            for (int i = 0; i < usersArr.length(); i++) {
                JSONObject u = usersArr.getJSONObject(i);
                if (u.optString("username").equals(username)) {
                    u.put("role", role);
                    JSONArray permsArray = new JSONArray();
                    permsArray.put(path);
                    u.put("permissions", permsArray);
                    break;
                }
            }
            prefs.edit().putString("host_users", usersArr.toString()).apply();
            Toast.makeText(this, "Updated role " + role.toUpperCase(Locale.US) + " for " + username, Toast.LENGTH_SHORT).show();
            renderUsers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeUser(String username) {
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < usersArr.length(); i++) {
                if (!usersArr.getJSONObject(i).optString("username").equals(username)) {
                    newArr.put(usersArr.getJSONObject(i));
                }
            }
            prefs.edit().putString("host_users", newArr.toString()).apply();
            Toast.makeText(this, "User " + username + " removed", Toast.LENGTH_SHORT).show();
            renderUsers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getFullPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) return null;
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        String[] split = docId.split(":");
        String type = split[0];
        String path = "";
        if (split.length > 1) {
            path = split[1];
        }

        if ("primary".equalsIgnoreCase(type)) {
            return Environment.getExternalStorageDirectory() + "/" + path;
        } else {
            return "/storage/" + type + "/" + path;
        }
    }
}
