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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.HashMap;

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
    static final String EXTRA_RIDER_PHONE = "rider_phone";
    private static final String ACTION_STOP = "com.ridebuddy.app.STOP_SHARING";
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
    private String riderPhone = "";
    private String cloudRiderId;
    private DatabaseReference cloudLocation;
    private DatabaseReference cloudLocationsRoot;
    private ChildEventListener cloudRiderListener;

    private static final class RiderState {
        String id;
        String name;
        String phone;
        long updatedAt;
        double accuracy;
        boolean staleAlerted;
        String safetyAlertId = "";
    }

    static void setListener(Listener value) { listener = value; }

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
            riderPhone = intent.getStringExtra(EXTRA_RIDER_PHONE);
            if (riderName == null || riderName.isBlank()) riderName = "Rider";
            if (riderPhone == null) riderPhone = "";
            state.edit().putString(EXTRA_MODE, tripMode).putString(EXTRA_CODE, tripCode)
                    .putBoolean("active", true)
                    .putLong(EXTRA_EXPIRES_AT, expiresAt)
                    .putString(EXTRA_DESTINATION_TITLE, destinationTitle)
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
            riderPhone = state.getString(EXTRA_RIDER_PHONE, "");
        }
        if (expiresAt <= System.currentTimeMillis()) {
            requestStop("Trip time ended. Location sharing stopped.");
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(notificationTick);
        handler.post(notificationTick);
        if ("cloud".equals(tripMode)) setupCloudPublisher();
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
        if (id.isBlank() || id.equals(cloudRiderId)) return;
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
        String alertId = value.optString("safetyAlertId", "");
        String safetyType = value.optString("safetyType", "");
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

        String details = label + "\nTrip " + tripCode + " · " + riderCountText()
                + "\nEnds " + formatTime(expiresAt) + " · " + status;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.ridebuddy.app.R.drawable.ic_launcher)
                .setContentTitle("RideBuddy → " + label)
                .setContentText("Trip " + tripCode + " · " + status)
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
    }

    private String riderCountText() {
        long staleBefore = System.currentTimeMillis() - 120_000;
        riders.entrySet().removeIf(entry -> entry.getValue().updatedAt < staleBefore);
        int attention = 0;
        long now = System.currentTimeMillis();
        for (RiderState rider : riders.values()) {
            if (now - rider.updatedAt > 60_000 || rider.accuracy > 50) attention++;
        }
        String text = riders.size() + (riders.size() == 1 ? " rider visible" : " riders visible");
        return attention > 0 ? text + " · " + attention + " need attention" : text;
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
        if ("cloud".equals(tripMode)) publishCloudLocation(location);
        Listener current = listener;
        if (current != null) current.onLocation(location);
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null) locationManager.removeUpdates(this);
        if (cloudLocationsRoot != null && cloudRiderListener != null) {
            cloudLocationsRoot.removeEventListener(cloudRiderListener);
        }
        instance = null;
        super.onDestroy();
    }
}
