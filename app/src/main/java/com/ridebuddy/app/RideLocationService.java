package com.ridebuddy.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class RideLocationService extends Service implements LocationListener {
    static final String EXTRA_MODE = "ride_mode";
    static final String EXTRA_CODE = "ride_code";
    static final String EXTRA_EXPIRES_AT = "ride_expires_at";
    static final String EXTRA_DESTINATION_TITLE = "destination_title";
    static final String EXTRA_DESTINATION_LAT = "destination_lat";
    static final String EXTRA_DESTINATION_LNG = "destination_lng";
    static final String EXTRA_RIDER_NAME = "rider_name";
    static final String EXTRA_RIDER_ID = "rider_id";
    static final String EXTRA_RIDER_PHONE = "rider_phone";
    private static final String ACTION_STOP = "com.ridebuddy.app.STOP_SHARING";
    static final String ACTION_SHOW_OVERLAY = "com.ridebuddy.app.SHOW_RIDE_OVERLAY";
    static final String ACTION_HIDE_OVERLAY = "com.ridebuddy.app.HIDE_RIDE_OVERLAY";
    private static final String CHANNEL_ID = "active_ride";
    private static final String ALERT_CHANNEL_ID = "ride_alerts";

    interface Listener {
        void onLocation(Location location);
        void onStopRequested(String reason);
    }

    private static RideLocationService instance;
    private static Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, RiderState> riders = new HashMap<>();
    private LocationManager locationManager;
    private String tripMode = "Ride";
    private String tripCode = "—";
    private String destinationTitle = "Destination";
    private double destinationLat;
    private double destinationLng;
    private long expiresAt;
    private String riderName = "Rider";
    private String localRiderId = "";
    private String riderPhone = "";
    private Location latestLocation;
    private String cloudRiderId;
    private DatabaseReference cloudLocation;
    private DatabaseReference cloudLocationsRoot;
    private ChildEventListener cloudRiderListener;
    private WindowManager windowManager;
    private View overlayView;
    private TextView overlayTitle;
    private TextView overlayDestination;
    private TextView overlayRiders;
    private TextView overlayEvents;
    private String overlayStopsSummary = "";

    private static final class RiderState {
        String id;
        String name;
        String phone;
        long updatedAt;
        double accuracy;
        double lat;
        double lng;
        double speed;
        boolean staleAlerted;
        String safetyAlertId = "";
        String safetyType = "";
    }

    private static final class RiderProgress {
        final String name;
        final double distanceToDestinationKm;
        RiderProgress(String name, double distanceToDestinationKm) {
            this.name = name;
            this.distanceToDestinationKm = distanceToDestinationKm;
        }
    }

    static void setListener(Listener value) { listener = value; }

    static boolean isOverlayVisible() {
        return instance != null && instance.overlayView != null;
    }

    static void updateOverlayStops(String json) {
        RideLocationService service = instance;
        if (service == null) return;
        service.handler.post(() -> service.acceptOverlayStops(json));
    }

    static void noteRider(String json) {
        RideLocationService service = instance;
        if (service == null) return;
        try {
            JSONObject value = new JSONObject(json);
            String riderId = value.optString("riderId");
            if (!riderId.isBlank()) service.handler.post(() -> service.updateRider(value));
        } catch (Exception ignored) { }
    }

    static void removeRider(String riderId) {
        RideLocationService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            RiderState removed = service.riders.remove(riderId);
            if (removed != null) service.sendRiderAlert(removed,
                    removed.name + " stopped sharing location");
            service.refreshNotification();
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Active ride", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(
                ALERT_CHANNEL_ID, "Ride safety alerts", NotificationManager.IMPORTANCE_HIGH));
        startForeground(71, buildNotification("Starting location sharing…"));

        locationManager = getSystemService(LocationManager.class);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 4, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 8, this);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop("Location sharing stopped from the notification.");
            return START_NOT_STICKY;
        }
        SharedPreferences state = getSharedPreferences("active_ride", MODE_PRIVATE);
        if (intent != null && intent.hasExtra(EXTRA_EXPIRES_AT)) {
            tripMode = intent.getStringExtra(EXTRA_MODE);
            tripCode = intent.getStringExtra(EXTRA_CODE);
            expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, 0);
            destinationTitle = intent.getStringExtra(EXTRA_DESTINATION_TITLE);
            destinationLat = intent.getDoubleExtra(EXTRA_DESTINATION_LAT, 0);
            destinationLng = intent.getDoubleExtra(EXTRA_DESTINATION_LNG, 0);
            riderName = intent.getStringExtra(EXTRA_RIDER_NAME);
            localRiderId = intent.getStringExtra(EXTRA_RIDER_ID);
            riderPhone = intent.getStringExtra(EXTRA_RIDER_PHONE);
            if (riderName == null || riderName.isBlank()) riderName = "Rider";
            if (riderPhone == null) riderPhone = "";
            state.edit().putString(EXTRA_MODE, tripMode).putString(EXTRA_CODE, tripCode)
                    .putBoolean("active", true)
                    .putLong(EXTRA_EXPIRES_AT, expiresAt)
                    .putString(EXTRA_DESTINATION_TITLE, destinationTitle)
                    .putString(EXTRA_RIDER_ID, localRiderId)
                    .putString(EXTRA_RIDER_NAME, riderName)
                    .putString(EXTRA_RIDER_PHONE, riderPhone)
                    .putLong("destination_lat_bits", Double.doubleToRawLongBits(destinationLat))
                    .putLong("destination_lng_bits", Double.doubleToRawLongBits(destinationLng)).apply();
        } else {
            tripMode = state.getString(EXTRA_MODE, "Ride");
            tripCode = state.getString(EXTRA_CODE, "—");
            expiresAt = state.getLong(EXTRA_EXPIRES_AT, 0);
            destinationTitle = state.getString(EXTRA_DESTINATION_TITLE, "Destination");
            destinationLat = Double.longBitsToDouble(state.getLong("destination_lat_bits", 0));
            destinationLng = Double.longBitsToDouble(state.getLong("destination_lng_bits", 0));
            riderName = state.getString(EXTRA_RIDER_NAME, "Rider");
            localRiderId = state.getString(EXTRA_RIDER_ID, "");
            riderPhone = state.getString(EXTRA_RIDER_PHONE, "");
        }
        if (expiresAt <= System.currentTimeMillis()) {
            requestStop("Trip time ended. Location sharing stopped.");
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHOW_OVERLAY.equals(intent.getAction())) showRideOverlay();
        if (intent != null && ACTION_HIDE_OVERLAY.equals(intent.getAction())) hideRideOverlay(true);
        if (getSharedPreferences("active_ride", MODE_PRIVATE).getBoolean("overlay_enabled", false)
                && Settings.canDrawOverlays(this)) showRideOverlay();
        handler.removeCallbacks(notificationTick);
        handler.post(notificationTick);
        if ("cloud".equals(tripMode) && cloudLocation == null) setupCloudPublisher();
        return START_REDELIVER_INTENT;
    }

    private void setupCloudPublisher() {
        try {
            FirebaseApp app = FirebaseApp.initializeApp(this);
            if (app == null) app = FirebaseApp.getInstance();
            final FirebaseApp finalApp = app;
            FirebaseAuth auth = FirebaseAuth.getInstance(finalApp);
            Runnable connect = () -> {
                cloudRiderId = auth.getUid();
                if (cloudRiderId == null) return;
                cloudLocation = FirebaseDatabase.getInstance(finalApp).getReference("trips")
                        .child(tripCode).child("locations").child(cloudRiderId);
                cloudLocationsRoot = cloudLocation.getParent();
                cloudLocation.onDisconnect().removeValue();
                listenForCloudRiders();
            };
            if (auth.getCurrentUser() != null) connect.run();
            else auth.signInAnonymously().addOnSuccessListener(result -> connect.run());
        } catch (Exception ignored) { }
    }

    private void listenForCloudRiders() {
        if (cloudLocationsRoot == null || cloudRiderListener != null) return;
        cloudRiderListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String previousChildName) { acceptSnapshot(snapshot); }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) { acceptSnapshot(snapshot); }
            @Override public void onChildRemoved(DataSnapshot snapshot) {
                if (snapshot.getKey() == null || snapshot.getKey().equals(cloudRiderId)) return;
                removeRider(snapshot.getKey());
            }
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) { }
            @Override public void onCancelled(DatabaseError error) { }
            private void acceptSnapshot(DataSnapshot snapshot) {
                if (snapshot.getKey() == null || snapshot.getKey().equals(cloudRiderId)) return;
                Object value = snapshot.getValue();
                if (value instanceof Map) updateRider(new JSONObject((Map<?, ?>) value));
            }
        };
        cloudLocationsRoot.addChildEventListener(cloudRiderListener);
    }

    private void updateRider(JSONObject value) {
        String id = value.optString("riderId");
        if (id.isBlank() || id.equals(localRiderId) || id.equals(cloudRiderId)) return;
        RiderState rider = riders.get(id);
        if (rider == null) {
            rider = new RiderState();
            rider.id = id;
            riders.put(id, rider);
        }
        rider.name = value.optString("name", "Rider");
        rider.phone = value.optString("phone", "");
        rider.updatedAt = value.optLong("updatedAt", System.currentTimeMillis());
        rider.accuracy = value.optDouble("accuracy", 0);
        rider.lat = value.optDouble("lat", Double.NaN);
        rider.lng = value.optDouble("lng", Double.NaN);
        rider.speed = value.optDouble("speed", 0);
        String alertId = value.optString("safetyAlertId", "");
        String safetyType = value.optString("safetyType", "");
        rider.safetyType = safetyType;
        if (!alertId.isBlank() && !alertId.equals(rider.safetyAlertId)) {
            rider.safetyAlertId = alertId;
            sendRiderAlert(rider, rider.name + ("breakdown".equals(safetyType)
                    ? " reported a breakdown" : " sent an SOS with their live location"));
        } else if (alertId.isBlank()) rider.safetyAlertId = "";
        if (System.currentTimeMillis() - rider.updatedAt < 30_000) rider.staleAlerted = false;
        refreshNotification();
    }

    private void publishCloudLocation(Location location) {
        if (cloudLocation == null || expiresAt <= System.currentTimeMillis()) return;
        Map<String, Object> data = new HashMap<>();
        data.put("riderId", cloudRiderId);
        data.put("name", riderName);
        if (!riderPhone.isBlank()) data.put("phone", riderPhone);
        data.put("lat", location.getLatitude());
        data.put("lng", location.getLongitude());
        data.put("heading", location.hasBearing() ? location.getBearing() : 0);
        data.put("speed", location.hasSpeed() ? location.getSpeed() : 0);
        data.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0);
        data.put("updatedAt", System.currentTimeMillis());
        cloudLocation.updateChildren(data);
    }

    private Notification buildNotification(String status) {
        pruneStaleRiders();
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent appIntent = PendingIntent.getActivity(this, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String label = destinationTitle == null ? "Destination" : destinationTitle;
        String uri = "geo:" + destinationLat + "," + destinationLng + "?q="
                + destinationLat + "," + destinationLng + "(" + Uri.encode(label) + ")";
        Intent openMap = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        PendingIntent mapIntent = PendingIntent.getActivity(this, 2, openMap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RideLocationService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String details = "RIDING TO\n" + label + "\n\n" + buildRiderStandings()
                + "\n\nRIDE ENDS\n" + formatTime(expiresAt) + " · " + status;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.ridebuddy.app.R.drawable.ic_launcher)
                .setContentTitle("Riding to " + label)
                .setContentText(riderCountText() + " · ends " + formatTime(expiresAt))
                .setSubText("nearby".equals(tripMode) ? "Nearby Ride" : "Connected Ride")
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentIntent(appIntent)
                .addAction(new Notification.Action.Builder(null, "OPEN MAP", mapIntent).build())
                .addAction(new Notification.Action.Builder(null, "STOP SHARING", stopIntent).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private final Runnable notificationTick = new Runnable() {
        @Override public void run() {
            long remaining = expiresAt - System.currentTimeMillis();
            if (remaining <= 0) {
                requestStop("Trip time ended. Location sharing stopped.");
                return;
            }
            refreshNotification();
            handler.postDelayed(this, 30_000);
        }
    };

    private void refreshNotification() {
        if (expiresAt <= 0) return;
        checkRiderHealth();
        long remaining = Math.max(0, expiresAt - System.currentTimeMillis());
        getSystemService(NotificationManager.class).notify(71,
                buildNotification(formatDuration(remaining) + " remaining"));
        refreshOverlay();
    }

    private void acceptOverlayStops(String json) {
        try {
            JSONArray stops = new JSONArray(json == null ? "[]" : json);
            StringBuilder summary = new StringBuilder();
            int start = Math.max(0, stops.length() - 2);
            for (int i = start; i < stops.length(); i++) {
                JSONObject stop = stops.optJSONObject(i);
                if (stop == null) continue;
                if (summary.length() > 0) summary.append('\n');
                String type = stop.optString("type", "marker").toUpperCase(Locale.US);
                String label = stop.optString("label", "Route marker");
                summary.append("◆ ").append(type).append(" · ").append(label);
            }
            overlayStopsSummary = summary.toString();
            refreshOverlay();
        } catch (Exception ignored) { }
    }

    private void showRideOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return;
        windowManager = getSystemService(WindowManager.class);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(242, 10, 28, 23));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.argb(90, 247, 183, 49));
        card.setBackground(background);
        card.setElevation(dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        overlayTitle = overlayText("RIDEBUDDY HUD", 12, Color.rgb(247, 183, 49), true);
        header.addView(overlayTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView close = overlayText("×", 22, Color.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close RideBuddy overlay");
        close.setOnClickListener(view -> hideRideOverlay(true));
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        card.addView(header);

        overlayDestination = overlayText("", 11, Color.rgb(198, 210, 206), false);
        overlayDestination.setPadding(0, 0, 0, dp(7));
        card.addView(overlayDestination);
        overlayRiders = overlayText("Waiting for live positions…", 13, Color.WHITE, false);
        overlayRiders.setLineSpacing(dp(3), 1f);
        card.addView(overlayRiders);
        overlayEvents = overlayText("", 11, Color.rgb(255, 198, 91), true);
        overlayEvents.setPadding(0, dp(8), 0, 0);
        card.addView(overlayEvents);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(10);
        params.y = dp(92);
        attachDragHandler(header, params);
        overlayView = card;
        windowManager.addView(card, params);
        getSharedPreferences("active_ride", MODE_PRIVATE).edit()
                .putBoolean("overlay_enabled", true).apply();
        refreshOverlay();
    }

    private void attachDragHandler(View handle, WindowManager.LayoutParams params) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = params.x; startY = params.y;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE && overlayView != null) {
                    params.x = Math.max(0, startX - Math.round(event.getRawX() - downX));
                    params.y = Math.max(0, startY + Math.round(event.getRawY() - downY));
                    windowManager.updateViewLayout(overlayView, params);
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP;
            }
        });
    }

    private void refreshOverlay() {
        if (overlayView == null || overlayTitle == null) return;
        overlayTitle.setText("RIDEBUDDY · " + tripCode);
        overlayDestination.setText("TO " + (destinationTitle == null ? "Destination" : destinationTitle)
                + "  ·  " + formatDuration(Math.max(0, expiresAt - System.currentTimeMillis())) + " LEFT");
        overlayRiders.setText(buildOverlayRiderBoard());
        String alerts = buildOverlayAlerts();
        if (!overlayStopsSummary.isBlank()) alerts = alerts.isBlank()
                ? overlayStopsSummary : alerts + "\n" + overlayStopsSummary;
        overlayEvents.setText(alerts);
        overlayEvents.setVisibility(alerts.isBlank() ? View.GONE : View.VISIBLE);
    }

    private String buildOverlayRiderBoard() {
        List<RiderProgress> progress = new ArrayList<>();
        Map<String, RiderState> states = new HashMap<>();
        if (latestLocation != null) progress.add(new RiderProgress(riderName + " (you)",
                distanceToDestinationKm(latestLocation.getLatitude(), latestLocation.getLongitude())));
        for (RiderState rider : riders.values()) {
            if (!Double.isNaN(rider.lat) && !Double.isNaN(rider.lng)) {
                String name = rider.name == null ? "Rider" : rider.name;
                progress.add(new RiderProgress(name, distanceToDestinationKm(rider.lat, rider.lng)));
                states.put(name, rider);
            }
        }
        if (progress.isEmpty()) return "Waiting for live positions…";
        progress.sort(Comparator.comparingDouble(item -> item.distanceToDestinationKm));
        double leader = progress.get(0).distanceToDestinationKm;
        StringBuilder board = new StringBuilder();
        int count = Math.min(progress.size(), 6);
        for (int i = 0; i < count; i++) {
            RiderProgress item = progress.get(i);
            if (i > 0) board.append('\n');
            board.append(i + 1).append("  ").append(item.name).append("  ");
            if (i == 0) board.append("LEAD");
            else board.append(String.format(Locale.getDefault(), "%.1f km behind",
                    Math.max(0, item.distanceToDestinationKm - leader)));
            RiderState state = states.get(item.name);
            if (state != null) board.append("  ·  ").append(riderMotionState(state));
            else if (latestLocation != null) board.append("  ·  ")
                    .append(latestLocation.hasSpeed() && latestLocation.getSpeed() >= .8f
                            ? Math.round(latestLocation.getSpeed() * 3.6f) + " km/h" : "STOPPED");
        }
        return board.toString();
    }

    private String riderMotionState(RiderState rider) {
        long age = System.currentTimeMillis() - rider.updatedAt;
        if (age > 60_000) return "NO SIGNAL";
        if (!rider.safetyType.isBlank()) return rider.safetyType.toUpperCase(Locale.US);
        return rider.speed >= .8 ? Math.round(rider.speed * 3.6) + " km/h" : "STOPPED";
    }

    private String buildOverlayAlerts() {
        StringBuilder alerts = new StringBuilder();
        long now = System.currentTimeMillis();
        for (RiderState rider : riders.values()) {
            String message = "";
            if (!rider.safetyType.isBlank()) message = "⚠ " + rider.name + " · "
                    + rider.safetyType.toUpperCase(Locale.US);
            else if (now - rider.updatedAt > 60_000) message = "⚠ " + rider.name + " · SIGNAL LOST";
            if (!message.isBlank()) {
                if (alerts.length() > 0) alerts.append('\n');
                alerts.append(message);
            }
        }
        return alerts.toString();
    }

    private TextView overlayText(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideRideOverlay(boolean rememberDisabled) {
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) { }
        }
        overlayView = null;
        overlayTitle = null;
        overlayDestination = null;
        overlayRiders = null;
        overlayEvents = null;
        if (rememberDisabled) getSharedPreferences("active_ride", MODE_PRIVATE).edit()
                .putBoolean("overlay_enabled", false).apply();
    }

    private String riderCountText() {
        int attention = 0;
        long now = System.currentTimeMillis();
        for (RiderState rider : riders.values()) {
            if (now - rider.updatedAt > 60_000 || rider.accuracy > 50) attention++;
        }
        int total = riders.size() + 1;
        String text = total + (total == 1 ? " rider" : " riders");
        return attention > 0 ? text + " · " + attention + " need attention" : text;
    }

    private void pruneStaleRiders() {
        long staleBefore = System.currentTimeMillis() - 120_000;
        riders.entrySet().removeIf(entry -> entry.getValue().updatedAt < staleBefore);
    }

    private String buildRiderStandings() {
        List<RiderProgress> progress = new ArrayList<>();
        if (latestLocation != null) {
            progress.add(new RiderProgress(riderName + " (you)", distanceToDestinationKm(
                    latestLocation.getLatitude(), latestLocation.getLongitude())));
        }
        for (RiderState rider : riders.values()) {
            if (!Double.isNaN(rider.lat) && !Double.isNaN(rider.lng)) {
                progress.add(new RiderProgress(rider.name == null ? "Rider" : rider.name,
                        distanceToDestinationKm(rider.lat, rider.lng)));
            }
        }
        if (progress.isEmpty()) return "RIDERS\nWaiting for live positions…";
        progress.sort(Comparator.comparingDouble(item -> item.distanceToDestinationKm));
        double leaderDistance = progress.get(0).distanceToDestinationKm;
        StringBuilder board = new StringBuilder("RIDERS — APPROX. GAP\n");
        for (int index = 0; index < progress.size(); index++) {
            RiderProgress rider = progress.get(index);
            board.append(index + 1).append(". ").append(rider.name).append(" — ");
            if (index == 0) board.append("LEAD");
            else board.append(String.format(Locale.getDefault(), "≈%.1f km behind",
                    Math.max(0, rider.distanceToDestinationKm - leaderDistance)));
            if (index < progress.size() - 1) board.append('\n');
        }
        return board.toString();
    }

    private double distanceToDestinationKm(double lat, double lng) {
        float[] result = new float[1];
        Location.distanceBetween(lat, lng, destinationLat, destinationLng, result);
        return result[0] / 1000.0;
    }

    private void checkRiderHealth() {
        long now = System.currentTimeMillis();
        for (RiderState rider : riders.values()) {
            if (now - rider.updatedAt > 60_000 && !rider.staleAlerted) {
                rider.staleAlerted = true;
                sendRiderAlert(rider, rider.name + " has not updated for over a minute");
            }
        }
    }

    private void sendRiderAlert(RiderState rider, String message) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 20 + Math.abs(rider.id.hashCode() % 1000),
                openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(com.ridebuddy.app.R.drawable.ic_launcher)
                .setContentTitle("RideBuddy rider alert")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message + "\nTrip " + tripCode))
                .setContentIntent(open)
                .setAutoCancel(true);
        if (rider.phone != null && !rider.phone.isBlank()) {
            Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(rider.phone)));
            PendingIntent call = PendingIntent.getActivity(this,
                    1200 + Math.abs(rider.id.hashCode() % 1000), dial,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "CALL " + rider.name, call).build());
        }
        getSystemService(NotificationManager.class).notify(2000 + Math.abs(rider.id.hashCode() % 1000), builder.build());
    }

    private void requestStop(String reason) {
        hideRideOverlay(true);
        if (cloudLocation != null) cloudLocation.removeValue();
        if (cloudLocationsRoot != null && cloudRiderListener != null) {
            cloudLocationsRoot.removeEventListener(cloudRiderListener);
            cloudRiderListener = null;
        }
        getSharedPreferences("active_ride", MODE_PRIVATE).edit().clear().apply();
        Listener current = listener;
        if (current != null) current.onStopRequested(reason);
        listener = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }

    private String formatDuration(long millis) {
        long totalMinutes = Math.max(1, millis / 60_000);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours > 0 ? String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
                : String.format(Locale.getDefault(), "%dm", minutes);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onLocationChanged(Location location) {
        latestLocation = new Location(location);
        if ("cloud".equals(tripMode)) publishCloudLocation(location);
        refreshNotification();
        Listener current = listener;
        if (current != null) current.onLocation(location);
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onDestroy() {
        hideRideOverlay(false);
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null) locationManager.removeUpdates(this);
        if (cloudLocationsRoot != null && cloudRiderListener != null) {
            cloudLocationsRoot.removeEventListener(cloudRiderListener);
        }
        instance = null;
        super.onDestroy();
    }
}
