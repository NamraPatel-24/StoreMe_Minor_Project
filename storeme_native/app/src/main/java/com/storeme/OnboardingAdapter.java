package com.storeme;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.List;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.PageHolder> {

    private static final int TYPE_WELCOME  = 0;
    private static final int TYPE_STEPS    = 1;
    private static final int TYPE_FEATURES = 2;

    // ── Page definitions ──────────────────────────────────────
    static class StepPage {
        final String pageIndex, title, subtitle;
        final String[] stepNumbers, stepTitles, stepDescs;

        StepPage(String pageIndex, String title, String subtitle,
                 String[] stepNumbers, String[] stepTitles, String[] stepDescs) {
            this.pageIndex = pageIndex; this.title = title; this.subtitle = subtitle;
            this.stepNumbers = stepNumbers; this.stepTitles = stepTitles; this.stepDescs = stepDescs;
        }
    }

    private static final List<StepPage> STEP_PAGES = Arrays.asList(
        // Page 2 — Initialize Host Node
        new StepPage("01", "Initialize Storage Node",
            "Establish a private peer-to-peer storage host on your enterprise device",
            new String[]{"1", "2", "3", "4"},
            new String[]{"Launch Node", "Configure Server", "Issue Host Identifier", "Daemon Runs in Background"},
            new String[]{
                "Open StoreMe on the primary host hardware",
                "Set unique Server ID and root storage directory",
                "Share the authenticated Node ID with organization team members",
                "The storage service daemon runs continuously with foreground status"
            }),

        // Page 3 — Connect Client Node
        new StepPage("02", "Client Authentication",
            "Connect securely to an authorized storage node via WebRTC data channels",
            new String[]{"1", "2", "3", "4"},
            new String[]{"Enter Node ID", "Provide Credentials", "Enforce Permissions", "Real-Time File Access"},
            new String[]{
                "Specify the destination Host ID in the connection panel",
                "Submit designated username and authentication token",
                "Access permissions are mapped directly to your assigned role tier",
                "Stream, download, and browse files with zero intermediate servers"
            }),

        // Page 4 — Web Console Access
        new StepPage("03", "Web Console Access",
            "Access files from any modern browser without installing additional clients",
            new String[]{"1", "2", "3", "4"},
            new String[]{"Access Web Console", "Zero-Install WebRTC", "Session Verification", "Collaborate Seamlessly"},
            new String[]{
                "Open the enterprise web dashboard URL in Chrome, Firefox, or Safari",
                "Direct browser-to-node WebRTC connection establishes automatically",
                "Verify session tokens and organization access control",
                "Upload and manage assets with responsive desktop file tooling"
            })
    );

    // ── Features data ─────────────────────────────────────────
    private static final String[][] FEATURES = {
        {"RBAC", "Role-Based Access",   "Admin, Uploader, and Viewer access tiers"},
        {"P2P",  "Direct DataChannel",  "Sub-millisecond latency peer-to-peer piping"},
        {"AUTH", "Session Tokens",      "Cryptographically verified access credentials"},
        {"VOL",  "Multi-Storage Mount", "Access internal and external attached storage"},
        {"LOCK", "Biometric Lock",      "Hardware-backed device authentication lock"},
        {"TRF",  "Resume Transfers",    "Chunk-based streaming with automatic pause/resume"},
        {"AUD",  "Activity Audit Log",  "Full telemetry tracking of file transfer tasks"},
        {"PRIV", "Zero-Knowledge",      "Private peer encryption without third-party clouds"}
    };

    private final Context context;

    public OnboardingAdapter(Context context) {
        this.context = context;
    }

    @Override public int getItemCount() {
        return 1 + STEP_PAGES.size() + 1;
    }

    @Override public int getItemViewType(int position) {
        if (position == 0) return TYPE_WELCOME;
        if (position == getItemCount() - 1) return TYPE_FEATURES;
        return TYPE_STEPS;
    }

    @NonNull @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(context);
        View v;
        if (viewType == TYPE_WELCOME)       v = inf.inflate(R.layout.onboard_page_welcome, parent, false);
        else if (viewType == TYPE_FEATURES) v = inf.inflate(R.layout.onboard_page_features, parent, false);
        else                                v = inf.inflate(R.layout.onboard_page_steps, parent, false);
        return new PageHolder(v, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_WELCOME) return;

        if (type == TYPE_FEATURES) {
            buildFeaturesGrid(holder.featuresGrid);
            return;
        }

        // Steps page
        StepPage page = STEP_PAGES.get(position - 1);
        holder.pageEmoji.setText(page.pageIndex);
        holder.pageTitle.setText(page.title);
        holder.pageSubtitle.setText(page.subtitle);
        buildSteps(holder.stepsContainer, page);
    }

    private void buildSteps(LinearLayout container, StepPage page) {
        container.removeAllViews();
        for (int i = 0; i < page.stepTitles.length; i++) {
            View row = buildStepRow(page.stepNumbers[i], page.stepTitles[i], page.stepDescs[i]);
            container.addView(row);
        }
    }

    private View buildStepRow(String number, String title, String desc) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dp(18));
        row.setLayoutParams(rowParams);

        // Numeric step pill
        TextView numView = new TextView(context);
        numView.setText(number);
        numView.setTextSize(14);
        numView.setTextColor(ContextCompat.getColor(context, R.color.primary));
        numView.setTypeface(Typeface.DEFAULT_BOLD);
        numView.setGravity(Gravity.CENTER);

        GradientDrawable numBg = new GradientDrawable();
        numBg.setColor(ContextCompat.getColor(context, R.color.primary_dim));
        numBg.setCornerRadius(dp(8));
        numBg.setStroke(dp(1), ContextCompat.getColor(context, R.color.border_subtle));
        numView.setBackground(numBg);

        LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        numParams.setMarginEnd(dp(14));
        numView.setLayoutParams(numParams);
        row.addView(numView);

        // Text column
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textCol.setLayoutParams(colParams);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(titleView);

        TextView descView = new TextView(context);
        descView.setText(desc);
        descView.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        descView.setTextSize(13);
        descView.setLineSpacing(0, 1.25f);
        textCol.addView(descView);

        row.addView(textCol);
        return row;
    }

    private void buildFeaturesGrid(LinearLayout container) {
        container.removeAllViews();
        for (int i = 0; i < FEATURES.length; i += 2) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(10));
            rowLayout.setLayoutParams(rowParams);

            rowLayout.addView(buildFeatureCard(FEATURES[i]));
            if (i + 1 < FEATURES.length) {
                View spacer = new View(context);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
                rowLayout.addView(spacer);
                rowLayout.addView(buildFeatureCard(FEATURES[i + 1]));
            }
            container.addView(rowLayout);
        }
    }

    private View buildFeatureCard(String[] feature) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        card.setLayoutParams(cardParams);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(context, R.color.bg_surface));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), ContextCompat.getColor(context, R.color.border_subtle));
        card.setBackground(bg);

        TextView tagView = new TextView(context);
        tagView.setText(feature[0]);
        tagView.setTextSize(11);
        tagView.setTypeface(Typeface.DEFAULT_BOLD);
        tagView.setTextColor(ContextCompat.getColor(context, R.color.primary));
        tagView.setPadding(0, 0, 0, dp(6));
        card.addView(tagView);

        TextView titleView = new TextView(context);
        titleView.setText(feature[1]);
        titleView.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        titleView.setTextSize(13);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, dp(3));
        card.addView(titleView);

        TextView descView = new TextView(context);
        descView.setText(feature[2]);
        descView.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        descView.setTextSize(11);
        descView.setLineSpacing(0, 1.2f);
        card.addView(descView);

        return card;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        TextView pageEmoji, pageTitle, pageSubtitle;
        LinearLayout stepsContainer, featuresGrid;

        PageHolder(View itemView, int viewType) {
            super(itemView);
            if (viewType == OnboardingAdapter.TYPE_STEPS) {
                pageEmoji      = itemView.findViewById(R.id.pageEmoji);
                pageTitle      = itemView.findViewById(R.id.pageTitle);
                pageSubtitle   = itemView.findViewById(R.id.pageSubtitle);
                stepsContainer = itemView.findViewById(R.id.stepsContainer);
            } else if (viewType == OnboardingAdapter.TYPE_FEATURES) {
                featuresGrid = itemView.findViewById(R.id.featuresGrid);
            }
        }
    }
}
