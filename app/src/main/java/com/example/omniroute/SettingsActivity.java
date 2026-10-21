package com.example.omniroute;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Preferences screen.
 *
 * <p>Values are written on change and picked up on the next server start, so
 * nothing here restarts the service implicitly. A port change in particular
 * would drop the dashboard mid-edit, so the restart is an explicit button.
 */
public final class SettingsActivity extends Activity {

    private EditText portInput;
    private EditText heapInput;
    private EditText fontScaleInput;
    private EditText ballSizeInput;
    private Switch listenAllSwitch;
    private Switch bootSwitch;
    private Switch crashSwitch;
    private Switch watchdogSwitch;
    private Switch desktopSwitch;
    private Switch ballSwitch;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyWindowInsets(findViewById(R.id.settings_root));

        portInput = findViewById(R.id.port_input);
        heapInput = findViewById(R.id.heap_input);
        fontScaleInput = findViewById(R.id.font_scale_input);
        ballSizeInput = findViewById(R.id.ball_size_input);
        listenAllSwitch = findViewById(R.id.switch_listen_all);
        bootSwitch = findViewById(R.id.switch_boot);
        crashSwitch = findViewById(R.id.switch_crash);
        watchdogSwitch = findViewById(R.id.switch_watchdog);
        desktopSwitch = findViewById(R.id.switch_desktop);
        ballSwitch = findViewById(R.id.switch_ball);

        ((ImageView) findViewById(R.id.settings_back)).setOnClickListener(v -> finish());
        ((Button) findViewById(R.id.btn_restart)).setOnClickListener(v -> restartServer());

        // Migrated here from the main toolbar so that bar stays short.
        findViewById(R.id.row_export).setOnClickListener(v -> exportData());
        findViewById(R.id.row_battery).setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        findViewById(R.id.row_logs).setOnClickListener(v -> showLogs());
        findViewById(R.id.row_reset_password).setOnClickListener(v -> resetDashboardPassword());
        findViewById(R.id.row_oauth).setOnClickListener(v ->
                startActivity(new Intent(this, OAuthActivity.class)));
        findViewById(R.id.row_import).setOnClickListener(v ->
                startActivity(new Intent(this, ImportActivity.class)));

        // Credential notebooks, kept local so no database schema is involved.
        findViewById(R.id.row_client_keys).setOnClickListener(v ->
                startActivity(CredentialListActivity.intentFor(this, CredentialStore.Kind.CLIENT_KEY)));
        findViewById(R.id.row_providers).setOnClickListener(v ->
                startActivity(CredentialListActivity.intentFor(this, CredentialStore.Kind.PROVIDER)));

        // Hints carry the valid range so the bounds stay discoverable without
        // reading the source, and stay in sync with AppSettings constants.
        ((android.widget.TextView) findViewById(R.id.port_hint)).setText(
                getString(R.string.settings_port_hint, AppSettings.MIN_PORT, AppSettings.MAX_PORT));
        ((android.widget.TextView) findViewById(R.id.heap_hint)).setText(
                getString(R.string.settings_heap_hint, AppSettings.MIN_HEAP_MB, AppSettings.MAX_HEAP_MB));
        ((android.widget.TextView) findViewById(R.id.font_scale_hint)).setText(
                getString(R.string.settings_font_scale_hint,
                        AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE));

        // Endpoint and runtime version moved out of the main header to keep that
        // bar to a single icon row.
        ((android.widget.TextView) findViewById(R.id.endpoint_value)).setText(
                getString(R.string.settings_endpoint_value, AppSettings.port(this)));
        ((android.widget.TextView) findViewById(R.id.runtime_value)).setText(
                runtimeLabel());

        updateCredentialCounts();

