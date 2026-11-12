package com.reed.fcmguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import java.util.concurrent.CopyOnWriteArraySet;
import rikka.shizuku.Shizuku;

/** Owns the authorized helper binding for the lifetime of the application process. */
public final class ShizukuBridge {
    private static final int REQUEST_CODE = 426;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile ISettingsService service;
    private static Shizuku.UserServiceArgs args;
    private static Context app;
    private static boolean binding;
    private static boolean initialized;
    private static boolean automaticRebindUsed;
    private static final Runnable BIND_TIMEOUT = () -> {
        binding = false;
        changed();
    };

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            MAIN.post(() -> {
                MAIN.removeCallbacks(BIND_TIMEOUT);
                binding = false;
                if (binder != null && binder.pingBinder()) {
                    service = ISettingsService.Stub.asInterface(binder);
                    try { binder.linkToDeath(() -> MAIN.post(() -> helperDisconnected(binder)), 0); }
                    catch (RemoteException e) { service = null; }
                }
                changed();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            MAIN.post(() -> {
                ISettingsService current = service;
                if (current != null && !current.asBinder().pingBinder()) helperDisconnected(current.asBinder());
            });
        }
    };

    public static void initialize(Context context) {
        if (initialized) return;
        initialized = true;
        app = context.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(context, SettingsUserService.class))
                .daemon(false).processNameSuffix("settings").debuggable(false)
                .version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListenerSticky(() -> MAIN.post(() -> {
            automaticRebindUsed = false;
            connect();
        }));
        Shizuku.addBinderDeadListener(() -> MAIN.post(ShizukuBridge::disconnected));
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == REQUEST_CODE) MAIN.post(() -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) connect();
                changed();
            });
        });
    }

    public static void addListener(Runnable listener) { LISTENERS.add(listener); }
    public static void removeListener(Runnable listener) { LISTENERS.remove(listener); }
    private static void changed() { for (Runnable listener : LISTENERS) listener.run(); }

    private static void disconnected() {
        service = null;
        binding = false;
        MAIN.removeCallbacks(BIND_TIMEOUT);
        changed();
    }

    private static void helperDisconnected(IBinder expectedBinder) {
        ISettingsService current = service;
        // Death callbacks from a superseded helper must not clear a newer binding.
        if (current == null || current.asBinder() != expectedBinder) return;
        disconnected();
        if (!automaticRebindUsed && isAuthorized()) {
            automaticRebindUsed = true;
            connect();
        }
    }

    public static boolean isRunning() {
        try { return Shizuku.pingBinder(); } catch (RuntimeException e) { return false; }
    }

    public static boolean isAuthorized() {
        try {
            return isRunning() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) { return false; }
    }

    public static boolean isReady() {
        ISettingsService current = service;
        return isAuthorized() && current != null && current.asBinder().pingBinder();
    }

    static IBinder sessionBinder() {
        ISettingsService current = service;
        return current == null ? null : current.asBinder();
    }

    static EnhancedStatus.Access accessState(Context context) {
        if (!DiagnosticPreferences.enhanced(context)) return EnhancedStatus.Access.DISABLED;
        if (!isRunning()) return EnhancedStatus.Access.NOT_RUNNING;
        if (!isAuthorized()) return EnhancedStatus.Access.NOT_AUTHORIZED;
        return isReady() ? EnhancedStatus.Access.READY : EnhancedStatus.Access.CONNECTING;
    }

    public static void connect() {
        if (app == null || !DiagnosticPreferences.enhanced(app) || !isAuthorized() || isReady() || binding) return;
        binding = true;
        try {
            Shizuku.bindUserService(args, CONNECTION);
            MAIN.postDelayed(BIND_TIMEOUT, 10000L);
        } catch (RuntimeException e) {
            binding = false;
            changed();
        }
    }

    public static void requestPermission() {
        automaticRebindUsed = false;
        if (isAuthorized()) { connect(); return; }
        if (isRunning()) Shizuku.requestPermission(REQUEST_CODE);
    }

    public static int statusText() {
        if (isReady()) return R.string.shizuku_ready;
        if (!isRunning()) return R.string.shizuku_not_running;
        return isAuthorized() ? R.string.shizuku_connecting : R.string.shizuku_permission_missing;
    }

    public static void disable() {
        try { Shizuku.unbindUserService(args, CONNECTION, true); } catch (RuntimeException ignored) { }
        disconnected();
    }
    public static String readWhitelist() throws RemoteException {
        ISettingsService current = service;
        if (!isReady() || current == null) throw new IllegalStateException("Shizuku unavailable");
        return current.readWhitelist(Process.myUid() / 100000);
    }
    public static String socketEvidence() throws RemoteException {
        ISettingsService current = service;
        if (!isReady() || current == null) return "unavailable";
        return current.fcmSocketEvidence(Process.myUid() / 100000);
    }
    public static int runtimeBitness() throws RemoteException {
        ISettingsService current = service;
        if (!isReady() || current == null) return 0;
        return current.runtimeBitness();
    }
}
