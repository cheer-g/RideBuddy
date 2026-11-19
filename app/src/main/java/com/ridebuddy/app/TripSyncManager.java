package com.ridebuddy.app;

import android.app.Activity;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class TripSyncManager {
    interface Listener {
        void onStatus(String message);
        void onTripReady(String mode, String code, String riderId, long expiresAt,
                         String destinationTitle, double destinationLat, double destinationLng);
        void onPairingRequest(String endpointId, String riderName, String digits);
        void onRiderLocation(String json);
        void onRiderLeft(String riderId);
        void onTripPreview(String code, String destinationTitle, double destinationLat,
                           double destinationLng, long expiresAt);
        void onOrganizerState(boolean organizer, boolean joinLocked, long tripExpiresAt, String membersJson);
        void onTripTerminated(String reason);
        void onSharingCutoffChanged(long sharingUntil, long tripExpiresAt);
        void onStopsChanged(String stopsJson);
    }

    private static final String SERVICE_ID = "com.ridebuddy.app.NEARBY_TRIP_V1";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final Activity activity;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final String nearbyRiderId = UUID.randomUUID().toString();
    private final ConnectionsClient connections;
    private final Map<String, String> connectedEndpoints = new HashMap<>();
    private final Map<String, ConnectionInfo> pendingConnections = new HashMap<>();
    private final Map<String, Boolean> requestedEndpoints = new HashMap<>();
    private final LinkedHashMap<String, Boolean> seenMessages = new LinkedHashMap<>();

    private String mode;
    private String tripCode;
    private String riderName;
    private String riderPhone = "";
    private String riderId;
    private long expiresAt;
    private String destinationTitle;
    private double destinationLat;
    private double destinationLng;
    private boolean nearbyCreating;
    private boolean nearbyReady;
    private long requestedShareUntil;
    private double odometerStart = -1;
    private DatabaseReference cloudTrip;
    private DatabaseReference cloudLocations;
    private ChildEventListener cloudLocationListener;
    private ValueEventListener cloudMetaListener;
    private ValueEventListener cloudMembersListener;
    private ValueEventListener cloudStopsListener;
    private boolean organizer;
    private boolean joinLocked;
    private long tripExpiresAt;
    private String membersJson = "[]";
    private boolean membersLoaded;
    private String safetyType = "";
    private String safetyAlertId = "";
    private String acknowledgedAlertId = "";
    private double lastLat = Double.NaN, lastLng, lastHeading, lastSpeed, lastAccuracy;

    TripSyncManager(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.connections = Nearby.getConnectionsClient(activity);
    }

    boolean isCloudConfigured() {
        try {
            FirebaseApp initializedApp = FirebaseApp.initializeApp(activity);
            if (initializedApp == null) initializedApp = FirebaseApp.getInstance();
            final FirebaseApp app = initializedApp;
            return app.getOptions().getDatabaseUrl() != null
                    && !app.getOptions().getDatabaseUrl().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    void createTrip(String requestedMode, String name, long requestedExpiresAt,
                    String requestedDestinationTitle, double requestedDestinationLat,
                    double requestedDestinationLng, String requestedPhone) {
        if (requestedExpiresAt < System.currentTimeMillis() + 60_000) {
            listener.onStatus("Choose an end time at least one minute from now.");
            return;
        }
        start(requestedMode, newCode(), name, true, requestedExpiresAt,
                sanitizeDestination(requestedDestinationTitle), requestedDestinationLat,
                requestedDestinationLng, requestedPhone);
    }

    void joinTrip(String requestedMode, String code, String name, long shareUntil,
                  double requestedOdometer, String requestedPhone) {
        String cleanCode = code == null ? "" : code.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (cleanCode.length() != 6) {
            listener.onStatus("Enter the six-character trip code.");
            return;
        }
        start(requestedMode, cleanCode, name, false, 0, null, 0, 0, requestedPhone);
        requestedShareUntil = shareUntil;
        odometerStart = requestedOdometer;
    }

    void previewConnectedTrip(String code) {
        String cleanCode = code == null ? "" : code.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (cleanCode.length() != 6) {
            listener.onStatus("Enter the six-character trip code.");
            return;
        }
        if (!isCloudConfigured()) {
            listener.onStatus("Connected Ride needs Firebase configuration.");
            return;
        }
        try {
            FirebaseApp app = FirebaseApp.initializeApp(activity);
            if (app == null) app = FirebaseApp.getInstance();
            final FirebaseApp finalApp = app;
            FirebaseAuth auth = FirebaseAuth.getInstance(finalApp);
            Runnable load = () -> FirebaseDatabase.getInstance(finalApp).getReference("trips")
                    .child(cleanCode).child("meta").get().addOnSuccessListener(snapshot -> {
                        Long end = snapshot.child("expiresAt").getValue(Long.class);
                        String title = snapshot.child("destinationTitle").getValue(String.class);
                        Double lat = snapshot.child("destinationLat").getValue(Double.class);
                        Double lng = snapshot.child("destinationLng").getValue(Double.class);
                        String status = snapshot.child("status").getValue(String.class);
                        Boolean locked = snapshot.child("joinLocked").getValue(Boolean.class);
                        if (!snapshot.exists() || end == null || end <= System.currentTimeMillis()
                                || !"active".equals(status) || title == null || lat == null || lng == null) {
                            listener.onStatus("That ride was not found or has ended.");
                        } else if (Boolean.TRUE.equals(locked)) {
                            listener.onStatus("This ride is no longer accepting new riders.");
                        } else {
                            listener.onTripPreview(cleanCode, title, lat, lng, end);
                        }
                    }).addOnFailureListener(error -> listener.onStatus("Could not load ride: " + error.getMessage()));
            if (auth.getCurrentUser() != null) load.run();
            else auth.signInAnonymously().addOnSuccessListener(result -> load.run())
                    .addOnFailureListener(error -> listener.onStatus("Cloud sign-in failed: " + error.getMessage()));
        } catch (Exception error) {
            listener.onStatus("Could not preview ride: " + error.getMessage());
        }
    }

    private void start(String requestedMode, String code, String name, boolean create, long requestedExpiresAt,
                       String requestedDestinationTitle, double requestedDestinationLat,
                       double requestedDestinationLng, String requestedPhone) {
        leave();
        mode = requestedMode;
        tripCode = code;
        riderName = sanitizeName(name);
        riderPhone = sanitizePhone(requestedPhone);
        expiresAt = requestedExpiresAt;
        if (create) {
            destinationTitle = requestedDestinationTitle;
            destinationLat = requestedDestinationLat;
            destinationLng = requestedDestinationLng;
        }
        if ("cloud".equals(mode)) {
            if (!isCloudConfigured()) {
                listener.onStatus("Connected Ride needs Firebase configuration. See README.md.");
                return;
            }
            startCloud(create);
        } else {
            riderId = nearbyRiderId;
            nearbyCreating = create;
            nearbyReady = create;
            startNearby(create);
            if (create) {
                listener.onTripReady("nearby", tripCode, riderId, expiresAt,
                        destinationTitle, destinationLat, destinationLng);
                listener.onStatus("Nearby trip created. Share the code with riders.");
            } else {
                listener.onStatus("Looking for the nearby trip…");
            }
        }
    }

    private void startCloud(boolean create) {
        try {
            FirebaseApp initializedApp = FirebaseApp.initializeApp(activity);
            if (initializedApp == null) initializedApp = FirebaseApp.getInstance();
            final FirebaseApp app = initializedApp;
            if (app == null) throw new IllegalStateException("Firebase could not start");
            FirebaseAuth auth = FirebaseAuth.getInstance(app);
            if (auth.getCurrentUser() != null) {
                openCloudTrip(app, auth, create);
            } else {
                listener.onStatus("Connecting securely…");
                auth.signInAnonymously().addOnSuccessListener(result -> openCloudTrip(app, auth, create))
                        .addOnFailureListener(error -> listener.onStatus("Cloud sign-in failed: " + error.getMessage()));
            }
        } catch (Exception error) {
            listener.onStatus("Firebase configuration is invalid: " + error.getMessage());
        }
    }

    private void openCloudTrip(FirebaseApp app, FirebaseAuth auth, boolean create) {
        riderId = auth.getUid();
        if (riderId == null) {
            listener.onStatus("Cloud sign-in did not return a rider ID.");
            return;
        }
        FirebaseDatabase database = FirebaseDatabase.getInstance(app);
        cloudTrip = database.getReference("trips").child(tripCode);
        if (create) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("ownerId", riderId);
            meta.put("status", "active");
            meta.put("createdAt", ServerValue.TIMESTAMP);
            meta.put("expiresAt", expiresAt);
            meta.put("joinLocked", false);
            meta.put("destinationTitle", destinationTitle);
            meta.put("destinationLat", destinationLat);
            meta.put("destinationLng", destinationLng);
            cloudTrip.child("meta").setValue(meta)
                    .addOnSuccessListener(unused -> joinCloudTrip())
                    .addOnFailureListener(error -> listener.onStatus("Could not create trip: " + error.getMessage()));
        } else {
            cloudTrip.child("meta").get().addOnSuccessListener(snapshot -> {
                if (snapshot.exists() && "active".equals(snapshot.child("status").getValue(String.class))) {
                    Boolean locked = snapshot.child("joinLocked").getValue(Boolean.class);
                    String ownerId = snapshot.child("ownerId").getValue(String.class);
                    organizer = riderId.equals(ownerId);
                    Long remoteExpiry = snapshot.child("expiresAt").getValue(Long.class);
                    if (remoteExpiry == null || remoteExpiry <= System.currentTimeMillis()) {
                        listener.onStatus("That connected trip has expired.");
                    } else {
                        tripExpiresAt = remoteExpiry;
                        expiresAt = requestedShareUntil > System.currentTimeMillis()
                                ? Math.min(remoteExpiry, requestedShareUntil) : remoteExpiry;
                        destinationTitle = snapshot.child("destinationTitle").getValue(String.class);
                        Double lat = snapshot.child("destinationLat").getValue(Double.class);
                        Double lng = snapshot.child("destinationLng").getValue(Double.class);
                        if (destinationTitle == null || lat == null || lng == null) {
                            listener.onStatus("That trip has no destination.");
                            return;
                        }
                        destinationLat = lat;
                        destinationLng = lng;
                        joinCloudTrip();
                    }
                } else {
                    listener.onStatus("That connected trip was not found or has ended.");
                }
            }).addOnFailureListener(error -> listener.onStatus("Could not find trip: " + error.getMessage()));
        }
    }

    private void joinCloudTrip() {
        Map<String, Object> member = new HashMap<>();
        member.put("name", riderName);
        member.put("joinedAt", ServerValue.TIMESTAMP);
        member.put("sharingUntil", expiresAt);
        if (!riderPhone.isBlank()) member.put("phone", riderPhone);
        if (odometerStart >= 0) member.put("odometerStart", odometerStart);
        cloudTrip.child("members").child(riderId).setValue(member).addOnSuccessListener(unused -> {
            cloudLocations = cloudTrip.child("locations");
            cloudTrip.child("locations").child(riderId).onDisconnect().removeValue();
            listenToCloudLocations();
            listenToCloudControls();
            listener.onTripReady("cloud", tripCode, riderId, expiresAt,
                    destinationTitle, destinationLat, destinationLng);
            listener.onStatus("Connected to trip " + tripCode + ".");
        }).addOnFailureListener(error -> listener.onStatus("Could not join trip: " + error.getMessage()));
    }

    private void listenToCloudControls() {
        cloudMetaListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String status = snapshot.child("status").getValue(String.class);
                if (!"active".equals(status)) {
                    listener.onTripTerminated("The organizer ended this ride.");
                    return;
                }
                String ownerId = snapshot.child("ownerId").getValue(String.class);
                organizer = riderId != null && riderId.equals(ownerId);
                joinLocked = Boolean.TRUE.equals(snapshot.child("joinLocked").getValue(Boolean.class));
                Long newTripEnd = snapshot.child("expiresAt").getValue(Long.class);
                if (newTripEnd != null && newTripEnd != tripExpiresAt) {
                    long previousTripEnd = tripExpiresAt;
                    tripExpiresAt = newTripEnd;
                    if (expiresAt == previousTripEnd || organizer) expiresAt = newTripEnd;
                    else expiresAt = Math.min(expiresAt, newTripEnd);
                    listener.onSharingCutoffChanged(expiresAt, tripExpiresAt);
                }
                emitOrganizerState();
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        cloudMembersListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (membersLoaded && riderId != null && !snapshot.hasChild(riderId)) {
                    listener.onTripTerminated("The organizer removed you from this ride.");
                    return;
                }
                membersLoaded = true;
                org.json.JSONArray values = new org.json.JSONArray();
                for (DataSnapshot child : snapshot.getChildren()) {
                    try {
                        JSONObject member = new JSONObject();
                        member.put("id", child.getKey());
                        member.put("name", child.child("name").getValue(String.class));
                        member.put("phone", child.child("phone").getValue(String.class));
                        member.put("sharingUntil", child.child("sharingUntil").getValue(Long.class));
                        values.put(member);
                    } catch (Exception ignored) { }
                }
                membersJson = values.toString();
                emitOrganizerState();
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        cloudStopsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                org.json.JSONArray values = new org.json.JSONArray();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Object raw = child.getValue();
                    if (!(raw instanceof Map)) continue;
                    try {
                        JSONObject stop = new JSONObject((Map<?, ?>) raw);
                        stop.put("id", child.getKey());
                        values.put(stop);
                    } catch (Exception ignored) { }
                }
                listener.onStopsChanged(values.toString());
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        cloudTrip.child("meta").addValueEventListener(cloudMetaListener);
        cloudTrip.child("members").addValueEventListener(cloudMembersListener);
        cloudTrip.child("stops").addValueEventListener(cloudStopsListener);
    }

    private void emitOrganizerState() {
        listener.onOrganizerState(organizer, joinLocked, tripExpiresAt, membersJson);
    }

    void setJoinLocked(boolean locked) {
        if (organizer && cloudTrip != null) cloudTrip.child("meta").child("joinLocked").setValue(locked);
    }

    void extendTrip(long newExpiresAt) {
        if (organizer && cloudTrip != null && newExpiresAt > tripExpiresAt) {
            cloudTrip.child("meta").child("expiresAt").setValue(newExpiresAt);
        } else listener.onStatus("Choose a time later than the current ride end.");
    }

    void removeMember(String memberId) {
        if (!organizer || cloudTrip == null || memberId == null || memberId.equals(riderId)) return;
        cloudTrip.child("members").child(memberId).removeValue();
        cloudTrip.child("locations").child(memberId).removeValue();
    }

    void transferOrganizer(String memberId) {
        if (!organizer || cloudTrip == null || memberId == null || memberId.equals(riderId)) return;
        cloudTrip.child("meta").child("ownerId").setValue(memberId);
    }

    void endTripForEveryone() {
        if (!organizer || cloudTrip == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put("status", "ended");
        update.put("endedAt", ServerValue.TIMESTAMP);
        cloudTrip.child("meta").updateChildren(update);
    }

    void addRouteStop(String type, String label, double lat, double lng) {
        if (!organizer || cloudTrip == null) return;
        DatabaseReference stop = cloudTrip.child("stops").push();
        Map<String, Object> value = new HashMap<>();
        value.put("type", type == null ? "regroup" : type);
        value.put("label", label == null || label.isBlank() ? "Ride stop" : label);
        value.put("lat", lat);
        value.put("lng", lng);
        value.put("createdBy", riderId);
        value.put("createdAt", ServerValue.TIMESTAMP);
        stop.setValue(value);
    }

    void removeRouteStop(String stopId) {
        if (organizer && cloudTrip != null && stopId != null) cloudTrip.child("stops").child(stopId).removeValue();
    }

    private void listenToCloudLocations() {
        cloudLocationListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String previousChildName) { emitCloud(snapshot); }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) { emitCloud(snapshot); }
            @Override public void onChildRemoved(DataSnapshot snapshot) { listener.onRiderLeft(snapshot.getKey()); }
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) { }
            @Override public void onCancelled(DatabaseError error) { listener.onStatus("Location sync stopped: " + error.getMessage()); }
        };
        cloudLocations.addChildEventListener(cloudLocationListener);
    }

    private void emitCloud(DataSnapshot snapshot) {
        Map<String, Object> value = (Map<String, Object>) snapshot.getValue();
        if (value == null) return;
        try {
            JSONObject json = new JSONObject(value);
            listener.onRiderLocation(json.toString());
        } catch (Exception ignored) { }
    }

    private void startNearby(boolean create) {
        AdvertisingOptions advertising = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        DiscoveryOptions discovery = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        if (create) startNearbyAdvertising(advertising);
        connections.startDiscovery(SERVICE_ID, discoveryCallback, discovery)
                .addOnFailureListener(error -> listener.onStatus("Nearby discovery failed: " + error.getMessage()));
    }

    private void startNearbyAdvertising(AdvertisingOptions options) {
        String endpointName = tripCode + "|" + expiresAt + "|" + destinationLat + "|"
                + destinationLng + "|" + sanitizeDestination(destinationTitle) + "|" + riderName;
        connections.startAdvertising(endpointName, SERVICE_ID, lifecycleCallback, options)
                .addOnFailureListener(error -> listener.onStatus("Nearby advertising failed: " + error.getMessage()));
    }

    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            String[] parts = info.getEndpointName().split("\\|", 6);
            if (parts.length < 6 || !tripCode.equals(parts[0]) || requestedEndpoints.containsKey(endpointId)) return;
            if (!nearbyCreating && expiresAt == 0) {
                try {
                    long tripEnd = Long.parseLong(parts[1]);
                    expiresAt = requestedShareUntil > System.currentTimeMillis()
                            ? Math.min(tripEnd, requestedShareUntil) : tripEnd;
                    destinationLat = Double.parseDouble(parts[2]);
                    destinationLng = Double.parseDouble(parts[3]);
                    destinationTitle = parts[4];
                } catch (NumberFormatException ignored) { return; }
                if (expiresAt <= System.currentTimeMillis()) {
                    listener.onStatus("That nearby trip has expired.");
                    return;
                }
            }
            requestedEndpoints.put(endpointId, true);
            connections.requestConnection(tripCode + "|" + expiresAt + "|" + destinationLat + "|"
                            + destinationLng + "|" + sanitizeDestination(destinationTitle) + "|" + riderName,
                    endpointId, lifecycleCallback)
                    .addOnFailureListener(error -> requestedEndpoints.remove(endpointId));
        }
        @Override public void onEndpointLost(String endpointId) { requestedEndpoints.remove(endpointId); }
    };

    private final ConnectionLifecycleCallback lifecycleCallback = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            String[] parts = info.getEndpointName().split("\\|", 6);
            String name = parts.length == 6 ? parts[5] : info.getEndpointName();
            if (!nearbyCreating && expiresAt == 0 && parts.length == 6) {
                try {
                    expiresAt = Long.parseLong(parts[1]);
                    destinationLat = Double.parseDouble(parts[2]);
                    destinationLng = Double.parseDouble(parts[3]);
                    destinationTitle = parts[4];
                } catch (NumberFormatException ignored) { }
            }
            pendingConnections.put(endpointId, info);
            listener.onPairingRequest(endpointId, name, info.getAuthenticationDigits());
        }

        @Override public void onConnectionResult(String endpointId, ConnectionResolution resolution) {
            pendingConnections.remove(endpointId);
            if (resolution.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoints.put(endpointId, endpointId);
                if (!nearbyReady) {
                    nearbyReady = true;
                    startNearbyAdvertising(new AdvertisingOptions.Builder().setStrategy(STRATEGY).build());
                    listener.onTripReady("nearby", tripCode, riderId, expiresAt,
                            destinationTitle, destinationLat, destinationLng);
                }
                listener.onStatus(connectedEndpoints.size() + " nearby rider(s) connected.");
            } else {
                requestedEndpoints.remove(endpointId);
            }
        }

        @Override public void onDisconnected(String endpointId) {
            connectedEndpoints.remove(endpointId);
            requestedEndpoints.remove(endpointId);
            listener.onStatus(connectedEndpoints.size() + " nearby rider(s) connected.");
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null) return;
            String json = new String(payload.asBytes(), StandardCharsets.UTF_8);
            try {
                JSONObject value = new JSONObject(json);
                String messageId = value.optString("messageId");
                if (messageId.isBlank() || alreadySeen(messageId)) return;
                listener.onRiderLocation(json);
                forwardNearby(json, endpointId);
            } catch (Exception ignored) { }
        }
        @Override public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) { }
    };

    void respondToPairing(String endpointId, boolean accept) {
        if (!pendingConnections.containsKey(endpointId)) return;
        if (accept) connections.acceptConnection(endpointId, payloadCallback);
        else connections.rejectConnection(endpointId);
    }

    void publishLocation(double lat, double lng, double heading, double speed, double accuracy) {
        if (mode == null || riderId == null) return;
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
            leave();
            listener.onStatus("Trip ended. Location sharing stopped.");
            return;
        }
        lastLat = lat; lastLng = lng; lastHeading = heading; lastSpeed = speed; lastAccuracy = accuracy;
        Map<String, Object> data = new HashMap<>();
        data.put("riderId", riderId);
        data.put("name", riderName);
        if (!riderPhone.isBlank()) data.put("phone", riderPhone);
        data.put("lat", lat);
        data.put("lng", lng);
        data.put("heading", heading);
        data.put("speed", speed);
        data.put("accuracy", accuracy);
        data.put("updatedAt", System.currentTimeMillis());
        data.put("safetyType", safetyType);
        data.put("safetyAlertId", safetyAlertId);
        data.put("acknowledgedAlertId", acknowledgedAlertId);
        if ("cloud".equals(mode)) {
            if (cloudLocations != null) cloudLocations.child(riderId).setValue(data);
        } else {
            try {
                JSONObject json = new JSONObject(data);
                String messageId = riderId + ":" + System.currentTimeMillis();
                json.put("messageId", messageId);
                alreadySeen(messageId);
                listener.onRiderLocation(json.toString());
                forwardNearby(json.toString(), null);
            } catch (Exception ignored) { }
        }
    }

    void setSafetyStatus(String type) {
        if (riderId == null) return;
        String clean = "breakdown".equals(type) ? "breakdown" : "sos";
        safetyType = clean;
        safetyAlertId = riderId + ":" + System.currentTimeMillis();
        acknowledgedAlertId = "";
        publishSafetySnapshot();
    }

    void acknowledgeSafety(String alertId) {
        if (riderId == null || alertId == null || alertId.isBlank()) return;
        acknowledgedAlertId = alertId;
        publishSafetySnapshot();
    }

    void clearSafetyStatus() {
        safetyType = "";
        safetyAlertId = "";
        publishSafetySnapshot();
    }

    private void publishSafetySnapshot() {
        if (Double.isNaN(lastLat)) listener.onStatus("Waiting for GPS before sending the safety update.");
        else publishLocation(lastLat, lastLng, lastHeading, lastSpeed, lastAccuracy);
    }

    void recordTripEnd(double odometerEnd) {
        if (cloudTrip == null || riderId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put("endedAt", ServerValue.TIMESTAMP);
        if (odometerEnd >= 0) update.put("odometerEnd", odometerEnd);
        cloudTrip.child("members").child(riderId).updateChildren(update);
    }

    private void forwardNearby(String json, String exceptEndpoint) {
        List<String> targets = new ArrayList<>();
        for (String endpoint : connectedEndpoints.keySet()) {
            if (!endpoint.equals(exceptEndpoint)) targets.add(endpoint);
        }
        if (!targets.isEmpty()) {
            connections.sendPayload(targets, Payload.fromBytes(json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private synchronized boolean alreadySeen(String id) {
        if (seenMessages.containsKey(id)) return true;
        seenMessages.put(id, true);
        if (seenMessages.size() > 300) seenMessages.remove(seenMessages.keySet().iterator().next());
        return false;
    }

    void leave() {
        if (cloudLocations != null && riderId != null) {
            if (cloudLocationListener != null) cloudLocations.removeEventListener(cloudLocationListener);
            cloudLocations.child(riderId).removeValue();
        }
        if (cloudTrip != null && cloudMetaListener != null) cloudTrip.child("meta").removeEventListener(cloudMetaListener);
        if (cloudTrip != null && cloudMembersListener != null) cloudTrip.child("members").removeEventListener(cloudMembersListener);
        if (cloudTrip != null && cloudStopsListener != null) cloudTrip.child("stops").removeEventListener(cloudStopsListener);
        cloudTrip = null;
        cloudLocations = null;
        cloudLocationListener = null;
        cloudMetaListener = null;
        cloudMembersListener = null;
        cloudStopsListener = null;
        connections.stopAdvertising();
        connections.stopDiscovery();
        connections.stopAllEndpoints();
        connectedEndpoints.clear();
        pendingConnections.clear();
        requestedEndpoints.clear();
        seenMessages.clear();
        mode = null;
        tripCode = null;
        riderId = null;
        expiresAt = 0;
        nearbyCreating = false;
        nearbyReady = false;
        organizer = false;
        joinLocked = false;
        tripExpiresAt = 0;
        membersJson = "[]";
        membersLoaded = false;
        riderPhone = "";
        requestedShareUntil = 0;
        odometerStart = -1;
        safetyType = "";
        safetyAlertId = "";
        acknowledgedAlertId = "";
        lastLat = Double.NaN;
        destinationTitle = null;
        destinationLat = 0;
        destinationLng = 0;
    }

    private String newCode() {
        StringBuilder value = new StringBuilder(6);
        for (int i = 0; i < 6; i++) value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return value.toString();
    }

    private String sanitizeName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("[\\r\\n|]", "");
        if (clean.isEmpty()) clean = "Rider";
        return clean.substring(0, Math.min(24, clean.length()));
    }

    private String sanitizeDestination(String value) {
        String clean = value == null ? "Destination" : value.trim().replaceAll("[\\r\\n|]", " ");
        if (clean.isEmpty()) clean = "Destination";
        return clean.substring(0, Math.min(28, clean.length()));
    }

    private String sanitizePhone(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^+0-9]", "");
        return clean.substring(0, Math.min(18, clean.length()));
    }
}