        portInput.setText(String.valueOf(AppSettings.port(this)));
        heapInput.setText(String.valueOf(AppSettings.heapMb(this)));
        fontScaleInput.setText(String.valueOf(AppSettings.webFontScale(this)));
        listenAllSwitch.setChecked(AppSettings.listenAll(this));
        bootSwitch.setChecked(AppSettings.startOnBoot(this));
        crashSwitch.setChecked(AppSettings.restartOnCrash(this));
        watchdogSwitch.setChecked(KeepAlive.isEnabled(this));
        desktopSwitch.setChecked(AppSettings.desktopMode(this));
        ballSwitch.setChecked(AppSettings.showFloatingBall(this));
        ballSizeInput.setText(String.valueOf(AppSettings.ballSizeDp(this)));

        // Commit on focus loss rather than on every keystroke: typing "65535"
        // would pass through the valid range partially and thrash the storage.
        bindNumberField(portInput, AppSettings.MIN_PORT, AppSettings.MAX_PORT,
                value -> AppSettings.setPort(this, value),
                value -> getString(R.string.settings_port_saved, value),
                R.string.settings_port_invalid, AppSettings.MIN_PORT, AppSettings.MAX_PORT);

        bindNumberField(heapInput, AppSettings.MIN_HEAP_MB, AppSettings.MAX_HEAP_MB,
                value -> AppSettings.setHeapMb(this, value),
                value -> getString(R.string.settings_heap_saved, value),
                R.string.settings_port_invalid, AppSettings.MIN_HEAP_MB, AppSettings.MAX_HEAP_MB);

        listenAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setListenAll(this, isChecked);
            notifyRestartNeeded();
        });
        bootSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppSettings.setStartOnBoot(this, isChecked));
        crashSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppSettings.setRestartOnCrash(this, isChecked));
        watchdogSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            KeepAlive.setEnabled(this, isChecked);
            // Arm or cancel the alarm right away rather than waiting for the
            // next service start, so the switch reflects reality at once.
            KeepAlive.sync(this);
        });

        bindDisplayOptions();
        updateBatteryState();
    }

    /** Display options apply immediately when returning to the main screen. */
    private void bindDisplayOptions() {
        desktopSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppSettings.setDesktopMode(this, isChecked));

        // Shown as a percentage of the default type scale; clamped by the setter.
        bindNumberField(fontScaleInput, AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE,
                value -> AppSettings.setWebFontScale(this, value),
                value -> getString(R.string.settings_font_scale_saved, value),
                R.string.settings_port_invalid,
                AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE);

        ballSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppSettings.setShowFloatingBall(this, isChecked));

        bindNumberField(ballSizeInput, AppSettings.MIN_BALL_SIZE_DP, AppSettings.MAX_BALL_SIZE_DP,
                value -> AppSettings.setBallSizeDp(this, value),
                value -> getString(R.string.settings_ball_size_saved, value),
                R.string.settings_ball_size_invalid,
                AppSettings.MIN_BALL_SIZE_DP, AppSettings.MAX_BALL_SIZE_DP);
    }

    /**
     * Human-readable runtime version for the settings screen.
     *
     * <p>The APK no longer bundles a rootfs, so "no runtime" is a normal state
     * rather than an error; the imported version is what the user actually
     * wants to see once an archive has been installed.
     */
    private String runtimeLabel() {
        String imported = RootfsInstaller.importedVersion(this);
        if (imported != null && !imported.isEmpty()) {
            return imported;
        }
        String installed = RootfsInstaller.installedVersion(this);
        if (installed != null && !installed.isEmpty()) {
            return installed;
        }
        return getString(R.string.settings_runtime_none);
    }

    /** Shows how many entries each notebook holds, so an empty list is obvious. */
    private void updateCredentialCounts() {
        ((android.widget.TextView) findViewById(R.id.count_keys)).setText(
                String.valueOf(CredentialStore.load(this, CredentialStore.Kind.CLIENT_KEY).size()));
        ((android.widget.TextView) findViewById(R.id.count_providers)).setText(
                String.valueOf(CredentialStore.load(this, CredentialStore.Kind.PROVIDER).size()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Entries may have been added or removed on the list screens.
        updateCredentialCounts();
        // The battery exemption can be granted or revoked in system settings
        // while this screen is in the background, so the state is re-read on
        // every resume rather than only once at creation.
        updateBatteryState();
    }

    private interface IntConsumer {
        void accept(int value);
    }

    /**
     * Wires an EditText to validated integer storage, applying the change only
     * when focus leaves and the value parses inside {@code [min, max]}.
     * Invalid input is reverted instead of stored, so a typo cannot leave the
     * server with an unusable port.
     */
    private void bindNumberField(EditText field,
                                 int min,
                                 int max,
                                 IntConsumer setter,
                                 java.util.function.IntFunction<String> savedMessage,
                                 int errorRes,
                                 int errorMin,
                                 int errorMax) {
        field.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            String raw = field.getText().toString().trim();
            int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                revert(field, errorRes, errorMin, errorMax);
                return;
            }
            if (value < min || value > max) {
                revert(field, errorRes, errorMin, errorMax);
                return;
            }
            setter.accept(value);
            Toast.makeText(this, savedMessage.apply(value), Toast.LENGTH_SHORT).show();
            // The endpoint line quotes the port, so keep it in step with edits.
            ((android.widget.TextView) findViewById(R.id.endpoint_value)).setText(
                    getString(R.string.settings_endpoint_value, AppSettings.port(this)));
            notifyRestartNeeded();
        });
    }

    private void revert(EditText field, int errorRes, int errorMin, int errorMax) {
        Toast.makeText(this, getString(errorRes, errorMin, errorMax), Toast.LENGTH_SHORT).show();
        // Restore whichever backing value this field represents, so an invalid
        // entry never leaves the box empty or out of range.
        if (field == portInput) {
            field.setText(String.valueOf(AppSettings.port(this)));
        } else if (field == heapInput) {
            field.setText(String.valueOf(AppSettings.heapMb(this)));
        } else if (field == fontScaleInput) {
            field.setText(String.valueOf(AppSettings.webFontScale(this)));
        } else if (field == ballSizeInput) {
            field.setText(String.valueOf(AppSettings.ballSizeDp(this)));
        }
    }

    private void notifyRestartNeeded() {
        Toast.makeText(this, R.string.settings_restart_needed, Toast.LENGTH_SHORT).show();
    }

    /** Packages the data directory into a zip under the app's external files. */
    private void exportData() {
        Toast.makeText(this, "正在导出备份…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                File archive = DataStore.exportData(this);
                mainHandler.post(() -> Toast.makeText(
                        this, "已保存到 " + archive.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Exception exception) {
                mainHandler.post(() -> Toast.makeText(
                        this, "导出失败：" + exception.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Shows the server log in-app, with the one-time bootstrap token surfaced
     * when present.
     *
     * <p>Pasting the path into a Toast was useless on the one occasion the log
     * actually matters: OmniRoute's first-run wizard asks for a one-time
     * bootstrap token that is printed only to this log, so completing onboarding
     * required adb. The tail is now rendered here, and the token is pulled onto
     * its own selectable line and copied to the clipboard.
     */
    private void showLogs() {
        File log = AppPaths.logFile(this);
        if (!log.isFile()) {
            Toast.makeText(this, R.string.settings_logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            // The scanned window is far larger than what is displayed: the
            // bootstrap token is printed once at startup and then buried under
            // the runtime's own output, so a tail-only scan comes up empty on
            // exactly the run that needs it. 4 MB covers a startup burst plus
            // its aftermath without reading an unbounded log, and the line cap
            // keeps the TextView fed with a readable amount.
            String recent = readLogTail(log, 4 * 1024 * 1024, 400);
            // Prefer the value the log pump captured live as the bytes streamed
            // past; fall back to scanning the window in case the pump started
            // after the server had already printed the line.
            String token = BootstrapToken.load(this);
            if (token == null) {
                token = extractBootstrapToken(recent);
            }
            String captured = token;
            mainHandler.post(() -> presentLogDialog(recent, captured));
        });
    }

    /**
     * Reads the last {@code maxBytes} of a file, then the last {@code maxLines}
     * lines of that slice.
     *
     * <p>Byte-truncating can leave a partial first line, which is dropped: a
     * half-quoted log line reads like corruption rather than a window.
     */
    private static String readLogTail(File log, int maxBytes, int maxLines) {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(log, "r")) {
            long length = file.length();
            int window = (int) Math.min(length, maxBytes);
            byte[] buffer = new byte[window];
            file.seek(length - window);
            file.readFully(buffer);
            String text = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
            if (window < length) {
                int firstBreak = text.indexOf('\n');
                if (firstBreak >= 0) {
                    text = text.substring(firstBreak + 1);
                }
            }
            String[] lines = text.split("\n");
            int start = Math.max(0, lines.length - maxLines);
            StringBuilder builder = new StringBuilder();
            for (int index = start; index < lines.length; index++) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(lines[index]);
            }
            return builder.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    /**
     * Pulls the one-time bootstrap token out of the log, or returns null.
     *
     * <p>The server prints it as {@code [BOOTSTRAP] ... continue: <token>} on a
     * single line, so the token is whatever follows the final ": " there. The
     * match is deliberately loose: the token's own format is the server's
     * business, and pinning it here would break the day it changes.
     */
    private static String extractBootstrapToken(String log) {
        if (log == null || log.isEmpty()) {
            return null;
        }
        for (String line : log.split("\n")) {
            if (!line.contains("[BOOTSTRAP]")) {
                continue;
            }
            int separator = line.lastIndexOf(": ");
            if (separator < 0 || separator + 2 >= line.length()) {
                continue;
            }
            String token = line.substring(separator + 2).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    /** Renders the log tail, with the bootstrap token called out above it. */
    private void presentLogDialog(String tail, String token) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int bodyHeight = (int) (380 * density);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        if (token != null) {
            android.widget.TextView caption = new android.widget.TextView(this);
            caption.setText(R.string.settings_logs_token_found);
            caption.setTextSize(13f);
            box.addView(caption);

            android.widget.TextView value = new android.widget.TextView(this);
            value.setText(token);
            value.setTextIsSelectable(true);
            value.setTypeface(android.graphics.Typeface.MONOSPACE);
            value.setTextSize(15f);
            value.setPadding(0, (int) (6 * density), 0, (int) (12 * density));
            box.addView(value);

            // The wizard wants the token pasted into a text field, and selecting
            // text inside a dialog is fiddly on a phone, so seed the clipboard.
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("bootstrap token", token));
                Toast.makeText(this, R.string.settings_logs_token_copied, Toast.LENGTH_SHORT).show();
            }
        }

        android.widget.TextView body = new android.widget.TextView(this);
        body.setText(tail.isEmpty() ? getString(R.string.settings_logs_empty) : tail);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(10f);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, bodyHeight));

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_logs_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Asking the system to exempt the app is the only way to keep the gateway
     * alive on aggressive OEM builds; the settings screen as a fallback covers
     * devices that refuse the direct request.
     */
    /**
     * Asks the system to exempt the app from battery optimisation.
     *
     * <p>The result is not knowable at the moment the request is made, so the
     * screen refreshes its battery state when it comes back to the
     * foreground instead of assuming the request worked.
     */
    private void requestIgnoreBatteryOptimizations() {
        Intent intent = BatteryStatus.exemptionRequestIntent(this);
        if (intent == null) {
            // Below Android 6 there is nothing to request; the state is
            // already as good as it gets.
            Toast.makeText(this, R.string.settings_battery_state_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception failure) {
            // Some OEM builds ship without a handler for the direct request.
            // Fall back to the generic list so the user can still pick the
            // app by hand.
            try {
                startActivity(BatteryStatus.exemptionSettingsIntent());
            } catch (Exception secondFailure) {
                Toast.makeText(this, R.string.settings_battery_request_failed,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Shows whether the app is exempt from battery optimisation.
     *
     * <p>Previously the row showed a fixed hint and nothing else, so a
     * denied or ignored request looked exactly like a granted one. The state
     * badge and the hint now both reflect the real answer.
     */
    private void updateBatteryState() {
        TextView state = findViewById(R.id.battery_state);
        TextView hint = findViewById(R.id.battery_hint);
        if (!BatteryStatus.canRequestExemption(this)) {
            state.setText(R.string.settings_battery_state_unavailable);
            state.setTextColor(getColor(R.color.idle));
            hint.setText(R.string.settings_battery_hint_unavailable);
            return;
        }
        if (BatteryStatus.isExempt(this)) {
            state.setText(R.string.settings_battery_state_exempt);
            state.setTextColor(getColor(R.color.ok));
            hint.setText(R.string.settings_battery_hint_exempt);
        } else {
            state.setText(R.string.settings_battery_state_not_exempt);
            state.setTextColor(getColor(R.color.warn));
            hint.setText(R.string.settings_battery_hint_not_exempt);
        }
    }

    /**
     * Resets the dashboard password by removing the stored hash.
     *
     * <p>OmniRoute ignores {@code INITIAL_PASSWORD} whenever
     * {@code settings.json} already holds a {@code passwordHash} — the stored
     * hash wins. That is the documented recovery path shown on the dashboard's
     * own "reset password" screen: delete the settings file (or just its hash
     * field) and let the server re-initialise from the environment on the next
     * boot. The upstream CLI equivalent is {@code npx omniroute reset-password},
     * which is not available inside this packaging.
     *
     * <p>The file is backed up before removal. It also carries panel settings
     * beyond the password, so a user who tapped this by mistake can put it back;
     * provider credentials and API keys live in {@code storage.sqlite} and are
     * untouched either way.
     */
    private void resetDashboardPassword() {
        File settings = AppPaths.settingsFile(this);
        if (!settings.isFile()) {
            // Nothing stored means the password already comes from the injected
            // INITIAL_PASSWORD, so there is nothing to clear.
            Toast.makeText(this, R.string.settings_reset_password_none, Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_reset_password_title)
                .setMessage(R.string.settings_reset_password_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset_password_do, (dialog, which) ->
                        performPasswordReset(settings))
                .show();
    }

    private void performPasswordReset(File settings) {
        executor.execute(() -> {
            String outcome;
            try {
                // Keep a copy beside the original rather than deleting outright.
                File backup = new File(settings.getParentFile(),
                        "settings.json.bak-" + System.currentTimeMillis());
                copyFile(settings, backup);
                if (!settings.delete()) {
                    throw new IllegalStateException("无法删除 " + settings.getAbsolutePath());
                }
                outcome = getString(R.string.settings_reset_password_done,
                        RootfsInstaller.DEFAULT_DASHBOARD_PASSWORD);
            } catch (Exception exception) {
                outcome = getString(R.string.settings_reset_password_failed,
                        exception.getMessage() == null ? "" : exception.getMessage());
            }
            String message = outcome;
            mainHandler.post(() -> {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                // The reset only takes effect on the next start, so restart the
                // service rather than leaving the user on a stale session.
                restartServer();
            });
        });
    }

    private static void copyFile(File source, File target) throws Exception {
        try (java.io.InputStream input = new java.io.FileInputStream(source);
             java.io.OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
        }
    }

    private void restartServer() {
        Intent intent = new Intent(this, OmniRouteService.class);
        intent.setAction(OmniRouteService.ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, R.string.settings_restart_now, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Same edge-to-edge handling as MainActivity; see the note there. */
    private void applyWindowInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = windowInsets.getSystemWindowInsetTop();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }
}
