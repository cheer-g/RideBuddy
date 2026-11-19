package com.ridebuddy.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.window.OnBackInvokedDispatcher;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 42;
    private static final int NEARBY_REQUEST = 43;
    private static final int NOTIFICATION_REQUEST = 44;
    private static final int OVERLAY_REQUEST = 45;
    private WebView mapView;
    private TripSyncManager tripSyncManager;
    private Runnable pendingNearbyAction;
    private Intent pendingServiceIntent;
    private boolean pendingOverlayEnable;
    private boolean activityAlive = true;
    private boolean tripActive;
    private String activeTripMode;
    private String activeTripCode;
    private String activeRiderId;
    private long activeExpiresAt;
    private String activeDestinationTitle;
    private double activeDestinationLat;
    private double activeDestinationLng;
    private String pendingRiderName = "Rider";
    private String pendingRiderPhone = "";
    private double pendingOdometerStart = -1;
    private boolean restoreAttempted;
    private String pendingInviteMode;
    private String pendingInviteCode;
    private boolean launchedFromInvite;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mapView = new WebView(this);
        setContentView(mapView);
        tripSyncManager = new TripSyncManager(this, new TripSyncManager.Listener() {
            @Override public void onStatus(String message) { callJs("window.onTripStatus", message); }
            @Override public void onTripReady(String mode, String code, String riderId, long expiresAt,
                                              String destinationTitle, double destinationLat, double destinationLng) {
                tripActive = true;
                activeTripMode = mode;
                activeTripCode = code;
                activeRiderId = riderId;
                activeExpiresAt = expiresAt;
                activeDestinationTitle = destinationTitle;
                activeDestinationLat = destinationLat;
                activeDestinationLng = destinationLng;
                getSharedPreferences("active_ride", MODE_PRIVATE).edit()
                        .putBoolean("active", true)
                        .putString(RideLocationService.EXTRA_MODE, mode)
                        .putString(RideLocationService.EXTRA_CODE, code)
                        .putLong(RideLocationService.EXTRA_EXPIRES_AT, expiresAt)
                        .putString(RideLocationService.EXTRA_DESTINATION_TITLE, destinationTitle)
                        .putLong("destination_lat_bits", Double.doubleToRawLongBits(destinationLat))
                        .putLong("destination_lng_bits", Double.doubleToRawLongBits(destinationLng))
                        .putString(RideLocationService.EXTRA_RIDER_NAME, pendingRiderName)
                        .putString(RideLocationService.EXTRA_RIDER_PHONE, pendingRiderPhone)
                        .putLong("odometer_start_bits", Double.doubleToRawLongBits(pendingOdometerStart))
                        .apply();
                callJs("window.onTripReady", mode, code, riderId, Long.toString(expiresAt),
                        destinationTitle, Double.toString(destinationLat), Double.toString(destinationLng),
                        Double.toString(pendingOdometerStart));
                startRideLocationService(mode, code, expiresAt, destinationTitle, destinationLat,
                        destinationLng, riderId, pendingRiderName, pendingRiderPhone);
            }
            @Override public void onPairingRequest(String endpointId, String riderName, String digits) {
                callJs("window.onPairingRequest", endpointId, riderName, digits);
            }
            @Override public void onRiderLocation(String json) {
                callJs("window.onRiderLocation", json);
                RideLocationService.noteRider(json);
            }
            @Override public void onRiderLeft(String riderId) {
                callJs("window.onRiderLeft", riderId);
                RideLocationService.removeRider(riderId);
            }
            @Override public void onTripPreview(String code, String destinationTitle,
                                                double destinationLat, double destinationLng, long expiresAt) {
                callJs("window.onTripPreview", code, destinationTitle,
                        Double.toString(destinationLat), Double.toString(destinationLng),
                        Long.toString(expiresAt));
            }
            @Override public void onOrganizerState(boolean organizer, boolean joinLocked,
                                                   long tripExpiresAt, String membersJson) {
                callJs("window.onOrganizerState", Boolean.toString(organizer),
                        Boolean.toString(joinLocked), Long.toString(tripExpiresAt), membersJson);
            }
            @Override public void onTripTerminated(String reason) { endActiveTrip(reason); }
            @Override public void onSharingCutoffChanged(long sharingUntil, long tripExpiresAt) {
                activeExpiresAt = sharingUntil;
                getSharedPreferences("active_ride", MODE_PRIVATE).edit()
                        .putLong(RideLocationService.EXTRA_EXPIRES_AT, sharingUntil).apply();
                callJs("window.onSharingCutoffChanged", Long.toString(sharingUntil),
                        Long.toString(tripExpiresAt));
                startRideLocationService(activeTripMode, activeTripCode, sharingUntil,
                        activeDestinationTitle, activeDestinationLat, activeDestinationLng,
                        activeRiderId, pendingRiderName, pendingRiderPhone);
            }
            @Override public void onStopsChanged(String stopsJson) {
                callJs("window.onRouteStopsChanged", stopsJson);
                RideLocationService.updateOverlayStops(stopsJson);
            }
        });

        mapView.getSettings().setJavaScriptEnabled(true);
        mapView.getSettings().setDomStorageEnabled(true);
        mapView.getSettings().setGeolocationEnabled(true);
        mapView.getSettings().setUserAgentString(
                mapView.getSettings().getUserAgentString() + " RideBuddy/0.1 (Android ride navigation prototype)"
        );
        mapView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                deliverPendingInvite();
                restoreActiveTripIfNeeded();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("tel".equals(uri.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_DIAL, uri));
                    return true;
                }
                return false;
            }
        });
        mapView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, LOCATION_REQUEST);
                }
            }
        });
        mapView.addJavascriptInterface(new MapApi(), "RideBuddyApi");
        RideLocationService.setListener(new RideLocationService.Listener() {
            @Override public void onLocation(Location location) { onRideLocation(location); }
            @Override public void onStopRequested(String reason) { endActiveTrip(reason); }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        mapView.loadUrl("file:///android_asset/map.html");
        readInvite(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readInvite(intent);
        deliverPendingInvite();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlayEnable && tripActive && Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = false;
            setRideOverlayEnabled(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST && pendingGeoCallback != null) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == NEARBY_REQUEST && pendingNearbyAction != null) {
            boolean granted = true;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            Runnable action = pendingNearbyAction;
            pendingNearbyAction = null;
            if (granted) action.run();
            else callJs("window.onTripStatus", "Nearby Ride needs Bluetooth, nearby-device and location permissions.");
        } else if (requestCode == NOTIFICATION_REQUEST && pendingServiceIntent != null) {
            Intent service = pendingServiceIntent;
            pendingServiceIntent = null;
            startForegroundService(service);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OVERLAY_REQUEST || !pendingOverlayEnable) return;
        pendingOverlayEnable = false;
        if (Settings.canDrawOverlays(this)) {
            setRideOverlayEnabled(true);
        } else {
            callJs("window.onOverlayState", "false");
            callJs("window.onTripStatus", "Allow Display over other apps to use the Ride HUD with Google Maps.");
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (mapView == null) {
            if (tripActive) moveTaskToBack(true); else finish();
            return;
        }
        mapView.evaluateJavascript(
                "Boolean(window.handleBackNavigation && window.handleBackNavigation())",
                result -> {
                    if ("true".equals(result)) return;
                    if (mapView.canGoBack()) mapView.goBack();
                    else if (tripActive) moveTaskToBack(true);
                    else finish();
                });
    }

    @Override
    protected void onDestroy() {
        activityAlive = false;
        networkExecutor.shutdownNow();
        if (!tripActive) {
            tripSyncManager.leave();
            stopService(new Intent(this, RideLocationService.class));
            RideLocationService.setListener(null);
            mapView.destroy();
        }
        super.onDestroy();
    }

    private final class MapApi {
        @JavascriptInterface
        public boolean cloudConfigured() {
            return tripSyncManager.isCloudConfigured();
        }

        @JavascriptInterface
        public void createTrip(String mode, String name, double expiresAt,
                               String destinationTitle, double destinationLat, double destinationLng,
                               double odometerStart, String phone) {
            beginTrip(mode, null, name, true, (long) expiresAt,
                    destinationTitle, destinationLat, destinationLng, odometerStart, phone);
        }

        @JavascriptInterface
        public void joinTrip(String mode, String code, String name, double sharingUntil,
                             double odometerStart, String phone) {
            beginTrip(mode, code, name, false, (long) sharingUntil, null, 0, 0,
                    odometerStart, phone);
        }

        @JavascriptInterface
        public void previewTrip(String code) {
            tripSyncManager.previewConnectedTrip(code);
        }

        @JavascriptInterface
        public void respondToPairing(String endpointId, boolean accept) {
            tripSyncManager.respondToPairing(endpointId, accept);
        }

        @JavascriptInterface
        public void publishLocation(double lat, double lng, double heading, double speed, double accuracy) {
            tripSyncManager.publishLocation(lat, lng, heading, speed, accuracy);
        }

        @JavascriptInterface
        public void leaveTrip() {
            endActiveTrip("You left the ride group.");
        }

        @JavascriptInterface
        public void finishTrip(double odometerEnd) {
            tripSyncManager.recordTripEnd(odometerEnd);
            endActiveTrip("Ride completed. Location sharing stopped.");
        }

        @JavascriptInterface public void setJoinLocked(boolean locked) { tripSyncManager.setJoinLocked(locked); }
        @JavascriptInterface public void extendTrip(double expiresAt) { tripSyncManager.extendTrip((long) expiresAt); }
        @JavascriptInterface public void removeRider(String riderId) { tripSyncManager.removeMember(riderId); }
        @JavascriptInterface public void makeOrganizer(String riderId) { tripSyncManager.transferOrganizer(riderId); }
        @JavascriptInterface public void endTripForEveryone() { tripSyncManager.endTripForEveryone(); }
        @JavascriptInterface public void addRouteStop(String type, String label, double lat, double lng) {
            tripSyncManager.addRouteStop(type, label, lat, lng);
        }
        @JavascriptInterface public void removeRouteStop(String stopId) { tripSyncManager.removeRouteStop(stopId); }
        @JavascriptInterface public void setSafetyStatus(String type) { tripSyncManager.setSafetyStatus(type); }
        @JavascriptInterface public void clearSafetyStatus() { tripSyncManager.clearSafetyStatus(); }
        @JavascriptInterface public void acknowledgeSafety(String alertId) { tripSyncManager.acknowledgeSafety(alertId); }

        @JavascriptInterface
        public void toggleRideOverlay() {
            runOnUiThread(() -> {
                if (!tripActive) {
                    callJs("window.onTripStatus", "Start or join a ride before opening the Ride HUD.");
                    return;
                }
                if (RideLocationService.isOverlayVisible()) {
                    setRideOverlayEnabled(false);
                    return;
                }
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    pendingOverlayEnable = true;
                    callJs("window.onTripStatus", "Enable Display over other apps, then return to RideBuddy.");
                    Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(settings, OVERLAY_REQUEST);
                    return;
                }
                setRideOverlayEnabled(true);
            });
        }

        @JavascriptInterface
        public void shareTrip(String mode, String code) {
            runOnUiThread(() -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                String label = "nearby".equals(mode) ? "Nearby Ride" : "Connected Ride";
                String link = "https://ridebuddy-107cc.web.app/join?mode=" + mode + "&code=" + code;
                String directLink = "ridebuddy://join?mode=" + mode + "&code=" + code;
                share.putExtra(Intent.EXTRA_TEXT, "Join my RideBuddy " + label + "\n" + link
                        + "\n\nOpen directly in the app:\n" + directLink + "\n\nTrip code: " + code);
                startActivity(Intent.createChooser(share, "Share RideBuddy trip"));
            });
        }

        @JavascriptInterface
        public void search(String query) {
            networkExecutor.execute(() -> {
                try {
                    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                    String result = get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1&q=" + encoded);
                    callJs("window.onSearchResults", result);
                } catch (Exception error) {
                    callJs("window.onMapError", "Search is unavailable. Check your connection.");
                }
            });
        }

        @JavascriptInterface
        public void searchCreateDestination(String query) {
            networkExecutor.execute(() -> {
                try {
                    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                    String result = get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1&q=" + encoded);
                    callJs("window.onCreateDestinationResults", result);
                } catch (Exception error) {
                    callJs("window.onMapError", "Destination search is unavailable. Check your connection.");
                }
            });
        }

        @JavascriptInterface
        public void route(double fromLng, double fromLat, double toLng, double toLat) {
            networkExecutor.execute(() -> {
                try {
                    String coordinates = fromLng + "," + fromLat + ";" + toLng + "," + toLat;
                    String result = get("https://router.project-osrm.org/route/v1/driving/" + coordinates
                            + "?overview=full&geometries=geojson&steps=true");
                    callJs("window.onRouteResult", result);
                } catch (Exception error) {
                    callJs("window.onMapError", "A route could not be calculated.");
                }
            });
        }

        private String get(String address) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "RideBuddy/0.1 Android prototype");
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
                return body.toString();
            } finally {
                connection.disconnect();
            }
        }

    }

    private void beginTrip(String mode, String code, String name, boolean create, long expiresAt,
                           String destinationTitle, double destinationLat, double destinationLng,
                           double odometerStart, String phone) {
        pendingRiderName = name == null || name.isBlank() ? "Rider" : name;
        pendingRiderPhone = phone == null ? "" : phone;
        pendingOdometerStart = odometerStart;
        Runnable action = () -> {
            if (create) tripSyncManager.createTrip(mode, name, expiresAt,
                    destinationTitle, destinationLat, destinationLng, phone);
            else tripSyncManager.joinTrip(mode, code, name, expiresAt, odometerStart, phone);
        };
        if (!"nearby".equals(mode)) {
            action.run();
            return;
        }
        runOnUiThread(() -> {
            String[] permissions = nearbyPermissions();
            boolean granted = true;
            for (String permission : permissions) {
                granted &= checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) action.run();
            else {
                pendingNearbyAction = action;
                requestPermissions(permissions, NEARBY_REQUEST);
            }
        });
    }

    private String[] nearbyPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION};
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void callJs(String function, String... values) {
        if (!activityAlive) return;
        StringBuilder script = new StringBuilder(function).append('(');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) script.append(',');
            String value = values[i] == null ? "" : values[i];
            String safe = value.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("</", "<\\/");
            script.append('\'').append(safe).append('\'');
        }
        script.append(')');
        runOnUiThread(() -> mapView.evaluateJavascript(script.toString(), null));
    }

    private void startRideLocationService(String mode, String code, long expiresAt,
                                          String destinationTitle, double destinationLat, double destinationLng,
                                          String riderId, String riderName, String riderPhone) {
        runOnUiThread(() -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                callJs("window.onTripStatus", "Location permission is required to share your position.");
                return;
            }
            Intent service = new Intent(this, RideLocationService.class)
                    .putExtra(RideLocationService.EXTRA_MODE, mode)
                    .putExtra(RideLocationService.EXTRA_CODE, code)
                    .putExtra(RideLocationService.EXTRA_EXPIRES_AT, expiresAt)
                    .putExtra(RideLocationService.EXTRA_DESTINATION_TITLE, destinationTitle)
                    .putExtra(RideLocationService.EXTRA_DESTINATION_LAT, destinationLat)
                    .putExtra(RideLocationService.EXTRA_DESTINATION_LNG, destinationLng)
                    .putExtra(RideLocationService.EXTRA_RIDER_ID, riderId)
                    .putExtra(RideLocationService.EXTRA_RIDER_NAME, riderName)
                    .putExtra(RideLocationService.EXTRA_RIDER_PHONE, riderPhone);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingServiceIntent = service;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                return;
            }
            startForegroundService(service);
        });
    }

    private void endActiveTrip(String reason) {
        tripActive = false;
        getSharedPreferences("active_ride", MODE_PRIVATE).edit().clear().apply();
        tripSyncManager.leave();
        stopService(new Intent(this, RideLocationService.class));
        callJs("window.onOverlayState", "false");
        callJs("window.onTripEnded", reason);
    }

    private void setRideOverlayEnabled(boolean enabled) {
        Intent intent = new Intent(this, RideLocationService.class)
                .setAction(enabled ? RideLocationService.ACTION_SHOW_OVERLAY
                        : RideLocationService.ACTION_HIDE_OVERLAY);
        startService(intent);
        callJs("window.onOverlayState", Boolean.toString(enabled));
    }

    private void onRideLocation(Location location) {
        if (!"cloud".equals(activeTripMode)) {
            tripSyncManager.publishLocation(location.getLatitude(), location.getLongitude(),
                    location.hasBearing() ? location.getBearing() : 0,
                    location.hasSpeed() ? location.getSpeed() : 0,
                    location.hasAccuracy() ? location.getAccuracy() : 0);
        }
        callJs("window.onNativeLocation", Double.toString(location.getLatitude()),
                Double.toString(location.getLongitude()), Float.toString(location.getAccuracy()),
                Float.toString(location.hasSpeed() ? location.getSpeed() : 0),
                Float.toString(location.hasBearing() ? location.getBearing() : 0),
                Double.toString(location.hasAltitude() ? location.getAltitude() : 0));
    }

    private void readInvite(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        boolean webLink = "https".equals(data.getScheme())
                && "ridebuddy-107cc.web.app".equals(data.getHost())
                && data.getPath() != null && data.getPath().startsWith("/join");
        boolean appLink = "ridebuddy".equals(data.getScheme()) && "join".equals(data.getHost());
        if (!webLink && !appLink) return;
        String code = data.getQueryParameter("code");
        String mode = data.getQueryParameter("mode");
        if (code != null && code.matches("[A-Za-z0-9]{6}")) {
            launchedFromInvite = true;
            pendingInviteCode = code.toUpperCase(java.util.Locale.US);
            pendingInviteMode = "nearby".equals(mode) ? "nearby" : "cloud";
        }
    }

    private void deliverPendingInvite() {
        if (pendingInviteCode == null) return;
        String code = pendingInviteCode;
        String mode = pendingInviteMode;
        pendingInviteCode = null;
        pendingInviteMode = null;
        callJs("window.onInviteLink", mode, code);
    }

    private void restoreActiveTripIfNeeded() {
        if (restoreAttempted || tripActive || launchedFromInvite) return;
        restoreAttempted = true;
        android.content.SharedPreferences state = getSharedPreferences("active_ride", MODE_PRIVATE);
        if (!state.getBoolean("active", false)) return;
        String mode = state.getString(RideLocationService.EXTRA_MODE, "");
        String code = state.getString(RideLocationService.EXTRA_CODE, "");
        long expiresAt = state.getLong(RideLocationService.EXTRA_EXPIRES_AT, 0);
        if (expiresAt <= System.currentTimeMillis()) {
            state.edit().clear().apply();
            return;
        }
        if (!"cloud".equals(mode)) {
            callJs("window.onTripStatus", "Nearby ride needs to reconnect to nearby riders.");
            state.edit().clear().apply();
            return;
        }
        pendingRiderName = state.getString(RideLocationService.EXTRA_RIDER_NAME, "Rider");
        pendingRiderPhone = state.getString(RideLocationService.EXTRA_RIDER_PHONE, "");
        pendingOdometerStart = Double.longBitsToDouble(state.getLong("odometer_start_bits",
                Double.doubleToRawLongBits(-1)));
        callJs("window.onRestoringTrip", code);
        tripSyncManager.joinTrip("cloud", code, pendingRiderName, expiresAt,
                pendingOdometerStart, pendingRiderPhone);
    }
}

