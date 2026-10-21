package com.example.omniroute;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Reports whether the app is exempt from battery optimisation.
 *
 * <p>The settings screen used to offer a button that fired
 * {@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} and then showed
 * nothing about the outcome. A user who taps "deny", or whose OEM refuses the
 * exemption outright, was left with no way to tell whether the gateway would
 * actually survive in the background — the row looked identical either way.
 *
 * <p>These helpers make the state visible: the screen can now say whether the
 * exemption is granted, denied, or simply unavailable on this build.
 */
final class BatteryStatus {

    private BatteryStatus() {}

    /**
     * Whether the app is currently exempt from battery optimisation.
     *
     * <p>On Android 6 and later this is a real question with a real answer.
     * Below that, battery optimisation did not exist, so the exemption is
     * trivially in effect and the app is as protected as it can be.
     */
    static boolean isExempt(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Doze and its whitelist arrived in Marshmallow; before that the
            // app is never battery-optimised away.
            return true;
        }
        PowerManager manager =
                (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (manager == null) {
            return false;
        }
        return manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Whether this build lets the app ask for the exemption directly.
     *
     * <p>{@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} was added in
     * Android 6. On newer builds it is still the documented path, but many
     * OEM skins replace the dialog with their own list or ignore it, which is
     * why the screen shows the state instead of assuming the request worked.
     */
    static boolean canRequestExemption(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /**
     * Builds the intent that asks the system to exempt this app.
     *
     * <p>Some builds ship without a handler for the direct request, so the
     * caller should fall back to the generic battery-optimisation settings
     * screen when this returns null or throws.
     */
    static Intent exemptionRequestIntent(Context context) {
        if (!canRequestExemption(context)) {
            return null;
        }
        Intent intent =
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    /**
     * The generic battery-optimisation settings list, used as a fallback when
     * the direct request is not handled.
     */
    static Intent exemptionSettingsIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }
}
