package com.storeme;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class CrashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        textView.setPadding(pad, pad, pad, pad);
        textView.setTextSize(12);
        textView.setTextColor(0xFFE53935);
        textView.setTextIsSelectable(true);

        String error = getIntent().getStringExtra("error");
        textView.setText("The app crashed!\nPlease send this error to the developer:\n\n" + error);

        scrollView.addView(textView);
        setContentView(scrollView);
    }

    public static void setupGlobalHandler(Context context) {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(throwable.toString()).append("\n");
                for (StackTraceElement element : throwable.getStackTrace()) {
                    sb.append("\tat ").append(element.toString()).append("\n");
                }
                Throwable cause = throwable.getCause();
                while (cause != null) {
                    sb.append("Caused by: ").append(cause.toString()).append("\n");
                    for (StackTraceElement element : cause.getStackTrace()) {
                        sb.append("\tat ").append(element.toString()).append("\n");
                    }
                    cause = cause.getCause();
                }

                Intent intent = new Intent(context, CrashActivity.class);
                intent.putExtra("error", sb.toString());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);

                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            } catch (Exception e) {
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });
    }
}
