package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private OnboardingAdapter adapter;
    private LinearLayout dotsContainer;
    private Button btnBack, btnNext;
    private int pageCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Force Update Check ─────────────────────────────────────────────
        // Runs before ANYTHING else. If outdated, shows a blocking dialog.
        ForceUpdateChecker.check(this, () -> {
            // Up to date — proceed normally
            initOnboarding();
        });
    }

    private void initOnboarding() {
        // If onboarding already completed, go straight to MainActivity
        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        if (prefs.getBoolean("onboarding_shown", false)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        viewPager      = findViewById(R.id.viewPager);
        dotsContainer  = findViewById(R.id.dotsContainer);
        btnBack        = findViewById(R.id.btnBack);
        btnNext        = findViewById(R.id.btnNext);

        adapter = new OnboardingAdapter(this);
        viewPager.setAdapter(adapter);
        pageCount = adapter.getItemCount();

        setupDots(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
                updateButtons(position);
            }
        });

        btnBack.setOnClickListener(v -> {
            int cur = viewPager.getCurrentItem();
            if (cur > 0) viewPager.setCurrentItem(cur - 1);
            else finishOnboarding(); // "Skip" on first page
        });

        btnNext.setOnClickListener(v -> {
            int cur = viewPager.getCurrentItem();
            if (cur < pageCount - 1) viewPager.setCurrentItem(cur + 1);
            else finishOnboarding();
        });
    }

    private void setupDots(int activePos) {
        dotsContainer.removeAllViews();
        for (int i = 0; i < pageCount; i++) {
            ImageView dot = new ImageView(this);
            dot.setImageResource(i == activePos ? R.drawable.dot_active : R.drawable.dot_inactive);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    i == activePos ? dp(24) : dp(8), dp(8));
            p.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(p);
            dot.setScaleType(ImageView.ScaleType.FIT_XY);
            dotsContainer.addView(dot);
        }
    }

    private void updateDots(int position) { setupDots(position); }

    private void updateButtons(int position) {
        btnBack.setText(position == 0 ? "Skip" : "← Back");
        btnNext.setText(position == pageCount - 1 ? "Get Started" : "Next →");
    }

    private void finishOnboarding() {
        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        prefs.edit().putBoolean("onboarding_shown", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
