package com.example.omniroute;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Insets;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "OmniRoute";
    private static final int REQUEST_NOTIFICATIONS = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private WebView webView;
    private View statusDot;
    private TextView statusMessage;
    private TextView emptyText;
    private View emptyState;
    private View progress;
    private View progressRow;
    private ImageView restartAction;
    private ImageView openAction;
    private ImageView settingsAction;
    private boolean loaded;
    private boolean polling;
    /** Tracks the dot's animated state so the pulse is not restarted on every poll. */
    private boolean dotAnimating;
    /** Last applied display settings, so a reload only happens on a real change. */
    private int appliedFontScale = -1;
    private boolean appliedDesktopMode;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowInsets(findViewById(R.id.root));

        webView = findViewById(R.id.webview);
        statusDot = findViewById(R.id.status_dot);
        statusMessage = findViewById(R.id.status_message);
        emptyText = findViewById(R.id.empty_text);
        emptyState = findViewById(R.id.empty_state);
        progress = findViewById(R.id.progress);
        progressRow = findViewById(R.id.progress_row);
        restartAction = findViewById(R.id.action_restart);
        openAction = findViewById(R.id.action_open);
        settingsAction = findViewById(R.id.action_settings);

        configureWebView();

        installFloatingBall();
        webView.setVisibility(View.GONE);

        restartAction.setOnClickListener(view -> restartServer());
        openAction.setOnClickListener(view -> openDashboardExternally());
        settingsAction.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));

        updateStatus(ServerState.status(this), ServerState.message(this));

        requestNotificationPermission();
        startServer();
        pollStatus();
    }

    /**
     * Drives the header indicator.
     *
     * <p>Running uses a slow alpha pulse so a glance distinguishes a live server
     * from a hung one; every other state is a static red dot. The animation is
     * started and cancelled only on transitions, because re-starting it every
     * poll would visibly stutter.
     */
    private void updateStatusDot(String status) {
        boolean running = ServerState.STATUS_RUNNING.equals(status);
        int color = getColor(running ? R.color.ok : R.color.error);

        // The drawable is shared between states, so mutate before tinting or the
        // tint would leak into other users of the same resource.
        android.graphics.drawable.Drawable dot = getDrawable(R.drawable.bg_status_dot);
        if (dot == null) {
            return;
        }
        dot = dot.mutate();
        dot.setTint(color);
        statusDot.setBackground(dot);

        if (running && !dotAnimating) {
            statusDot.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.dot_breathe));
            dotAnimating = true;
        } else if (!running && dotAnimating) {
            statusDot.clearAnimation();
            dotAnimating = false;
        }
        // A stopped dot is fully opaque; a resumed pulse restarts from its
        // first frame, so reset the alpha the animation may have left behind.
        statusDot.setAlpha(running ? statusDot.getAlpha() : 1f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Display settings may have changed; applying them needs a reload, which
        // is cheap here because the dashboard is a local server.
        applyWebDisplaySettings();
        updateStatus(ServerState.status(this), ServerState.message(this));
    }

    /** Re-applies zoom and viewport settings after they change in Settings. */
    private void applyWebDisplaySettings() {
        WebSettings settings = webView.getSettings();
        int scale = AppSettings.webFontScale(this);
        boolean desktop = AppSettings.desktopMode(this);

        boolean changed = scale != appliedFontScale || desktop != appliedDesktopMode;
        settings.setTextZoom(scale);
        settings.setUseWideViewPort(desktop);
        settings.setLoadWithOverviewMode(desktop);
        settings.setUserAgentString(desktop
                ? desktopUserAgent(settings.getUserAgentString())
                : mobileUserAgent(settings.getUserAgentString()));
        appliedFontScale = scale;
        appliedDesktopMode = desktop;

        // The ball has its own toggle; honour it without restarting the server.
        // Its size is also user-configurable, so re-apply it here so a change
        // made in Settings shows up the moment the user returns to the dashboard.
        View ball = findViewById(R.id.floating_ball);
        ball.setVisibility(
                AppSettings.showFloatingBall(this) ? View.VISIBLE : View.GONE);
        if (ball instanceof FloatingBallView) {
            ((FloatingBallView) ball).setSizeDp(AppSettings.ballSizeDp(this));
        }

        // Both viewport and UA are only read when the document is created, so a
        // change needs a reload to take effect. Zoom alone does not, but
        // reloading unconditionally after any display change keeps it simple.
        if (changed && loaded && webView.getVisibility() == View.VISIBLE) {
            webView.reload();
        }
    }

    /**
     * Restores a mobile-ish UA when desktop mode is off. The original token may
     * already have been replaced, so rebuild it from the engine version rather
     * than trying to reverse the earlier edit.
     */
    private static String mobileUserAgent(String current) {
        String base = current == null ? "" : current;
        String chrome = "Chrome/120.0.0.0";
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Chrome/[0-9.]+").matcher(base);
        if (matcher.find()) {
            chrome = matcher.group();
        }
        return "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) " + chrome + " Mobile Safari/537.36";
    }

    /**
     * Android 15+ (targetSdk 35+) forces edge-to-edge, so the layout is drawn
     * behind the status and navigation bars. Pad the root by the real system
     * bar / cutout insets instead of hardcoding any assumed status bar height,
     * which keeps the header visible on every screen size and notch shape.
     */
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

    /**
     * Wires the floating ball.
     *
     * <p>Short swipes navigate history; long swipes are reserved for the
     * heavier actions that will be added later. The ball itself only moves after
     * a long press, which replaces the earlier edge-swipe: an edge gesture
     * competes with the system back gesture on gesture-navigation devices,
     * whereas grabbing a ball on screen never does.
     */
    private void installFloatingBall() {
        FloatingBallView ball = findViewById(R.id.floating_ball);
        // Apply the persisted size; the ball keeps whatever the user chose last.
        ball.setSizeDp(AppSettings.ballSizeDp(this));
        ball.setListener(new FloatingBallView.Listener() {
            @Override
            public void onTap() {
                // Reserved for the shortcut menu.
                Toast.makeText(MainActivity.this,
                        webView.canGoBack() ? "可后退" : "已在首页", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSwipeLeft() {
                // Drag left = go back, matching the "swipe toward where you came
                // from" convention used by browsers.
                if (webView.canGoBack()) {
                    webView.goBack();
                }
            }

            @Override
            public void onSwipeRight() {
                if (webView.canGoForward()) {
                    webView.goForward();
                }
            }

            @Override
            public void onSwipeUp() {
                // Jump to the home / dashboard page: reload the base URL so the
                // WebView drops whatever nested route the user wandered into.
                webView.loadUrl(AppSettings.dashboardUrl(MainActivity.this));
            }

            @Override
            public void onSwipeDown() {
                // Pull to refresh: reload whatever page is showing.
                webView.reload();
            }

            @Override
            public void onLongSwipeLeft() {
                // Placeholder for a heavyweight action; kept distinct from the
                // short swipe so the two can diverge without a redesign.
                Toast.makeText(MainActivity.this, "长滑左（待定功能）", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onLongSwipeRight() {
                Toast.makeText(MainActivity.this, "长滑右（待定功能）", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onMoved(float x, float y) {
                // Remembered so the ball stays where the user parked it.
                AppSettings.setBallPosition(MainActivity.this, x, y);
            }
        });

        // Restore a saved position once the view has been laid out, otherwise
        // the parent has no size yet and the clamp would drop it at 0,0.
        ball.post(() -> {
            float x = AppSettings.ballX(MainActivity.this);
            float y = AppSettings.ballY(MainActivity.this);
            if (x != AppSettings.BALL_POSITION_UNSET && y != AppSettings.BALL_POSITION_UNSET) {
                ball.moveTo(x, y);
            }
        });
    }

    /**
     * Turns the WebView's default UA into a desktop one.
     *
     * <p>Replaces the {@code Mobile} token and drops the Android model tag so
     * the server and any UA sniffing treat the client as a desktop browser.
     * The Chrome version is kept from the original string to avoid claiming a
     * version the bundled engine does not support.
     */
    private static String desktopUserAgent(String original) {
        if (original == null || original.isEmpty()) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        }
        String desktop = original.replace("Mobile", "").replaceAll("\\s+", " ").trim();
        int linux = desktop.indexOf("Linux;");
        if (linux >= 0) {
            // "Linux; Android 14; Pixel 8" -> "Linux; Android 14" keeps it
            // plausible while removing the phone-specific model token.
            int end = desktop.indexOf(';', linux + 7);
            if (end > 0) {
                int next = desktop.indexOf(';', end + 1);
                if (next > 0 && desktop.substring(end + 1, next).trim().matches(".*[A-Za-z0-9].*")) {
                    desktop = desktop.substring(0, end + 1) + desktop.substring(next);
                }
            }
        }
        return desktop.replace("; ;", ";").replace("( ;", "(").replace(" )", ")").trim();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void openDashboardExternally() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppSettings.dashboardUrl(this))));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void startServer() {
        startServerWith(null);
    }

    private void startServerWith(String action) {
        Intent intent = new Intent(this, OmniRouteService.class);
        if (action != null) {
            intent.setAction(action);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void restartServer() {
        loaded = false;
        webView.setVisibility(View.GONE);
        emptyState.setVisibility(View.VISIBLE);
        updateStatus(ServerState.STATUS_STARTING, "正在重启服务");
        startServerWith(OmniRouteService.ACTION_RESTART);
        if (!polling) {
            pollStatus();
        }
    }

    private void pollStatus() {
        polling = true;
        mainHandler.postDelayed(() -> {
            updateStatus(ServerState.status(this), ServerState.message(this));
            if (ServerState.STATUS_RUNNING.equals(ServerState.status(this)) && !loaded) {
                loaded = true;
                webView.setVisibility(View.VISIBLE);
                emptyState.setVisibility(View.GONE);
                webView.loadUrl(AppSettings.dashboardUrl(this));
            }
            mainHandler.postDelayed(this::pollStatus, 2000);
        }, 1000);
    }

    private void updateStatus(String status, String message) {
        String label;
        switch (status) {
            case ServerState.STATUS_RUNNING:
                label = getString(R.string.state_running);
                break;
            case ServerState.STATUS_STARTING:
                label = getString(R.string.state_starting);
                break;
            case ServerState.STATUS_ERROR:
                label = getString(R.string.state_error);
                break;
            default:
                label = getString(R.string.state_stopped);
                break;
        }

        updateStatusDot(status);

        String detail = message == null || message.trim().isEmpty() ? label : message.trim();
        statusMessage.setText(detail);
        emptyText.setText(detail);

        // The progress row exists only to explain a non-running state, so it is
        // removed entirely once the server is up.
        boolean running = ServerState.STATUS_RUNNING.equals(status);
        progressRow.setVisibility(running ? View.GONE : View.VISIBLE);
        progress.setVisibility(running ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(running ? View.GONE : View.VISIBLE);
    }

    /**
     * Lets the dashboard drive its own OAuth flows.
     *
     * <p>http(s) navigations render inside the WebView so a consent page the
     * dashboard opens completes in-app. Non-http(s) schemes — a provider's own
     * app scheme, or the app's {@code omniroute-oauth} callback — are handed to
     * the system intent resolver instead of dying as an unsupported URL; the
     * app's own scheme is routed back to {@link OAuthActivity} to capture the
     * token.
     */
    private final class DashboardWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return routeScheme(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return routeScheme(Uri.parse(url));
        }

        /**
         * The renderer died; the gateway must not die with it.
         *
         * <p>The default (super) behaviour kills the whole app process, which
         * takes the OmniRoute gateway down together with a dashboard tab.
         * The crash report that started this fix was exactly that: a
         * Chromium-internal CHECK on the Chrome_InProcGp thread brought the
         * process down with SIGTRAP while the gateway was serving traffic.
         *
         * <p>Returning true means the app handles the death itself: the dead
         * WebView is torn down safely and a fresh one is created, so the
         * dashboard reloads instead of the whole app dying.
         */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (webView != null && webView == view) {
                Log.w(TAG, "WebView renderer died (crashed=" + detail.didCrash()
                        + "); rebuilding it instead of killing the process");
                rebuildWebView();
            }
            return true;
        }
    }

    /**
     * Replaces a renderer-dead WebView with a fresh one and reloads the
     * dashboard.
     *
     * <p>destroy() must not run while the view is still attached: it would
     * throw "This WebView cannot be destroyed" or leave the surface in a
     * half-dead state that keeps crashing. The dead view is removed from the
     * hierarchy first, then destroyed.
     */
    private void rebuildWebView() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        int index = parent.indexOfChild(webView);
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        parent.removeView(webView);
        try {
            webView.destroy();
        } catch (Exception ignored) {
            // A renderer-dead WebView can throw from destroy(); the view is
            // being discarded either way.
        }
        webView = new WebView(this);
        parent.addView(webView, index, params);
        configureWebView();
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(AppSettings.dashboardUrl(this));
    }

    /**
     * Applies every WebView setting the dashboard needs. Called both from
     * onCreate and after a renderer crash replaces the WebView.
     */
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // The dashboard is a Tailwind layout that switches to a cramped mobile
        // arrangement below 1024px (sidebar collapses into a drawer, cards stack
        // and the type scale shrinks). Reporting a desktop viewport keeps the
        // roomy layout, which is what a phone with a 1080px-wide screen can
        // actually fit once the viewport is scaled.
        if (AppSettings.desktopMode(this)) {
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUserAgentString(desktopUserAgent(settings.getUserAgentString()));
        } else {
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
        }
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        // Hiding the zoom buttons keeps the overlay from covering the dashboard;
        // pinch-to-zoom still works.
        settings.setDisplayZoomControls(false);

        // Pin the type scale. WebView otherwise honours the system font size,
        // and several OEM builds inflate it further, which is what produced the
        // "some text huge, some tiny" mismatch against Tailwind's rem sizes.
        settings.setTextZoom(AppSettings.webFontScale(this));
        appliedFontScale = AppSettings.webFontScale(this);
        appliedDesktopMode = AppSettings.desktopMode(this);

        webView.setBackgroundColor(0xFF121826);
        // Cookies (including third-party) are persisted so an OAuth consent page
        // the dashboard opens inside this WebView keeps its session/state across
        // the redirect. Without this, providers that round-trip a nonce cookie
        // reject the callback and the dashboard's own OAuth buttons fail.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.setAcceptFileSchemeCookies(false);
        webView.setWebViewClient(new DashboardWebViewClient());
        webView.setVisibility(View.GONE);
    }

    /**
     * Returns true for non-http(s) schemes after launching the system handler,
     * so the WebView does not show an "unsupported scheme" error. http(s) is
     * loaded in the WebView (return false).
     */
    private boolean routeScheme(Uri uri) {
        String scheme = uri == null ? null : uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /**
     * Route the hardware back key through the dashboard's history first, so
     * navigating inside the app does not immediately close it.
     */
    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
