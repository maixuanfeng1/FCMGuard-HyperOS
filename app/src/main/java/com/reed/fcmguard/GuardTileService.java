package com.reed.fcmguard;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A non-active tile: Android binds it when visible, providing an opportunity to resume. */
public final class GuardTileService extends TileService {
    private boolean listening;
    private final Runnable listener = this::render;

    @Override public void onStartListening() {
        super.onStartListening();
        listening = true;
        GuardRuntime.addStateListener(listener);
        ShizukuBridge.addListener(listener);
        android.util.Log.i("FCMGuardTile", "Visible; tileWakeup=" + SettingsGuard.usesTileWakeup(this)
                + " enabled=" + SettingsGuard.isProtectionEnabled(this));
        GuardController.restoreFromTile(this);
        render();
    }

    @Override public void onStopListening() {
        listening = false;
        GuardRuntime.removeStateListener(listener);
        ShizukuBridge.removeListener(listener);
        super.onStopListening();
    }

    @Override public void onDestroy() {
        listening = false;
        GuardRuntime.removeStateListener(listener);
        ShizukuBridge.removeListener(listener);
        // The process observer may continue while Android retains this process.
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::toggle);
        else toggle();
    }

    private void toggle() {
        if (GuardRuntime.isProtecting()) {
            GuardController.stop(this);
        } else {
            boolean needsAttention = !ShizukuBridge.isReady() || GuardRuntime.protectionError() != null;
            SettingsGuard.setProtectionEnabled(this, true);
            try { GuardController.start(this); }
            catch (RuntimeException e) { needsAttention = true; }
            if (needsAttention) openDashboard();
        }
        render();
    }

    private void render() {
        if (!listening) return;
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean active = GuardRuntime.isProtecting();
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        int detail = active ? R.string.tile_listening
                : !SettingsGuard.isProtectionEnabled(this) ? R.string.tile_off
                : !ShizukuBridge.isReady() ? R.string.tile_needs_shizuku
                : GuardRuntime.protectionError() != null ? R.string.status_attention
                : R.string.tile_tap_to_resume;
        tile.setContentDescription(getString(R.string.app_name) + ", " + getString(detail));
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(detail));
        tile.updateTile();
    }

    private void openDashboard() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 428, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else openDashboardLegacy(intent);
    }

    /** PendingIntent overload does not exist below Android 14. */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openDashboardLegacy(Intent intent) { startActivityAndCollapse(intent); }
}
