package com.example.omniroute;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "OmniRouteBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // The device has booted but the credential-encrypted storage is
            // still locked, so the app's own data directory is unreadable and
            // neither the settings nor the installed rootfs can be reached.
            // Starting the service here would fail in a confusing way, so
            // only the "did we get configured at all" flag is worth reading —
            // and even that lives in credential-encrypted storage.
            //
            // The real work happens on BOOT_COMPLETED, which arrives right
            // after the user unlocks the device. This branch exists so the
            // broadcast is not treated as unknown, and so the log explains
            // why nothing happened yet.
            Log.i(TAG, "locked boot completed; waiting for the device to be unlocked");
            return;
        }

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // After an update the process is killed, so the watchdog alarm
            // registered by the previous install is gone with it. Re-arm it,
            // otherwise an update silently disables the cross-process
            // watchdog until the user next opens the app.
            Log.i(TAG, "package replaced; re-arming the watchdog");
            KeepAlive.sync(context);
            return;
        }

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        // The user may have turned auto-start off; the receiver is deliberately
        // kept enabled so toggling it back on does not need a manifest change.
        if (!AppSettings.startOnBoot(context)) {
            Log.i(TAG, "Boot auto-start disabled in settings; skipping");
            return;
        }
        Intent service = new Intent(context, OmniRouteService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            Log.i(TAG, "auto-started OmniRoute at boot");
        } catch (Exception exception) {
            // Android 12+ blocks foreground-service starts from BOOT_COMPLETED
            // for non-whitelisted types, so record the failure and let the next
            // app launch start the gateway instead of crashing the receiver.
            Log.w(TAG, "Could not auto-start OmniRoute at boot", exception);
            ServerState.record(context, ServerState.STATUS_STOPPED, "boot auto-start blocked");
        }
    }
}
