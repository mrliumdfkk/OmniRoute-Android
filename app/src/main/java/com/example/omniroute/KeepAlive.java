package com.example.omniroute;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Cross-process watchdog for the OmniRoute gateway.
 *
 * <p>The in-process supervisor inside {@link OmniRouteService} can only fix a
 * crash while the app process is still alive. Everything that kills the
 * process itself — the user swiping the task out of recents, the low-memory
 * killer, an OEM task cleaner, or a reboot — leaves nothing behind to bring
 * the gateway back. This class supplies the missing half: a repeating alarm
 * that the system holds outside the app process, so a dead process can still
 * be restarted.
 *
 * <p>The alarm targets {@link KeepAliveReceiver}, which does nothing but ask
 * the system to start the foreground service again. A service that is already
 * running is a no-op: {@link OmniRouteService#onStartCommand} returns
 * {@code START_STICKY} and the existing worker is reused.
 *
 * <p>Deliberately not implemented:
 * <ul>
 *   <li>{@code JobScheduler} — a job is deferred under battery pressure and can
 *       be delayed by hours, which defeats the purpose.</li>
 *   <li>{@code WorkManager} — same reason, plus it needs a dependency this
 *       project does not have.</li>
 *   <li>Exact alarms — {@code SCHEDULE_EXACT_ALARM} is a special permission
 *       that Play restricts, and the watchdog only needs to run "every so
 *       often", not at an exact second.</li>
 * </ul>
 *
 * <p>The interval is intentionally generous. An alarm that fires too often is
 * both a battery cost and a good reason for the system to drop the app from
 * its whitelist; one wake-up every fifteen minutes is enough to bring a
 * gateway back within a quarter of an hour while staying cheap.
 */
final class KeepAlive {

    private static final String TAG = "OmniRouteKeepAlive";

    /** Request code for the alarm's PendingIntent; stable so it replaces itself. */
    private static final int REQUEST_CODE = 0x0A1E;

    /**
     * How often the watchdog fires, in milliseconds.
     *
     * <p>{@code AlarmManager} clamps inexact alarms to a minimum of one
     * minute, and the system may stretch the interval further under Doze.
     * Fifteen minutes is a compromise between recovery latency and the
     * battery cost of a periodic wake-up.
     */
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    /**
     * Whether the user asked for the watchdog.
     *
     * <p>Separate from {@link AppSettings#restartOnCrash(Context)} on purpose:
     * that switch governs what happens while the process is alive, this one
     * governs whether the process is brought back after it dies. Keeping them
     * apart lets a user accept either behaviour without the other.
     */
    private static final String PREFS = "omniroute-keepalive";
    private static final String KEY_ENABLED = "watchdog_enabled";

    /**
     * Whether the exact-alarm path is still worth trying.
     *
     * <p>Set to false once the system refuses an exact alarm, so the app
     * stops retrying on every arm and settles for the inexact repeater. It is
     * deliberately not persisted: the user can grant the permission at any
     * time, and a fresh process should try the better path again.
     */
    private static boolean exactAlarmsAllowed = true;

    private KeepAlive() {}

    /**
     * Whether the cross-process watchdog is enabled.
     *
     * <p>Defaults to on: the whole point of the feature is to cover the cases
     * the user cannot see, and a watchdog that is off by default protects
     * nobody.
     */
    static boolean isEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean value) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, value)
                .apply();
    }

    /**
     * Starts or stops the watchdog to match {@link #isEnabled(Context)}.
     *
     * <p>Safe to call repeatedly; the alarm is set with the same request code
     * each time, so a second call replaces the first rather than stacking a
     * second alarm.
     */
    static void sync(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            Log.w(TAG, "no AlarmManager; cross-process watchdog unavailable");
            return;
        }
        PendingIntent operation = pendingIntent(app);
        if (isEnabled(app)) {
            arm(manager, operation);
        } else {
            manager.cancel(operation);
            Log.i(TAG, "cross-process watchdog disabled");
        }
    }

    /**
     * Schedules the next watchdog alarm.
     *
     * <p>{@code setAlarmClock} is used rather than the plain inexact repeater
     * for two reasons:
     * <ul>
     *   <li>It is the only alarm type the system still delivers while the
     *       device is in Doze. An inexact alarm is deferred until the device
     *       leaves Doze, which on a phone that sits idle overnight means the
     *       gateway comes back only when the user picks the phone up.</li>
     *   <li>An alarm-clock-class alarm is one of the documented exemptions
     *       from the Android 12+ rule that blocks background foreground
     *       service starts, so {@link KeepAliveReceiver} is actually allowed
     *       to bring the service back.</li>
     * </ul>
     *
     * <p>Two costs worth knowing about:
     * <ul>
     *   <li>The alarm shows up in the status bar. The service already runs a
     *       persistent notification, so this adds no new surface.</li>
     *   <li>{@code setAlarmClock} schedules a <em>single</em> alarm, not a
     *       repeating one — there is no repeating variant of it. The watchdog
     *       therefore has to re-arm itself after every firing, which
     *       {@link KeepAliveReceiver} does by calling {@link #sync(Context)}
     *       once it is done.</li>
     * </ul>
     */
    private static void arm(AlarmManager manager, PendingIntent operation) {
        long triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS;
        if (exactAlarmsAllowed) {
            try {
                manager.setAlarmClock(
                        new AlarmManager.AlarmClockInfo(triggerAt, operation), operation);
                Log.i(TAG, "cross-process watchdog armed, interval " + INTERVAL_MS + "ms");
                return;
            } catch (SecurityException denied) {
                // setAlarmClock needs SCHEDULE_EXACT_ALARM on Android 12+ when
                // the app targets 31 or later. The user can grant it later, so
                // the flag is only remembered for this process; a restart
                // tries the exact path again.
                Log.w(TAG, "exact alarm denied; falling back to an inexact repeater", denied);
                exactAlarmsAllowed = false;
            }
        }
        try {
            // The inexact repeater does repeat on its own, but re-arming it on
            // every firing is harmless and keeps the two paths consistent.
            manager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    INTERVAL_MS,
                    operation);
            Log.i(TAG, "cross-process watchdog armed with an inexact alarm");
        } catch (Exception failure) {
            Log.w(TAG, "could not arm the watchdog", failure);
        }
    }

    /**
     * Cancels the watchdog.
     *
     * <p>Called when the user explicitly stops the gateway, so a deliberate
     * stop is not immediately undone by the watchdog.
     */
    static void cancel(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        manager.cancel(pendingIntent(app));
        Log.i(TAG, "cross-process watchdog cancelled");
    }

    /**
     * Asks the system to deliver the watchdog alarm now.
     *
     * <p>Used by the task-removed path: the process is about to be torn down,
     * and a pending alarm alone might sit in the doze bucket for a long time.
     * A one-shot alarm fires soon after the process dies and pulls the service
     * back up.
     */
    static void triggerNow(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent operation = pendingIntent(app);
        long triggerAt = SystemClock.elapsedRealtime() + 2000L;
        if (exactAlarmsAllowed) {
            try {
                // setAlarmClock rather than set(): the task-removed path is
                // exactly the case where the process is about to die, and a
                // plain alarm would be dropped along with it.
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, operation), operation);
                return;
            } catch (SecurityException denied) {
                exactAlarmsAllowed = false;
            }
        }
        try {
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
        } catch (Exception failure) {
            Log.w(TAG, "could not schedule the immediate watchdog trigger", failure);
        }
    }

    /**
     * Builds the PendingIntent the alarm delivers.
     *
     * <p>{@code FLAG_UPDATE_CURRENT} keeps the extras fresh, and
     * {@code FLAG_IMMUTABLE} is required from Android 12 onwards. The intent
     * is explicit so the alarm cannot be redirected to another component.
     */
    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class)
                .setAction(KeepAliveReceiver.ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}