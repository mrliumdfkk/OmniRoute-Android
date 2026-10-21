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
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
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
        } catch (Exception exception) {
            // Android 12+ blocks foreground-service starts from BOOT_COMPLETED
            // for non-whitelisted types, so record the failure and let the next
            // app launch start the gateway instead of crashing the receiver.
            Log.w(TAG, "Could not auto-start OmniRoute at boot", exception);
            ServerState.record(context, ServerState.STATUS_STOPPED, "boot auto-start blocked");
        }
    }
}
