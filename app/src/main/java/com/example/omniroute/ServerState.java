package com.example.omniroute;

import android.content.Context;
import android.content.SharedPreferences;

public final class ServerState {
    public static final String PREFS = "omniroute-server";
    public static final String KEY_STATUS = "status";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_PORT = "port";
    public static final String KEY_VERSION = "version";

    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_ERROR = "error";

    private ServerState() {}

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void record(Context context, String status, String message) {
        prefs(context).edit()
                .putString(KEY_STATUS, status)
                .putString(KEY_MESSAGE, message)
                .apply();
    }

    public static String status(Context context) {
        return prefs(context).getString(KEY_STATUS, STATUS_STOPPED);
    }

    public static String message(Context context) {
        return prefs(context).getString(KEY_MESSAGE, "");
    }
}
