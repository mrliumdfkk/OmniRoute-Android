package com.example.omniroute;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receives the watchdog alarm and brings the gateway back up.
 *
 * <p>Unlike {@link BootReceiver} this receiver runs in a live process, so it
 * is allowed to start a foreground service. Its only job is to ask the system
 * for the service; whether that actually spawns anything is the service's
 * business.
 */
public final class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "OmniRouteWatchdog";

    /** Action the watchdog alarm carries. */
    public static final String ACTION_WATCHDOG =
            "com.example.omniroute.action.WATCHDOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_WATCHDOG.equals(intent.getAction())) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!KeepAlive.isEnabled(app)) {
            Log.i(TAG, "watchdog disabled; not restarting the service");
            return;
        }
        // The watchdog is gated only by its own switch, not by the
        // "auto-start at boot" preference. Those are separate questions: the
        // boot setting controls what happens once at boot, while the watchdog
        // controls what happens after the process dies. Letting one silently
        // disable the other would leave a switch that appears enabled but
        // does nothing.
        Log.i(TAG, "watchdog fired; requesting the service");
        try {
            Intent service = new Intent(app, OmniRouteService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(service);
            } else {
                app.startService(service);
            }
        } catch (Exception failure) {
            // Android 12+ refuses foreground-service starts from the
            // background for types that are not on the exemption list, and
            // several OEM builds add their own restrictions on top. Record the
            // reason so the settings screen can explain what happened instead
            // of the gateway simply appearing dead.
            Log.w(TAG, "could not restart the service from the watchdog", failure);
            ServerState.record(app, ServerState.STATUS_STOPPED,
                    "watchdog could not restart the service: " + failure.getMessage());
        } finally {
            // The alarm is a one-shot, not a repeating one: setAlarmClock has
            // no repeating variant. Re-arming it here is what turns the
            // watchdog into an actual heartbeat — without this line it would
            // fire exactly once and then never again.
            KeepAlive.sync(app);
        }
    }
}
