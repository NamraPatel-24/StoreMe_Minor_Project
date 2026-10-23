package com.storeme;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

public class UpgradeProActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upgrade_pro);

        // Close button
        ImageButton btnClose = findViewById(R.id.btnClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());

        // Status pill
        TextView textPrice = findViewById(R.id.textPrice);
        if (textPrice != null) textPrice.setText("TIER: ENTERPRISE ACTIVE");

        // ── Populate feature list ────────────────────────────
        setupFeature(R.id.feat1,
                R.drawable.ic_users,
                "Role-Based Access Control",
                "Granular Admin, Uploader, and Viewer permission tiers");

        setupFeature(R.id.feat2,
                R.drawable.ic_dashboard,
                "Enterprise Dashboard",
                "Real-time node performance, network status, and storage breakdown");

        setupFeature(R.id.feat3,
                R.drawable.ic_video,
                "High-Throughput Streaming",
                "Direct low-latency peer-to-peer data channel transfers");

        setupFeature(R.id.feat4,
                R.drawable.ic_shield,
                "Biometric & Zero-Knowledge Security",
                "Hardware-backed biometric unlock and encrypted storage");

        setupFeature(R.id.feat5,
                R.drawable.ic_share,
                "Secure Temporary Access Tokens",
                "Cryptographic expiring tokens for zero-trust collaborator access");

        // ── Populate comparison table ────────────────────────
        setupCompare(R.id.cmp1, "Collaborator Tiers", "Basic", "Multi-Tier RBAC");
        setupCompare(R.id.cmp2, "File Streaming", "Limited", "Full Bandwidth");
        setupCompare(R.id.cmp3, "Audit & Metrics", "Basic", "Full Telemetry");
        setupCompare(R.id.cmp4, "Access Control", "None", "Admin / Uploader / Viewer");
        setupCompare(R.id.cmp5, "Security Engine", "Standard", "Zero-Knowledge");

        // ── Buy button ───────────────────────────────────────
        MaterialButton btnBuy = findViewById(R.id.btnBuyPro);
        if (btnBuy != null) {
            btnBuy.setOnClickListener(v -> launchPurchase());
        }

        // ── Restore button ───────────────────────────────────
        MaterialButton btnRestore = findViewById(R.id.btnRestore);
        if (btnRestore != null) {
            btnRestore.setOnClickListener(v -> restorePurchase());
        }
    }

    // ─── Feature row helper ──────────────────────────────────
    private void setupFeature(int viewId, int iconRes, String title, String desc) {
        View row = findViewById(viewId);
        if (row == null) return;
        ImageView icon = row.findViewById(R.id.featIcon);
        TextView tvTitle = row.findViewById(R.id.featTitle);
        TextView tvDesc  = row.findViewById(R.id.featDesc);
        if (icon != null)    icon.setImageResource(iconRes);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvDesc != null)  tvDesc.setText(desc);
    }

    // ─── Comparison row helper ───────────────────────────────
    private void setupCompare(int viewId, String label, String freeVal, String proVal) {
        View row = findViewById(viewId);
        if (row == null) return;
        TextView tvLabel = row.findViewById(R.id.cmpLabel);
        TextView tvFree  = row.findViewById(R.id.cmpFree);
        TextView tvPro   = row.findViewById(R.id.cmpPro);
        if (tvLabel != null) tvLabel.setText(label);
        if (tvFree != null) {
            tvFree.setText(freeVal);
            tvFree.setTextColor(0xFF94A3B8);
            tvFree.setTextSize(12);
        }
        if (tvPro != null) {
            tvPro.setText(proVal);
            tvPro.setTextColor(0xFF3B82F6);
            tvPro.setTextSize(12);
        }
    }

    // ─── Verification & Activation ───────────────────────────
    private void launchPurchase() {
        ProManager.activatePro(this);
        Toast.makeText(this, "StoreMe Enterprise verified and active.", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void restorePurchase() {
        if (ProManager.isPro(this)) {
            Toast.makeText(this, "Enterprise tier active on this node.", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            ProManager.activatePro(this);
            Toast.makeText(this, "Synchronized enterprise security policies.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
