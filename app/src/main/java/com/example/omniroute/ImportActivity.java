package com.example.omniroute;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Imports a full (untrimmed) rootfs tar.gz from external storage.
 *
 * <p>The bundled assets rootfs was aggressively trimmed to fit an APK, which
 * removed playwright, wreq-js, onnxruntime and other optional pieces. Providers
 * that depend on them fail at runtime. Rather than re-packing assets, this
 * screen lets the user hand the app a complete archive: the Storage Access
 * Framework returns a {@code content://} URI (no storage permission needed),
 * the stream is copied into the app's private files dir, and
 * {@link RootfsInstaller#importFrom} unpacks it with the same verified,
 * atomically-promoted path the bundled assets use. Restarting the service then
 * runs the imported tree.
 */
public final class ImportActivity extends Activity {
    private static final int REQUEST_PICK = 3001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView current;
    private ProgressBar progress;
    private Button pickButton;
    private Button officialButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import);
        applyWindowInsets(findViewById(R.id.import_root));

        // Extracting a multi-gigabyte rootfs takes minutes. If the screen sleeps
        // the system throttles the process and eventually kills it, which
        // surfaces as yet another "import failed". Keep the display on until
        // the work finishes; the flag is cleared on every exit path below.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        status = findViewById(R.id.import_status);
        current = findViewById(R.id.import_current);
        progress = findViewById(R.id.import_progress);
        pickButton = findViewById(R.id.import_pick);
        officialButton = findViewById(R.id.import_official_download);

        TextView officialSource = findViewById(R.id.import_official_source);
        officialSource.setText(getString(R.string.import_official_source,
                RootfsImageInstaller.DEFAULT_REGISTRY + "/"
                        + RootfsImageInstaller.DEFAULT_REPOSITORY + ":"
                        + RootfsImageInstaller.DEFAULT_TAG));

        ((ImageView) findViewById(R.id.import_back)).setOnClickListener(v -> finish());
        pickButton.setOnClickListener(v -> pickArchive());
        officialButton.setOnClickListener(v -> beginOfficialDownload());
        ((Button) findViewById(R.id.import_restart)).setOnClickListener(v -> {
            Intent intent = new Intent(this, OmniRouteService.class);
            intent.setAction(OmniRouteService.ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, R.string.settings_restart_now, Toast.LENGTH_SHORT).show();
            finish();
        });

        refreshCurrent();
    }

    private void refreshCurrent() {
        String active = RootfsInstaller.installedVersion(this);
        String imported = RootfsInstaller.importedVersion(this);
        current.setText(getString(R.string.import_current_value,
                active == null ? "—" : active,
                imported == null ? getString(R.string.import_none) : imported));
    }

    private void pickArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Some ROM DocumentsUI builds filter by MIME, so an explicit list is
        // what makes a .tar.gz selectable rather than greyed out.
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] {"application/gzip", "application/x-gzip", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception exception) {
            Toast.makeText(this, R.string.import_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        beginImport(uri);
    }

    private void beginImport(Uri uri) {
        setBusy(true);
        status.setText(R.string.import_copying);

        executor.execute(() -> {
            File archive = AppPaths.importedArchive(this);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(archive)) {
                if (input == null) {
                    throw new IllegalStateException("无法读取所选文件");
                }
                byte[] buffer = new byte[1 << 16];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) > 0) {
                    output.write(buffer, 0, count);
                    total += count;
                    long mb = total / (1024 * 1024);
                    if (mb % 64 == 0) {
                        long shown = mb;
                        mainHandler.post(() -> status.setText(
                                getString(R.string.import_copying_mb, shown)));
                    }
                }
            } catch (Exception exception) {
                postError("拷贝失败：" + exception.getMessage());
                return;
            }

            mainHandler.post(() -> status.setText(R.string.import_extracting));
            try {
                RootfsInstaller.importFrom(this, archive,
                        message -> mainHandler.post(() -> status.setText(message)));
            } catch (Exception exception) {
                postError("导入失败：" + exception.getMessage());
                return;
            } finally {
                // A several-hundred-MB intermediate file must not survive in
                // filesDir, whether the import succeeded or failed.
                if (archive.exists()) {
                    archive.delete();
                }
            }

            mainHandler.post(() -> {
                setBusy(false);
                status.setText(getString(R.string.import_done_password,
                        RootfsInstaller.DEFAULT_DASHBOARD_PASSWORD));
                refreshCurrent();
                Toast.makeText(this, R.string.import_done_toast, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void postError(String message) {
        mainHandler.post(() -> {
            progress.setVisibility(View.GONE);
            progress.setIndeterminate(true);
            pickButton.setEnabled(true);
            officialButton.setEnabled(true);
            status.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Fetches the runtime from the official container registry and installs it.
     *
     * <p>This is the path that needs no user-supplied URL: the upstream
     * project publishes its runtime as a Linux container image, and the arm64
     * variant is exactly the runtime this app needs. The layers are pulled,
     * unpacked into the layout the server launches and promoted through the
     * same atomic install path the archive import uses.
     */
    private void beginOfficialDownload() {
        setBusy(true);
        status.setText(R.string.import_official_downloading);

        executor.execute(() -> {
            String version;
            try {
                version = RootfsImageInstaller.installFromRegistry(this,
                        RootfsImageInstaller.DEFAULT_REGISTRY,
                        RootfsImageInstaller.DEFAULT_REPOSITORY,
                        RootfsImageInstaller.DEFAULT_TAG,
                        message -> mainHandler.post(() -> status.setText(message)),
                        (downloaded, total) -> mainHandler.post(() -> {
                            if (total > 0) {
                                progress.setIndeterminate(false);
                                progress.setMax(100);
                                progress.setProgress((int) (downloaded * 100L / total));
                            }
                        }));
            } catch (Exception exception) {
                postError(getString(R.string.import_official_failed,
                        String.valueOf(exception.getMessage())));
                return;
            }
            mainHandler.post(() -> {
                setBusy(false);
                progress.setIndeterminate(true);
                status.setText(getString(R.string.import_official_done, version));
                refreshCurrent();
                Toast.makeText(this, R.string.import_done_toast, Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Disables the action buttons and shows the progress bar. */
    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        officialButton.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    /** Same edge-to-edge handling as the other activities. */
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
