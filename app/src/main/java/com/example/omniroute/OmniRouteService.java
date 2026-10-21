package com.example.omniroute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public final class OmniRouteService extends Service {
    private static final String TAG = "OmniRouteService";
    private static final String CHANNEL_ID = "omniroute-server";
    private static final int NOTIFICATION_ID = 20128;
    static final String ACTION_RESTART = "com.example.omniroute.action.RESTART";

    private OmniRouteServer server;
    private Thread worker;
    private Thread supervisor;
    private volatile boolean supervising;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting OmniRoute…"));
        server = new OmniRouteServer(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean restart = intent != null && ACTION_RESTART.equals(intent.getAction());
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(() -> runServer(restart), "omniroute-server");
            worker.start();
        } else if (restart) {
            Thread restarter = new Thread(() -> {
                try {
                    server.restart();
                    updateNotification("OmniRoute running on port " + AppSettings.port(this));
                } catch (Exception exception) {
                    Log.e(TAG, "OmniRoute restart failed", exception);
                    ServerState.record(this, ServerState.STATUS_ERROR, String.valueOf(exception.getMessage()));
                }
            }, "omniroute-restart");
            restarter.start();
        }
        if (supervisor == null || !supervisor.isAlive()) {
            supervising = true;
            supervisor = new Thread(this::supervise, "omniroute-supervisor");
            supervisor.start();
        }
        // Arm the cross-process watchdog on every start. The service can be
        // started by the launcher, by the boot receiver, or by the watchdog
        // itself, and in all three cases the alarm has to be running for the
        // next process death to be recoverable.
        KeepAlive.sync(this);
        return START_STICKY;
    }

    /**
     * Brings the gateway back after the user swipes the task out of recents.
     *
     * <p>Removing the task does not stop a foreground service on its own, but
     * many OEM builds and some Android versions tear the whole process down
     * anyway, and without this the gateway silently disappears with no
     * notification and no way back except launching the app by hand.
     *
     * <p>Two mechanisms are used because either one alone has a hole:
     * <ul>
     *   <li>Starting the service again right away works while the process is
     *       still alive, which is the common case.</li>
     *   <li>The alarm covers the case where the process dies before the
     *       service start lands, since the alarm is held by the system and
     *       fires regardless of this process's fate.</li>
     * </ul>
     *
     * <p>Both are best-effort: Android 12+ blocks background foreground-service
     * starts for types outside the exemption list, so the alarm is the
     * dependable half and the direct start is the fast half.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "task removed; asking the system to bring the gateway back");
        try {
            Intent service = new Intent(this, OmniRouteService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception failure) {
            Log.w(TAG, "could not restart the service after task removal", failure);
        }
        // The alarm is the part that survives this process being killed.
        KeepAlive.triggerNow(this);
    }

    private void supervise() {
        while (supervising) {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (server == null || server.isRunning()) {
                continue;
            }
            // The "restart after a crash" switch governs exactly this case.
            // It used to be written to the settings file and then never read
            // back, so the toggle did nothing at all.
            if (!AppSettings.restartOnCrash(this)) {
                continue;
            }
            try {
                updateNotification("Restarting OmniRoute…");
                server.start();
                updateNotification("OmniRoute running on port " + AppSettings.port(this));
            } catch (Exception exception) {
                Log.e(TAG, "OmniRoute restart failed", exception);
                ServerState.record(this, ServerState.STATUS_ERROR, String.valueOf(exception.getMessage()));
            }
        }
    }

    private void runServer(boolean restart) {
        try {
            if (restart) {
                server.restart();
            } else {
                server.start();
            }
            updateNotification("OmniRoute running on port " + AppSettings.port(this));
        } catch (Exception exception) {
            Log.e(TAG, "OmniRoute failed to start", exception);
            ServerState.record(this, ServerState.STATUS_ERROR, String.valueOf(exception.getMessage()));
            updateNotification("OmniRoute failed: " + exception.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        supervising = false;
        if (supervisor != null) {
            supervisor.interrupt();
        }
        if (server != null) {
            server.stop();
        }
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OmniRoute server",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the local OmniRoute gateway alive");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("OmniRoute")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
