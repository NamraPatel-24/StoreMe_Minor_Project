package com.storeme;

import android.app.Application;

import com.google.firebase.auth.FirebaseAuth;
import android.util.Log;

public class StoreMeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashActivity.setupGlobalHandler(this);
        
        FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.d("StoreMeNative", "signInAnonymously:success");
            } else {
                Log.w("StoreMeNative", "signInAnonymously:failure", task.getException());
            }
        });
    }
}
