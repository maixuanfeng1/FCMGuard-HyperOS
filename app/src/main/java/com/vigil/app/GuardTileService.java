package com.vigil.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Fresh GMS verdict while visible; an unknown state remains an accessible diagnostics entry. */
public final class GuardTileService extends TileService {
    private boolean listening;
    private final Object connectionSurface = new Object();
    private final Runnable listener = this::render;
    @Override public void onTileAdded() {
        super.onTileAdded();
        DiagnosticPreferences.setQuickSettingsTileAdded(this, true);
    }
    @Override public void onTileRemoved() {
        super.onTileRemoved();
        DiagnosticPreferences.setQuickSettingsTileAdded(this, false);
    }
    @Override public void onStartListening() {
        super.onStartListening(); listening = true;
        // Existing tiles may start listening without a new onTileAdded callback after upgrade.
        if (!DiagnosticPreferences.isQuickSettingsTileAdded(this)) {
            DiagnosticPreferences.setQuickSettingsTileAdded(this, true);
        }
        android.util.Log.i("FCMGuardTile", "Visible; diagnostics-only; no authorization required; pid=" + android.os.Process.myPid());
        PushRuntime.add(listener);
        // Publish the click PendingIntent before starting any diagnostic work.
        render();
        startDiagnostics(this, connectionSurface);
    }
    static void startDiagnostics(Context context, Object surface) {
        if (DirectGmsAccess.isReady(context)) {
            DiagnosticPreferences.retireUnneededShizuku(context);
        } else if (DiagnosticPreferences.enhanced(context)) {
            ShizukuBridge.connect();
        }
        PushRuntime.start(context);
        EnhancedRuntime.startRefreshing(context, surface);
    }
    @Override public void onStopListening() {
        // Do not leave a cached lit verdict in SystemUI while this service cannot observe changes.
        if (getQsTile() != null) {
            applyState(this, getQsTile(), new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
                    EnhancedStatus.Result.STALE, 0, 0), SystemClock.elapsedRealtime());
            getQsTile().updateTile();
        }
        listening = false;
        EnhancedRuntime.stopRefreshing(connectionSurface);
        PushRuntime.remove(listener);
        super.onStopListening();
    }
    @Override public void onDestroy() {
        listening = false;
        EnhancedRuntime.stopRefreshing(connectionSurface);
        PushRuntime.remove(listener);
        super.onDestroy();
    }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::recover); else recover();
    }
    private void recover() {
        Intent intent = recoveryIntent();
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 428, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else openLegacy(intent);
    }
    private Intent recoveryIntent() {
        return new Intent(this, MainActivity.class).putExtra("open_diagnostics", true)
                .putExtra("request_recovery", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openLegacy(Intent intent) { startActivityAndCollapse(intent); }
    private void render() {
        if (!listening || getQsTile() == null) return;
        Tile tile = getQsTile();
        applyState(this, tile, EnhancedRuntime.snapshot(this), SystemClock.elapsedRealtime());
        if (Build.VERSION.SDK_INT >= 34) {
            tile.setActivityLaunchForClick(PendingIntent.getActivity(this, 428, recoveryIntent(),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        tile.updateTile();
    }
    static void applyState(Context c, Tile tile, EnhancedStatus.Snapshot status, long now) {
        EnhancedStatus.Result result = status.currentResult(now);
        boolean connected = result == EnhancedStatus.Result.CONNECTED;
        boolean disconnected = result == EnhancedStatus.Result.DISCONNECTED;
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        int label = connected ? R.string.tile_connected : disconnected ? R.string.tile_disconnected : R.string.tile_unknown;
        int description = connected ? R.string.gms_connected : disconnected ? R.string.gms_disconnected : R.string.tile_unknown_note;
        tile.setLabel(c.getString(label));
        tile.setIcon(Icon.createWithResource(c, connected ? R.drawable.ic_guard_tile :
                disconnected ? R.drawable.ic_connection_off : R.drawable.ic_connection_unknown));
        tile.setContentDescription(c.getString(description));
        int subtitle = connected || disconnected ? R.string.tile_recent_check :
                result == EnhancedStatus.Result.CHECKING ? R.string.checking :
                status.access == EnhancedStatus.Access.CONNECTING ? R.string.shizuku_connecting :
                status.access == EnhancedStatus.Access.NOT_RUNNING ? R.string.tile_shizuku_not_running :
                status.access == EnhancedStatus.Access.DISABLED || status.access == EnhancedStatus.Access.NOT_AUTHORIZED ?
                R.string.tile_requires_shizuku :
                result == EnhancedStatus.Result.STALE ? R.string.tile_stale : R.string.tile_unknown_note;
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(c.getString(subtitle));
        if (Build.VERSION.SDK_INT >= 30) tile.setStateDescription(c.getString(description));
    }
}
