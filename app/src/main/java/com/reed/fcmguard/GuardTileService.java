package com.reed.fcmguard;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A recovery entry point, never a continuous-connection indicator. */
public final class GuardTileService extends TileService {
    private boolean listening;
    private final Runnable listener = this::render;
    @Override public void onStartListening() {
        super.onStartListening(); listening = true;
        PushRuntime.add(listener); PushRuntime.start(this); PushRuntime.check(this, false);
        android.util.Log.i("FCMGuardTile", "Visible; diagnostics-only; no authorization required");
        render();
    }
    @Override public void onStopListening() { listening = false; PushRuntime.remove(listener); super.onStopListening(); }
    @Override public void onDestroy() { PushRuntime.remove(listener); super.onDestroy(); }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::recover); else recover();
    }
    private void recover() {
        PushRuntime.check(this, true);
        Intent intent = new Intent(this, MainActivity.class).putExtra("open_diagnostics", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 428, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else openLegacy(intent);
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openLegacy(Intent intent) { startActivityAndCollapse(intent); }
    private void render() {
        if (!listening || getQsTile() == null) return;
        Tile tile = getQsTile();
        // Neither a socket nor a successful network probe proves Diagnostics Connected.
        tile.setState(Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        tile.setContentDescription(getString(R.string.no_timer));
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(getString(PushRuntime.busy() ? R.string.checking : R.string.no_timer));
        tile.updateTile();
    }
}
