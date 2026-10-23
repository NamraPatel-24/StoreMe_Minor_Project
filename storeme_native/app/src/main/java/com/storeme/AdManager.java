package com.storeme;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * AdManager — Centralizes all Google AdMob logic.
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │  HOW TO USE                                             │
 * │                                                         │
 * │  1. Banner Ad (bottom of any screen):                   │
 * │     AdManager.showBannerAd(this, R.id.adContainer);    │
 * │                                                         │
 * │  2. Interstitial Ad (full-screen, e.g. after download): │
 * │     AdManager.loadInterstitial(this);                   │
 * │     AdManager.showInterstitialIfReady(this);            │
 * │                                                         │
 * │  REPLACE TEST AD UNIT IDs with your real IDs from:     │
 * │  https://admob.google.com                               │
 * └─────────────────────────────────────────────────────────┘
 */
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.AdLoader;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.LayoutInflater;

public class AdManager {

    private static final String TAG = "AdManager";

    // ── Ad Unit IDs ───────────────────────────────────────────
    // Real IDs from AdMob dashboard
    private static final String BANNER_AD_UNIT_ID       = "ca-app-pub-6377540141936496/7787380236";
    private static final String INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-6377540141936496/5514133789";
    private static final String NATIVE_AD_UNIT_ID       = "ca-app-pub-6377540141936496/7021093470";

    private static InterstitialAd interstitialAd = null;
    private static boolean sdkInitialized = false;

    // ── Initialize AdMob SDK (call once in Application or first Activity) ──
    public static void initialize(Context context) {
        if (sdkInitialized) return;
        sdkInitialized = true;
        MobileAds.initialize(context, initializationStatus -> {
            Log.d(TAG, "AdMob SDK initialized");
        });
    }

    // ── Show Native Advanced Ad ───────────────────────────────
    public static void showNativeAd(Activity activity, int containerId) {
        try {
            ViewGroup container = activity.findViewById(containerId);
            if (container == null) return;

            AdLoader adLoader = new AdLoader.Builder(activity, NATIVE_AD_UNIT_ID)
                .forNativeAd(nativeAd -> {
                    // Ad Loaded successfully — Inflate layout and bind data
                    NativeAdView adView = (NativeAdView) LayoutInflater.from(activity)
                            .inflate(R.layout.layout_native_ad, null);
                    
                    populateNativeAdView(nativeAd, adView);

                    container.removeAllViews();
                    container.addView(adView);
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError adError) {
                        Log.w(TAG, "Native ad failed to load: " + adError.getMessage());
                        container.removeAllViews();
                        container.setVisibility(android.view.View.GONE);
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder().build())
                .build();

            adLoader.loadAd(new AdRequest.Builder().build());
        } catch (Exception e) {
            Log.e(TAG, "showNativeAd error: " + e.getMessage());
        }
    }

    private static void populateNativeAdView(NativeAd nativeAd, NativeAdView adView) {
        // Set the views
        adView.setMediaView((MediaView) adView.findViewById(R.id.ad_media));
        adView.setHeadlineView(adView.findViewById(R.id.ad_headline));
        adView.setBodyView(adView.findViewById(R.id.ad_body));
        adView.setCallToActionView(adView.findViewById(R.id.ad_call_to_action));
        adView.setIconView(adView.findViewById(R.id.ad_app_icon));
        adView.setAdvertiserView(adView.findViewById(R.id.ad_advertiser));

        // Assign values
        ((TextView) adView.getHeadlineView()).setText(nativeAd.getHeadline());
        adView.getMediaView().setMediaContent(nativeAd.getMediaContent());

        if (nativeAd.getBody() == null) {
            adView.getBodyView().setVisibility(android.view.View.INVISIBLE);
        } else {
            adView.getBodyView().setVisibility(android.view.View.VISIBLE);
            ((TextView) adView.getBodyView()).setText(nativeAd.getBody());
        }

        if (nativeAd.getCallToAction() == null) {
            adView.getCallToActionView().setVisibility(android.view.View.INVISIBLE);
        } else {
            adView.getCallToActionView().setVisibility(android.view.View.VISIBLE);
            ((Button) adView.getCallToActionView()).setText(nativeAd.getCallToAction());
        }

        if (nativeAd.getIcon() == null) {
            adView.getIconView().setVisibility(android.view.View.GONE);
        } else {
            ((ImageView) adView.getIconView()).setImageDrawable(
                    nativeAd.getIcon().getDrawable());
            adView.getIconView().setVisibility(android.view.View.VISIBLE);
        }

        if (nativeAd.getAdvertiser() == null) {
            adView.getAdvertiserView().setVisibility(android.view.View.INVISIBLE);
        } else {
            ((TextView) adView.getAdvertiserView()).setText(nativeAd.getAdvertiser());
            adView.getAdvertiserView().setVisibility(android.view.View.VISIBLE);
        }

        // Register the NativeAd object
        adView.setNativeAd(nativeAd);
    }

    // ── Show a Banner Ad inside a container ViewGroup ─────────
    public static void showBannerAd(Activity activity, int containerId) {
        try {
            ViewGroup container = activity.findViewById(containerId);
            if (container == null) return;

            AdView adView = new AdView(activity);
            adView.setAdSize(AdSize.BANNER);
            adView.setAdUnitId(BANNER_AD_UNIT_ID);

            container.removeAllViews();
            container.addView(adView);

            AdRequest adRequest = new AdRequest.Builder().build();
            adView.loadAd(adRequest);

            adView.setAdListener(new AdListener() {
                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    Log.w(TAG, "Banner ad failed: " + error.getMessage());
                    container.removeAllViews(); // hide empty space if ad fails
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "showBannerAd error: " + e.getMessage());
        }
    }

    // ── Pre-load an Interstitial Ad ───────────────────────────
    public static void loadInterstitial(Context context) {
        AdRequest adRequest = new AdRequest.Builder().build();
        InterstitialAd.load(context, INTERSTITIAL_AD_UNIT_ID, adRequest,
            new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(InterstitialAd ad) {
                    interstitialAd = ad;
                    Log.d(TAG, "Interstitial ad loaded");
                }
                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    interstitialAd = null;
                    Log.w(TAG, "Interstitial ad failed to load: " + error.getMessage());
                }
            });
    }

    // ── Show Interstitial if ready, then reload for next time ─
    public static void showInterstitialIfReady(Activity activity, Runnable onDismissed) {
        if (interstitialAd == null) {
            loadInterstitial(activity); // preload for next time
            if (onDismissed != null) onDismissed.run();
            return;
        }
        interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                interstitialAd = null;
                loadInterstitial(activity); // preload next one
                if (onDismissed != null) onDismissed.run();
            }
            
            @Override
            public void onAdFailedToShowFullScreenContent(com.google.android.gms.ads.AdError adError) {
                interstitialAd = null;
                if (onDismissed != null) onDismissed.run();
            }
        });
        interstitialAd.show(activity);
    }

    // ── Check if interstitial is ready ────────────────────────
    public static boolean isInterstitialReady() {
        return interstitialAd != null;
    }
}
