package com.ridebuddy.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class SplashActivity extends Activity {
    private static final long SPLASH_DURATION_MS = 750;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openApp = () -> {
        Intent launch = new Intent(this, MainActivity.class);
        startActivity(launch);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        handler.postDelayed(openApp, SPLASH_DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openApp);
        super.onDestroy();
    }
}
