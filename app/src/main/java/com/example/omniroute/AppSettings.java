package com.example.omniroute;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted user preferences.
 *
 * <p>Everything the server needs to start is read from here rather than from
 * constants, so a change takes effect on the next restart without rebuilding.
 * Values are validated on read: a corrupted or out-of-range entry must not
 * prevent the server from starting.
 */
final class AppSettings {

    private static final String FILE = "omniroute-settings";

    private static final String KEY_PORT = "port";
    private static final String KEY_LISTEN_ALL = "listen_all";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_RESTART_ON_CRASH = "restart_on_crash";
    private static final String KEY_HEAP_MB = "heap_mb";
    private static final String KEY_DESKTOP_MODE = "desktop_mode";
    private static final String KEY_WEB_FONT_SCALE = "web_font_scale";
    private static final String KEY_BALL_X = "ball_x";
    private static final String KEY_BALL_Y = "ball_y";
    private static final String KEY_SHOW_BALL = "show_ball";
    private static final String KEY_BALL_SIZE_DP = "ball_size_dp";

    /** Ports below 1024 need root, and some are reserved by the system. */
    static final int MIN_PORT = 1024;
    static final int MAX_PORT = 65535;
    static final int DEFAULT_PORT = 20128;
    /** Localhost only by default: the dashboard has no TLS and weak auth. */
    static final boolean DEFAULT_LISTEN_ALL = false;
    static final boolean DEFAULT_START_ON_BOOT = true;
    static final boolean DEFAULT_RESTART_ON_CRASH = true;
    /**
     * V8 old-space cap. Node defaults to roughly half of physical RAM, which on
     * a phone invites the low-memory killer; 768MB keeps a light dashboard
     * comfortable while leaving the rest for the system.
     */
    static final int DEFAULT_HEAP_MB = 768;
    static final int MIN_HEAP_MB = 256;
    static final int MAX_HEAP_MB = 4096;

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int port(Context context) {
        int value = prefs(context).getInt(KEY_PORT, DEFAULT_PORT);
        return value >= MIN_PORT && value <= MAX_PORT ? value : DEFAULT_PORT;
    }

    static void setPort(Context context, int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        prefs(context).edit().putInt(KEY_PORT, port).apply();
    }

    static boolean listenAll(Context context) {
        return prefs(context).getBoolean(KEY_LISTEN_ALL, DEFAULT_LISTEN_ALL);
    }

    static void setListenAll(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_LISTEN_ALL, value).apply();
    }

    static boolean startOnBoot(Context context) {
        return prefs(context).getBoolean(KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT);
    }

    static void setStartOnBoot(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, value).apply();
    }

    static boolean restartOnCrash(Context context) {
        return prefs(context).getBoolean(KEY_RESTART_ON_CRASH, DEFAULT_RESTART_ON_CRASH);
    }

    static void setRestartOnCrash(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_RESTART_ON_CRASH, value).apply();
    }

    static int heapMb(Context context) {
        int value = prefs(context).getInt(KEY_HEAP_MB, DEFAULT_HEAP_MB);
        return value >= MIN_HEAP_MB && value <= MAX_HEAP_MB ? value : DEFAULT_HEAP_MB;
    }

    static void setHeapMb(Context context, int megabytes) {
        if (megabytes < MIN_HEAP_MB || megabytes > MAX_HEAP_MB) {
            throw new IllegalArgumentException("heap out of range: " + megabytes);
        }
        prefs(context).edit().putInt(KEY_HEAP_MB, megabytes).apply();
    }

    /** Address the dashboard should be reached at, matching the bind mode. */
    static String dashboardHost(Context context) {
        return listenAll(context) ? "127.0.0.1" : "127.0.0.1";
    }

    static String dashboardUrl(Context context) {
        return "http://127.0.0.1:" + port(context);
    }

    /**
     * Render the dashboard with a desktop viewport and UA.
     *
     * <p>The dashboard's layout switches to a cramped mobile arrangement below
     * 1024px CSS width, so this is what selects the roomy sidebar layout. On by
     * default because the mobile layout is the thing users complain about.
     */
    static boolean desktopMode(Context context) {
        return prefs(context).getBoolean(KEY_DESKTOP_MODE, true);
    }

    static void setDesktopMode(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_DESKTOP_MODE, value).apply();
    }

    /**
     * Text zoom percentage for the WebView, 50-200.
     *
     * <p>Pinning this to a value the user controls also stops OEM font-size
     * settings from inflating parts of the page and desynchronising it from
     * Tailwind's rem scale.
     */
    static final int MIN_FONT_SCALE = 50;
    static final int MAX_FONT_SCALE = 200;
    static final int DEFAULT_FONT_SCALE = 100;

    /** Floating ball diameter in dp. Smaller keeps it unobtrusive, larger is
     *  easier to hit on a big screen. */
    static final int MIN_BALL_SIZE_DP = 32;
    static final int MAX_BALL_SIZE_DP = 80;
    static final int DEFAULT_BALL_SIZE_DP = 44;

    static int webFontScale(Context context) {
        int value = prefs(context).getInt(KEY_WEB_FONT_SCALE, DEFAULT_FONT_SCALE);
        return value >= MIN_FONT_SCALE && value <= MAX_FONT_SCALE ? value : DEFAULT_FONT_SCALE;
    }

    static void setWebFontScale(Context context, int percent) {
        if (percent < MIN_FONT_SCALE || percent > MAX_FONT_SCALE) {
            throw new IllegalArgumentException("font scale out of range: " + percent);
        }
        prefs(context).edit().putInt(KEY_WEB_FONT_SCALE, percent).apply();
    }

    /** Sentinel meaning "no saved position"; the ball then uses its default corner. */
    static final float BALL_POSITION_UNSET = -1f;

    static float ballX(Context context) {
        return prefs(context).getFloat(KEY_BALL_X, BALL_POSITION_UNSET);
    }

    static float ballY(Context context) {
        return prefs(context).getFloat(KEY_BALL_Y, BALL_POSITION_UNSET);
    }

    static void setBallPosition(Context context, float x, float y) {
        prefs(context).edit().putFloat(KEY_BALL_X, x).putFloat(KEY_BALL_Y, y).apply();
    }

    /** Diameter in dp of the floating ball, clamped to the valid range. */
    static int ballSizeDp(Context context) {
        int value = prefs(context).getInt(KEY_BALL_SIZE_DP, DEFAULT_BALL_SIZE_DP);
        return value >= MIN_BALL_SIZE_DP && value <= MAX_BALL_SIZE_DP ? value : DEFAULT_BALL_SIZE_DP;
    }

    static void setBallSizeDp(Context context, int dp) {
        if (dp < MIN_BALL_SIZE_DP || dp > MAX_BALL_SIZE_DP) {
            throw new IllegalArgumentException("ball size out of range: " + dp);
        }
        prefs(context).edit().putInt(KEY_BALL_SIZE_DP, dp).apply();
    }

    /** Whether the floating shortcut ball is shown. */
    static boolean showFloatingBall(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_BALL, true);
    }

    static void setShowFloatingBall(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_SHOW_BALL, value).apply();
    }
}
