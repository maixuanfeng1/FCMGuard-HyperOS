package com.reed.fcmguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public class GuardService extends Service {
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final int NOTIFICATION_ID = 426;
    private HandlerThread workerThread;
    private Handler handler;
    private static volatile boolean running;
    private static volatile boolean repairSucceeded;
    private static volatile boolean observerRegistered;
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> STATE_LISTENERS =
            new java.util.concurrent.CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(android.os.Looper.getMainLooper());

    public static void addStateListener(Runnable listener) { STATE_LISTENERS.add(listener); }
    public static void removeStateListener(Runnable listener) { STATE_LISTENERS.remove(listener); }
    private static void stateChanged() {
        MAIN.post(() -> { for (Runnable listener : STATE_LISTENERS) listener.run(); });
    }
    private ISettingsService subscription;
    private String observedKey;
    private final Runnable bridgeListener = () -> {
        repairSucceeded = false;
        observerRegistered = false;
        stateChanged();
        if (handler != null && !this.destroyed) {
            handler.removeCallbacks(this.monitorAndRepair);
            handler.post(this.monitorAndRepair);
        }
    };

    public static boolean isProtecting() {
        return running && observerRegistered && repairSucceeded && ShizukuBridge.isReady();
    }
    private volatile boolean foreground;
    private volatile boolean destroyed;

    private final Runnable monitorAndRepair = this::subscribeAndRepair;
    private final Runnable repairDebounced = this::repair;
    private final ISettingsObserver settingsObserver = new ISettingsObserver.Stub() {
        @Override public void onSettingChanged() {
            if (destroyed || handler == null) return;
            // This delay is only a debounce after a real provider event, never polling.
            handler.removeCallbacks(repairDebounced);
            handler.postDelayed(repairDebounced, 400L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("whitelist-guard");
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        applyExecutionMode();
        running = true;
        ShizukuBridge.addListener(bridgeListener);
        ShizukuBridge.connect();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && "com.reed.fcmguard.STOP".equals(intent.getAction()))
                || !SettingsGuard.isProtectionEnabled(this)) {
            SettingsGuard.setProtectionEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(monitorAndRepair);
        handler.post(monitorAndRepair);
        return START_STICKY;
    }

    private void repair() {
        if (destroyed || !SettingsGuard.isProtectionEnabled(this) || !observerRegistered) return;
        SettingsGuard.Result result = SettingsGuard.repair(this);
        if (destroyed || !SettingsGuard.isProtectionEnabled(this)) return;
        repairSucceeded = result.success;
        stateChanged();
        if (result.changed) FcmReconnect.kick(this);
        if (foreground) refreshNotification(result.success
                ? getString(R.string.notification_observing) : result.message);
    }

    private void subscribeAndRepair() {
        if (destroyed || !SettingsGuard.isProtectionEnabled(this)) return;
        String key = SettingsGuard.getConfiguredKey(this);
        if (!ShizukuBridge.isReady()) {
            clearSubscription();
            repairSucceeded = false;
            stateChanged();
            refreshNotification(getString(ShizukuBridge.statusText()));
            return;
        }
        try {
            if (!observerRegistered || !key.equals(observedKey)
                    || !ShizukuBridge.isCurrentService(subscription)) {
                clearSubscription();
                ISettingsService next = ShizukuBridge.observe(key, settingsObserver);
                if (destroyed || !SettingsGuard.isProtectionEnabled(this)) {
                    ShizukuBridge.stopObserving(next, settingsObserver);
                    return;
                }
                subscription = next;
                observedKey = key;
                observerRegistered = true;
            }
            // Register first, then read/repair once. Subsequent work requires an event.
            repair();
        } catch (Exception e) {
            clearSubscription();
            repairSucceeded = false;
            stateChanged();
            refreshNotification(getString(R.string.observer_unavailable));
            android.util.Log.w("FCMGuard", "Whitelist observation unavailable", e);
        }
    }

    private void clearSubscription() {
        observerRegistered = false;
        observedKey = null;
        ISettingsService old = subscription;
        subscription = null;
        ShizukuBridge.stopObserving(old, settingsObserver);
    }

    private void applyExecutionMode() {
        ensureNotificationChannel(this);
        Notification notification = buildNotification(getString(R.string.notification_waiting));
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    /**
     * Creates the visible-but-silent foreground-service channel. Returns true only
     * when this call created the channel for the first time.
     */
    public static boolean ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        boolean created = nm.getNotificationChannel(CHANNEL_ID) == null;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(channel);
        return created;
    }

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (!nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        repairSucceeded = false;
        observerRegistered = false;
        stateChanged();
        ShizukuBridge.removeListener(bridgeListener);
        handler.removeCallbacksAndMessages(null);
        handler.post(this::clearSubscription);
        workerThread.quitSafely();
        foreground = false;
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void refreshNotification(String text) {
        if (!foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, GuardService.class).setAction("com.reed.fcmguard.STOP"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, getString(R.string.stop_protection), stop).build());
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
