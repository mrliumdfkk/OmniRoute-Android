package com.example.omniroute;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts an OAuth 2.0 authorization-code flow and captures the redirect.
 *
 * <p>The bundled rootfs dropped playwright, which is what OmniRoute's
 * browser-automation providers used to log in upstream. This activity replaces
 * that with an in-app WebView (or the system browser) for the consent step and
 * captures the redirect itself, turning an OAuth login into a bearer token
 * stored as a provider credential.
 *
 * <p>Two redirect shapes are supported:
 * <ul>
 *   <li>The app's own custom scheme {@code omniroute-oauth://callback}, used
 *       when the consent page runs in the system browser — the OS routes the
 *       redirect to {@link OAuthCallbackActivity}, which forwards it here.</li>
 *   <li>A loopback {@code http://localhost:&lt;port&gt;/...} redirect, used by
 *       providers that pin a loopback URI; the WebViewClient intercepts it
 *       before the WebView tries to connect to a port nothing is listening on.</li>
 * </ul>
 *
 * <p>PKCE (S256) and a state token are generated per flow so public clients
 * (Anthropic, Google) work without a client secret. The pending verifier and
 * state are persisted, so an external-browser flow still completes after the
 * app process is killed while the consent page is open.
 */
public final class OAuthActivity extends Activity {
    private static final String TAG = "OmniRoute";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * How long the gateway keeps its pinned-port listener open.
     *
     * <p>The runtime uses five minutes for the same thing, so the app waits a
     * little longer to cover a slow login.
     */
    private static final long CALLBACK_TIMEOUT_MS = 330_000L;
    /** How often the app asks the gateway whether the callback landed. */
    private static final long CALLBACK_POLL_MS = 2_000L;

    private View configPanel;
    private WebView webView;
    private TextView status;
    private EditText authorizeInput;
    private EditText tokenInput;
    private EditText clientIdInput;
    private EditText clientSecretInput;
    private EditText scopesInput;
    private EditText redirectInput;
    private EditText apiBaseInput;
    private Switch pkceSwitch;
    private Switch externalSwitch;
    private Switch saveSwitch;
    private LinearLayout presetContainer;
    private View importPanel;
    private EditText importTokenInput;

    private int selectedPreset = 0;
    /** The provider key whose flow is in flight, or {@code null}. */
    private String activeProviderKey;
    /**
     * The last authorization URL loaded into the WebView, so a renderer
     * rebuild can reload the consent page instead of dropping the flow.
     */
    private String pendingAuthorizeUrl;
    /** Guards against the redirect being handled twice (WebView + onNewIntent). */
    private boolean completing;
    /**
     * The loopback listener for Zed's native-app sign-in, when one is running.
     */
    private ZedNativeAppListener zedListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oauth);
        applyWindowInsets(findViewById(R.id.oauth_root));

        configPanel = findViewById(R.id.oauth_config_panel);
        webView = findViewById(R.id.oauth_webview);
        status = findViewById(R.id.oauth_status);
        authorizeInput = findViewById(R.id.oauth_authorize_url);
        tokenInput = findViewById(R.id.oauth_token_url);
        clientIdInput = findViewById(R.id.oauth_client_id);
        clientSecretInput = findViewById(R.id.oauth_client_secret);
        scopesInput = findViewById(R.id.oauth_scopes);
        redirectInput = findViewById(R.id.oauth_redirect_uri);
        apiBaseInput = findViewById(R.id.oauth_api_base);
        pkceSwitch = findViewById(R.id.oauth_switch_pkce);
        externalSwitch = findViewById(R.id.oauth_switch_external);
        saveSwitch = findViewById(R.id.oauth_switch_save);
        presetContainer = findViewById(R.id.oauth_preset_container);
        importPanel = findViewById(R.id.oauth_import_panel);
        importTokenInput = findViewById(R.id.oauth_import_token);

        ((Button) findViewById(R.id.oauth_btn_import))
                .setOnClickListener(view -> submitImportedToken());

        ((ImageView) findViewById(R.id.oauth_back)).setOnClickListener(view -> finish());
        ((Button) findViewById(R.id.oauth_btn_authorize)).setOnClickListener(view -> startFlow());

        buildPresetButtons();
        loadForm();

        // Launched by OAuthCallbackActivity carrying the captured redirect, or
        // recreated by the system after process death during an external flow.
        Uri incoming = getIntent() != null ? getIntent().getData() : null;
        if (incoming != null && OAuthConfig.CALLBACK_SCHEME.equalsIgnoreCase(incoming.getScheme())) {
            handleCallback(incoming);
        }
    }

    /**
     * Builds the provider buttons from {@link OAuthConfig#PRESETS}.
     *
     * <p>The layout used to declare four fixed buttons and bind them to
     * hard-coded preset indexes while {@code PRESETS} was edited separately.
     * Any change to the preset list silently paired a button with the wrong
     * provider — for example "Google" started a WorkBuddy device-code flow.
     * Generating the rows from the same array the logic reads removes that
     * whole class of bug: a button can only exist for a preset that exists.
     */
    private void buildPresetButtons() {
        presetContainer.removeAllViews();
        for (int i = 0; i < OAuthConfig.PRESETS.length; i++) {
            final int index = i;
            OAuthConfig.Preset preset = OAuthConfig.PRESETS[i];
            Button button = new Button(this);
            button.setText(preset.name);
            button.setAllCaps(false);
            button.setTextSize(13f);
            button.setMinHeight(dp(44));
            button.setGravity(android.view.Gravity.CENTER_VERTICAL);
            button.setPadding(dp(12), dp(8), dp(12), dp(8));
            // A programmatically created Button inherits the platform's
            // default material background, which is tinted with the *system*
            // theme colour. On a dark app that produced beige blocks that
            // clashed with every other control on the screen. The preset rows
            // therefore use the same drawable as the static buttons declared
            // in the layout, so the whole screen reads as one design.
            boolean isCustom = index == OAuthConfig.CUSTOM_PRESET_INDEX;
            button.setBackgroundResource(isCustom
                    ? R.drawable.bg_button_secondary
                    : R.drawable.bg_button);
            button.setTextColor(getColor(isCustom
                    ? R.color.text_secondary
                    : R.color.text_primary));
            // The platform also adds an elevation shadow to material buttons,
            // which the drawable-based buttons elsewhere in the app do not
            // have. Dropping it keeps the rows flush with the card.
            button.setStateListAnimator(null);
            button.setOnClickListener(view -> onPresetClicked(index));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            // The platform material background is drawn with an inset, so the
            // rows would touch each other once the custom drawable replaced
            // it. A small vertical margin keeps the list readable.
            rowParams.topMargin = dp(2);
            rowParams.bottomMargin = dp(2);
            presetContainer.addView(button, rowParams);
            // The note explains what the tap will actually do, and which
            // provider has to be configured on the server first. Without it
            // a tap that fails with "provider is not configured" looks like
            // a bug in the app rather than a missing setting.
            if (preset.note != null && !preset.note.isEmpty()) {
                TextView hint = new TextView(this);
                hint.setText(preset.note);
                hint.setTextSize(11f);
                hint.setTextColor(getColor(R.color.text_secondary));
                hint.setPadding(dp(12), dp(2), dp(12), dp(6));
                presetContainer.addView(hint, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * A one-tap provider starts immediately — that is the whole point of the
     * one-click list. The transport is chosen by the <em>runtime</em>, not by
     * the app: the app asks the gateway which flow the provider uses and
     * follows the answer.
     *
     * <p>The preset's own {@link OAuthConfig.FlowKind} is only a hint. The
     * provider registry lives in the runtime, an imported rootfs can replace
     * it, and a hardcoded table goes stale — which is how Grok CLI ended up
     * being driven down a device-code path the gateway does not serve. The
     * runtime's answer always wins; the hint is used only when the gateway
     * cannot be reached at all.
     */
    private void onPresetClicked(int index) {
        OAuthConfig.Preset preset = OAuthConfig.PRESETS[index];
        applyPreset(index);
        if (!preset.oneTap) {
            return;
        }
        if (!preset.isGatewayDriven()) {
            startFlow();
            return;
        }
        startOneTap(preset);
    }

    /**
     * Asks the runtime how a provider logs in, then runs that flow.
     *
     * <p>The probe is a single {@code GET /api/oauth/{provider}/authorize},
     * which the gateway answers with the flow type, the pinned loopback port
     * (if any) and — for providers it cannot serve — the reason.
     */
    private void startOneTap(final OAuthConfig.Preset preset) {
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        importPanel.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.oauth_status_probing);
        activeProviderKey = preset.providerKey;

        executor.execute(() -> {
            final RootfsApi.FlowInfo flow = RootfsApi.probeFlow(this,
                    preset.providerKey, OAuthConfig.DEFAULT_REDIRECT_URI);
            mainHandler.post(() -> {
                if (flow == null) {
                    // The gateway is unreachable or the provider is unknown to
                    // it. Fall back to the preset's own hint rather than
                    // failing outright.
                    runPresetHint(preset);
                    return;
                }
                runResolvedFlow(preset, flow);
            });
        });
    }

    /**
     * Runs the flow the runtime reported for a provider.
     */
    private void runResolvedFlow(OAuthConfig.Preset preset, RootfsApi.FlowInfo flow) {
        if (!flow.supported) {
            // The runtime knows the provider but refuses it, and says why
            // ("Qoder browser OAuth is experimental and disabled by
            // default…"). Showing that reason is far more useful than
            // letting the flow fail halfway through.
            //
            // But a refusal is not automatically an invitation to paste a
            // token: the generic import route only accepts devin-desktop,
            // devin-cli, grok-cli and claude. For Qoder and GitLab Duo it
            // would answer "import-token not supported for provider: …", so
            // the paste box is not offered at all.
            if (preset.flowKind == OAuthConfig.FlowKind.MANUAL_KEY) {
                showManualKeyOnly(preset, flow.error);
                return;
            }
            showImportTokenField(preset, flow.error);
            return;
        }
        if (flow.isImportToken()) {
            showImportTokenField(preset, null);
            return;
        }
        if (flow.isZedNativeApp()) {
            // Zed's native-app sign-in. The runtime built the authorization
            // URL with a public key it generated, and it expects the app to
            // capture the loopback redirect and hand the whole URL back —
            // together with the matching private key, which the runtime
            // passed along as the "code verifier".
            startZedNativeAppFlow(preset, flow);
            return;
        }
        if (flow.isDeviceCode()) {
            // A provider that pins a loopback port as well is served by the
            // gateway's own listener. Grok CLI is the case in point: the
            // runtime advertises "device_code" but its device endpoint fails
            // with "fetch failed" while the callback server works, so the
            // pinned port is the route that actually succeeds.
            if (flow.fixedPort > 0) {
                startCallbackServerFlow(preset);
                return;
            }
            startDeviceFlow(preset.providerKey, preset.name);
            return;
        }
        if (flow.fixedPort > 0) {
            // The provider pins its redirect to a fixed loopback port, so the
            // app cannot intercept it — but the gateway binds the port itself
            // and the app polls /poll-callback until the connection appears.
            startCallbackServerFlow(preset);
            return;
        }
        if (!flow.isRedirectCapturable()) {
            // The provider honours a redirect URI the app cannot catch —
            // Claude pins its own site, for example. The runtime still calls
            // this "authorization_code", but the code never reaches the
            // device, so the browser flow would wait forever. Importing a
            // token the user already holds is the only way in.
            showImportTokenField(preset, getString(
                    R.string.oauth_import_redirect_pinned,
                    preset.name,
                    flow.effectiveRedirectUri() == null
                            ? "" : flow.effectiveRedirectUri()));
            return;
        }
        startGatewayAuthorizationCodeFlow(preset, flow);
    }

    /**
     * Runs the flow the preset claims, used when the runtime cannot be asked.
     */
    private void runPresetHint(OAuthConfig.Preset preset) {
        switch (preset.flowKind) {
            case DEVICE_CODE:
                startDeviceFlow(preset.providerKey, preset.name);
                break;
            case CALLBACK_SERVER:
                startCallbackServerFlow(preset);
                break;
            case IMPORT_TOKEN:
                showImportTokenField(preset, null);
                break;
            case ZED_NATIVE_APP:
                // Without the runtime's answer there is no public key to give
                // Zed, so the native-app sign-in cannot be started. Fall back
                // to the paste box rather than opening a URL that cannot work.
                showImportTokenField(preset, getString(R.string.oauth_zed_need_runtime));
                break;
            case MANUAL_KEY:
                // The runtime has no OAuth flow and no import route for this
                // provider, so there is nothing to start and nothing to paste.
                showManualKeyOnly(preset, null);
                break;
            case BROWSER:
            default:
                startGatewayAuthorizationCodeFlow(preset);
                break;
        }
    }

    private void applyPreset(int index) {
        selectedPreset = index;
        OAuthConfig.Preset preset = OAuthConfig.PRESETS[index];
        // Only the import-token presets use the paste box; every other
        // preset hides it again so a leftover token never lands in the
        // wrong provider.
        importPanel.setVisibility(preset.flowKind == OAuthConfig.FlowKind.IMPORT_TOKEN
                ? View.VISIBLE : View.GONE);
        authorizeInput.setText(preset.authorizeUrl);
        tokenInput.setText(preset.tokenUrl);
        scopesInput.setText(preset.scopes);
        apiBaseInput.setText(preset.apiBaseUrl);
        // Keep a redirect URI the user typed, but seed the default once.
        if (redirectInput.getText().toString().trim().isEmpty()) {
            redirectInput.setText(OAuthConfig.DEFAULT_REDIRECT_URI);
        }
        pkceSwitch.setChecked(preset.requiresPkce);
        if (preset.oneTap) {
            Toast.makeText(this, getString(R.string.oauth_preset_applied, preset.name),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadForm() {
        selectedPreset = OAuthConfig.presetIndex(this);
        authorizeInput.setText(OAuthConfig.authorizeUrl(this));
        tokenInput.setText(OAuthConfig.tokenUrl(this));
        clientIdInput.setText(OAuthConfig.clientId(this));
        clientSecretInput.setText(OAuthConfig.clientSecret(this));
        scopesInput.setText(OAuthConfig.scopes(this));
        String redirect = OAuthConfig.redirectUri(this);
        redirectInput.setText(redirect.isEmpty() ? OAuthConfig.DEFAULT_REDIRECT_URI : redirect);
        apiBaseInput.setText(OAuthConfig.apiBase(this));
        externalSwitch.setChecked(OAuthConfig.useExternal(this));
        saveSwitch.setChecked(OAuthConfig.saveProvider(this));
        pkceSwitch.setChecked(OAuthConfig.PRESETS[selectedPreset].requiresPkce);
    }

    private void startFlow() {
        String authorizeUrl = authorizeInput.getText().toString().trim();
        String clientId = clientIdInput.getText().toString().trim();
        OAuthConfig.Preset preset = OAuthConfig.PRESETS[selectedPreset];
        // A built-in provider is driven entirely by the gateway: it knows the
        // client id, mints the PKCE challenge and performs the code exchange,
        // so the app never has to hand-write a client id for it. Which
        // transport it uses depends on the provider's flow kind.
        if (preset.isGatewayDriven()) {
            // Same server-authoritative dispatch as the one-tap buttons: the
            // runtime decides the transport, so pressing "开始授权" on a
            // built-in provider behaves exactly like tapping its button.
            startOneTap(preset);
            return;
        }
        if (authorizeUrl.isEmpty() || clientId.isEmpty()) {
            Toast.makeText(this, R.string.oauth_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        String tokenUrl = tokenInput.getText().toString().trim();
        String clientSecret = clientSecretInput.getText().toString().trim();
        String scopes = scopesInput.getText().toString().trim();
        String redirectUri = redirectInput.getText().toString().trim();
        if (redirectUri.isEmpty()) {
            redirectUri = OAuthConfig.DEFAULT_REDIRECT_URI;
        }
        String apiBase = apiBaseInput.getText().toString().trim();
        boolean usePkce = pkceSwitch.isChecked();
        boolean useExternal = externalSwitch.isChecked();
        boolean saveProvider = saveSwitch.isChecked();

        // Persist the form so the redirect handler (which may run in a fresh
        // process) can rebuild the exchange request and the saved credential.
        OAuthConfig.saveForm(this, selectedPreset, authorizeUrl, tokenUrl, clientId,
                clientSecret, scopes, redirectUri, apiBase, useExternal, saveProvider);

        String providerName = OAuthConfig.PRESETS[selectedPreset].name;
        String state = OAuthCapture.generateState();
        String verifier = null;
        String challenge = null;
        if (usePkce) {
            OAuthCapture.Pkce pkce = OAuthCapture.generatePkce();
            verifier = pkce.verifier;
            challenge = pkce.challenge;
        }
        OAuthConfig.savePending(this, verifier, state, providerName);

        Uri authorize = buildAuthorizeUri(authorizeUrl, clientId, redirectUri,
                scopes, state, challenge);
        if (authorize == null) {
            Toast.makeText(this, R.string.oauth_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        if (useExternal) {
            status.setText(R.string.oauth_status_external);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, authorize));
            } catch (Exception exception) {
                status.setText(getString(R.string.oauth_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
            }
            return;
        }
        status.setText(R.string.oauth_status_starting);
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        prepareWebView();
        pendingAuthorizeUrl = authorize.toString();
        webView.loadUrl(pendingAuthorizeUrl);
    }

    /**
     * Runs an authorization-code provider through the gateway.
     *
     * <p>{@code GET /api/oauth/{provider}/authorize} returns a ready-to-open
     * authorization URL together with the {@code state} and PKCE
     * {@code codeVerifier} the gateway generated. The user approves the
     * consent page, the redirect comes back to the app, and the app hands the
     * code to {@code POST /api/oauth/{provider}/exchange} — the gateway swaps
     * it for tokens and persists the connection, so the app never holds a raw
     * access token.
     */
    private void startGatewayAuthorizationCodeFlow(final OAuthConfig.Preset preset) {
        startGatewayAuthorizationCodeFlow(preset, null);
    }

    /**
     * Runs the plain authorization-code flow: the gateway builds the URL, the
     * user approves it in a browser and the redirect comes back to the app,
     * which hands the code to the gateway for exchange.
     *
     * <p>When the runtime's answer is already at hand it is reused instead of
     * asking a second time. That matters: the runtime mints a fresh PKCE
     * verifier and a fresh state on every call, so a second {@code /authorize}
     * would produce a URL whose challenge no longer matches the verifier the
     * app holds.
     */
    private void startGatewayAuthorizationCodeFlow(final OAuthConfig.Preset preset,
                                                   final RootfsApi.FlowInfo known) {
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        importPanel.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.oauth_status_starting);
        activeProviderKey = preset.providerKey;

        final String redirectUri = OAuthConfig.DEFAULT_REDIRECT_URI;
        executor.execute(() -> {
            final RootfsApi.AuthorizeInfo info;
            if (known != null && known.authUrl != null && !known.authUrl.isEmpty()) {
                info = new RootfsApi.AuthorizeInfo(known.authUrl, known.state,
                        known.codeVerifier, known.redirectUri, known.flowType);
            } else {
                try {
                    info = RootfsApi.requestAuthorize(this, preset.providerKey, redirectUri);
                } catch (Exception exception) {
                    postError(getString(R.string.oauth_failed,
                            exception.getMessage() == null ? "" : exception.getMessage()));
                    return;
                }
            }
            // Persist the verifier and state so the redirect handler — which
            // may run in a fresh process — can complete the exchange.
            OAuthConfig.savePending(this, info.codeVerifier, info.state, preset.name);
            // The runtime may resolve a different redirect URI than the one
            // asked for (Claude pins its own site), and it knows whether the
            // exchange needs a PKCE verifier. Both have to survive a restart.
            OAuthConfig.savePendingRedirect(this,
                    info.redirectUri != null ? info.redirectUri : redirectUri);
            OAuthConfig.savePendingFlowType(this, info.flowType);
            OAuthConfig.savePendingFlow(this, preset.providerKey, preset.flowKind);
            OAuthConfig.saveForm(this, selectedPreset, preset.authorizeUrl,
                    preset.tokenUrl, "", "", preset.scopes, redirectUri,
                    preset.apiBaseUrl, externalSwitch.isChecked(), saveSwitch.isChecked());

            mainHandler.post(() -> {
                status.setText(R.string.oauth_status_external);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.authUrl)));
                } catch (Exception exception) {
                    status.setText(getString(R.string.oauth_failed,
                            exception.getMessage() == null ? "" : exception.getMessage()));
                }
            });
        });
    }

    /**
     * Runs Zed's native-app sign-in.
     *
     * <p>Zed does not speak plain OAuth. The runtime generates an RSA key
     * pair, builds a {@code https://zed.dev/native_app_signin} URL that
     * carries the public key and the port to call back on, and hands the
     * private key to the app disguised as a "code verifier". After the user
     * signs in, Zed redirects the browser to
     * {@code http://127.0.0.1:<port>/?user_id=…&access_token=…}, where the
     * access token is encrypted to the public key.
     *
     * <p>The runtime cannot run the listener itself — it refuses
     * {@code start-callback-server} for this provider — and the dashboard's
     * only fallback is to have the user copy the callback URL out of the
     * browser by hand. The app does better: it binds the port, captures the
     * redirect and posts the whole URL back to the runtime, which decrypts
     * the token with the private key and stores the connection.
     *
     * <p>So the order matters: the listener has to be up <em>before</em> the
     * browser is opened, otherwise the redirect lands on a closed port and
     * Zed's sign-in silently fails.
     */
    private void startZedNativeAppFlow(final OAuthConfig.Preset preset,
                                        final RootfsApi.FlowInfo flow) {
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        importPanel.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.oauth_status_starting);
        completing = true;
        activeProviderKey = preset.providerKey;

        executor.execute(() -> {
            // The runtime generates a fresh key pair on every /authorize call
            // and returns the matching private key as the "code verifier".
            // Probing and then asking again would hand the app a public key
            // from one call and a private key from another, and the exchange
            // would fail to decrypt. So the probe response is used as-is.
            final String authUrl = flow.authUrl;
            final String privateKey = flow.codeVerifier;
            final String redirectUri = flow.redirectUri != null && !flow.redirectUri.isEmpty()
                    ? flow.redirectUri
                    : (flow.effectiveRedirectUri() != null ? flow.effectiveRedirectUri()
                            : "http://127.0.0.1:" + ZedNativeAppListener.DEFAULT_PORT + "/");
            final int port = flow.appListenerPort() > 0
                    ? flow.appListenerPort()
                    : portOf(redirectUri);

            if (authUrl == null || authUrl.isEmpty()) {
                postError(getString(R.string.oauth_failed,
                        "runtime did not return a Zed authorization URL"));
                return;
            }
            // The private key has to survive a process death: the browser
            // sign-in can take minutes, and the exchange is useless without
            // the key that matches the public key Zed encrypted to.
            OAuthConfig.savePending(this, privateKey, flow.state, preset.name);
            OAuthConfig.savePendingRedirect(this, redirectUri);
            OAuthConfig.savePendingFlowType(this, flow.flowType);
            OAuthConfig.savePendingFlow(this, preset.providerKey,
                    OAuthConfig.FlowKind.ZED_NATIVE_APP);

            mainHandler.post(() -> {
                if (port <= 0) {
                    postError(getString(R.string.oauth_failed,
                            "runtime did not report a Zed callback port"));
                    return;
                }
                status.setText(getString(R.string.oauth_status_zed_listening, port));
                zedListener = new ZedNativeAppListener();
                zedListener.start(port, new ZedNativeAppListener.Callback() {
                    @Override
                    public void onResult(ZedNativeAppListener.Result result) {
                        mainHandler.post(() -> completeZedSignIn(
                                preset, result, redirectUri, privateKey));
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            if (!completing) {
                                return;
                            }
                            postError(getString(R.string.oauth_failed, message));
                        });
                    }
                });
                // Only now open the browser: the callback has to have
                // somewhere to land.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                } catch (Exception exception) {
                    stopZedListener();
                    postError(getString(R.string.oauth_failed,
                            exception.getMessage() == null ? "" : exception.getMessage()));
                }
            });
        });
    }

    /**
     * Hands Zed's captured callback URL to the runtime, which decrypts the
     * access token and stores the connection.
     */
    private void completeZedSignIn(OAuthConfig.Preset preset,
                                   ZedNativeAppListener.Result result,
                                   String redirectUri, String privateKey) {
        if (!completing) {
            return;
        }
        stopZedListener();
        status.setText(R.string.oauth_status_exchanging);
        executor.execute(() -> {
            try {
                RootfsApi.exchangeZedNativeApp(this, result.callbackUrl,
                        redirectUri, privateKey);
            } catch (Exception exception) {
                postError(getString(R.string.oauth_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
                return;
            }
            finishGatewayFlow(preset.name);
        });
    }

    /** Stops the Zed loopback listener, if one is running. */
    private void stopZedListener() {
        ZedNativeAppListener listener = zedListener;
        zedListener = null;
        if (listener != null) {
            listener.stop();
        }
    }

    /** Extracts the port out of a loopback URL, or 0 when there is none. */
    private static int portOf(String url) {
        if (url == null) {
            return 0;
        }
        int index = url.indexOf("://");
        if (index < 0) {
            return 0;
        }
        String rest = url.substring(index + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int colon = rest.lastIndexOf(':');
        if (colon <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Runs a provider whose redirect URI is pinned to a fixed loopback port
     * (Codex on 1455, xAI on 56121, Openference on 56123).
     *
     * <p>The gateway owns the listener: it starts a loopback server, binds the
     * authorization URL to it and waits there. The app opens that URL in the
     * system browser and then asks the gateway, once every couple of seconds,
     * whether the redirect has arrived. The gateway performs the code
     * exchange and persists the connection itself, so the app never sees the
     * authorization code.
     */
    private void startCallbackServerFlow(final OAuthConfig.Preset preset) {
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        importPanel.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.oauth_status_starting);
        completing = true;
        activeProviderKey = preset.providerKey;

        executor.execute(() -> {
            final RootfsApi.CallbackServerInfo info;
            try {
                info = RootfsApi.startCallbackServer(this, preset.providerKey);
            } catch (Exception exception) {
                postError(getString(R.string.oauth_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
                return;
            }
            OAuthConfig.savePendingFlow(this, preset.providerKey, preset.flowKind);
            OAuthConfig.savePending(this, info.codeVerifier, null, preset.name);

            mainHandler.post(() -> {
                status.setText(R.string.oauth_status_external);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.authUrl)));
                } catch (Exception exception) {
                    status.setText(getString(R.string.oauth_failed,
                            exception.getMessage() == null ? "" : exception.getMessage()));
                }
            });

            pollCallbackServer(preset, System.currentTimeMillis() + CALLBACK_TIMEOUT_MS);
        });
    }

    /**
     * Falls back to the gateway's callback server when a device-code
     * request fails.
     *
     * <p>The runtime advertises {@code flowType:"device_code"} for some
     * providers whose device endpoint is not usable — Grok CLI answers
     * {@code 500 fetch failed} on {@code /device-code} while its
     * {@code /start-callback-server} works fine. The official dashboard
     * handles this by showing two buttons; the app tries the device code
     * first and quietly switches to the callback server when the device
     * endpoint is broken.
     *
     * @return {@code true} when a callback-server flow was started, so the
     *         caller must not report the original failure.
     */
    private boolean tryCallbackServerFallback(final String providerKey,
                                              final String providerName) {
        final RootfsApi.CallbackServerInfo info;
        try {
            info = RootfsApi.startCallbackServer(this, providerKey);
        } catch (Exception exception) {
            return false; // the runtime cannot serve it either
        }
        if (info == null || info.authUrl == null || info.authUrl.isEmpty()) {
            return false;
        }
        mainHandler.post(() -> {
            completing = true;
            activeProviderKey = providerKey;
            OAuthConfig.savePendingFlow(this, providerKey,
                    OAuthConfig.FlowKind.CALLBACK_SERVER);
            OAuthConfig.savePending(this, info.codeVerifier, null, providerName);
            configPanel.setVisibility(View.GONE);
            webView.setVisibility(View.GONE);
            importPanel.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.oauth_status_external);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.authUrl)));
            } catch (Exception exception) {
                postError(getString(R.string.oauth_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
            }
        });
        // The poll loop must run off the main thread like the normal path.
        executor.execute(() -> pollCallbackServer(
                new OAuthConfig.Preset(providerName, providerKey,
                        OAuthConfig.FlowKind.CALLBACK_SERVER, "", "", "", "",
                        false, true),
                System.currentTimeMillis() + CALLBACK_TIMEOUT_MS));
        return true;
    }

    /**
     * Asks the gateway whether the pinned-port callback has been captured.
     *
     * <p>The gateway answers {@code {"success":false,"pending":true}} until the
     * browser redirects back, and {@code {"success":false,"error":"no_server"}}
     * when its listener died — the latter is fatal and must not be retried
     * until the code expires.
     */
    private void pollCallbackServer(final OAuthConfig.Preset preset, final long deadline) {
        if (!completing) {
            return;
        }
        if (System.currentTimeMillis() >= deadline) {
            postError(getString(R.string.oauth_device_expired));
            return;
        }
        RootfsApi.PollResult result;
        try {
            result = RootfsApi.pollCallback(this, preset.providerKey);
        } catch (Exception exception) {
            postError(getString(R.string.oauth_failed,
                    exception.getMessage() == null ? "" : exception.getMessage()));
            return;
        }
        if (result.success) {
            finishGatewayFlow(preset.name);
            return;
        }
        if (!result.pending) {
            postError(getString(R.string.oauth_failed,
                    result.error == null ? "" : result.error));
            return;
        }
        mainHandler.postDelayed(
                () -> pollCallbackServer(preset, deadline), CALLBACK_POLL_MS);
    }

    /**
     * Explains that a provider cannot be logged into from the app at all.
     *
     * <p>Used for the providers the runtime refuses outright and for which
     * no import route exists either — Qoder and GitLab Duo. Offering a paste
     * box there would be worse than offering nothing: the user would paste a
     * valid token only to be told "import-token not supported for provider:
     * qoder" by the gateway. The dashboard handles these two the same way,
     * as providers that take a manually entered key.
     */
    private void showManualKeyOnly(OAuthConfig.Preset preset, String reason) {
        activeProviderKey = preset.providerKey;
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        importPanel.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(reason != null && !reason.isEmpty()
                ? getString(R.string.oauth_manual_key, preset.name, reason)
                : getString(R.string.oauth_manual_key, preset.name,
                        getString(R.string.oauth_manual_key_hint)));
    }

    /**
     * Reveals the paste field for providers that have no browser flow.
     *
     * <p>Claude, Cursor, Trae and Devin all redirect to a site the app cannot
     * intercept, so the only way in is to hand the gateway a token the user
     * already holds. The gateway normalises it and stores the connection.
     *
     * @param reason when the runtime refused the provider outright, the
     *     explanation it gave (for example "Qoder browser OAuth is
     *     experimental and disabled by default"); {@code null} when the
     *     provider simply has no browser flow.
     */
    private void showImportTokenField(OAuthConfig.Preset preset, String reason) {
        activeProviderKey = preset.providerKey;
        OAuthConfig.savePendingFlow(this, preset.providerKey, preset.flowKind);
        OAuthConfig.savePending(this, null, null, preset.name);
        configPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        importPanel.setVisibility(View.VISIBLE);
        importTokenInput.setText("");
        status.setText(reason != null && !reason.isEmpty()
                ? getString(R.string.oauth_import_blocked, preset.name, reason)
                : getString(R.string.oauth_import_hint, preset.name));
    }

    /**
     * Hands a pasted token to the gateway.
     *
     * <p>Which route the token goes to depends on the provider: Claude, Grok
     * CLI and Devin accept the generic {@code POST /api/oauth/{p}/import-token},
     * while Cursor and Trae have dedicated routes of their own and are
     * rejected by the generic one. The gateway stores the connection either
     * way, so the app never has to know the provider's token format.
     */
    private void submitImportedToken() {
        final String providerKey = activeProviderKey;
        if (providerKey == null || providerKey.isEmpty()) {
            Toast.makeText(this, R.string.oauth_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        final String token = importTokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.oauth_import_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        final String providerName = OAuthConfig.pendingName(this);
        final OAuthConfig.ImportStyle importStyle = pendingImportStyle(providerKey);
        status.setText(R.string.oauth_status_exchanging);
        executor.execute(() -> {
            try {
                RootfsApi.PollResult result;
                switch (importStyle) {
                    case CURSOR:
                        result = RootfsApi.importCursorToken(this, token);
                        break;
                    case TRAE:
                        result = RootfsApi.importTraeToken(this, token);
                        break;
                    case GENERIC:
                    default:
                        result = RootfsApi.importToken(this, providerKey, token);
                        break;
                }
                if (result.success) {
                    mainHandler.post(() -> {
                        importPanel.setVisibility(View.GONE);
                        String name = providerName == null ? providerKey : providerName;
                        status.setText(getString(R.string.oauth_import_success, name));
                        Toast.makeText(this, R.string.oauth_success, Toast.LENGTH_LONG).show();
                        mainHandler.postDelayed(this::finish, 1500);
                    });
                } else {
                    postError(getString(R.string.oauth_import_failed,
                            result.error == null ? "" : result.error));
                }
            } catch (Exception exception) {
                postError(getString(R.string.oauth_import_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
            }
        });
    }

    /**
     * The import route to use for a provider.
     *
     * <p>Read back from the preset catalogue rather than hard-coded here, so
     * the button label and the request always agree. The lookup is per
     * provider and not per preset index, because a provider can be listed
     * more than once — Grok CLI is both a device-code and an import-token
     * provider — and only the import-token entry knows which route the
     * gateway expects.
     */
    private OAuthConfig.ImportStyle pendingImportStyle(String providerKey) {
        return OAuthConfig.importStyleFor(providerKey);
    }

    private Uri buildAuthorizeUri(String authorizeUrl, String clientId, String redirectUri,
                                  String scopes, String state, String challenge) {
        Uri base;
        try {
            base = Uri.parse(authorizeUrl);
        } catch (Exception exception) {
            return null;
        }
        if (base == null || base.getScheme() == null) {
            return null;
        }
        Uri.Builder builder = base.buildUpon();
        builder.appendQueryParameter("response_type", "code");
        builder.appendQueryParameter("client_id", clientId);
        builder.appendQueryParameter("redirect_uri", redirectUri);
        if (!scopes.isEmpty()) {
            builder.appendQueryParameter("scope", scopes);
        }
        builder.appendQueryParameter("state", state);
        if (challenge != null) {
            builder.appendQueryParameter("code_challenge", challenge);
            builder.appendQueryParameter("code_challenge_method", "S256");
        }
        return builder.build();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void prepareWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // A desktop UA keeps consent pages from serving a cramped mobile layout,
        // and some providers gate embedded-WebViews by UA.
        settings.setUserAgentString(desktopUserAgent());
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        cookies.setAcceptFileSchemeCookies(false);
        webView.setWebViewClient(new ConsentWebViewClient());
        webView.setBackgroundColor(0xFF0B0F1A);
    }

    /**
     * Replaces a renderer-dead consent WebView with a fresh one.
     *
     * <p>The default (super) behaviour kills the whole app process, which takes
     * the gateway down with a consent page. Returning true from
     * {@link ConsentWebViewClient#onRenderProcessGone} routes here instead: the
     * dead WebView is torn down and a fresh one reloads the authorization URL.
     *
     * <p>Reloading is safe: the flow's {@code state} and PKCE verifier stay
     * pending in {@link OAuthConfig} until a callback actually arrives with
     * them, so re-rendering the consent page does not consume anything. If the
     * URL is unknown (crash before the first load), the user is dropped back on
     * the config panel to start over.
     */
    private void rebuildConsentWebView() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        parent.removeView(webView);
        try {
            webView.destroy();
        } catch (Exception ignored) {
            // A renderer-dead WebView can throw from destroy(); the view is
            // being discarded either way.
        }
        webView = new WebView(this);
        parent.addView(webView, params);
        prepareWebView();

        if (pendingAuthorizeUrl != null && !pendingAuthorizeUrl.isEmpty()) {
            webView.setVisibility(View.VISIBLE);
            configPanel.setVisibility(View.GONE);
            status.setText(R.string.oauth_status_starting);
            webView.loadUrl(pendingAuthorizeUrl);
        } else {
            webView.setVisibility(View.GONE);
            configPanel.setVisibility(View.VISIBLE);
            status.setText(R.string.oauth_renderer_recovered);
        }
    }

    private static String desktopUserAgent() {
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }

    /**
     * Captures the OAuth redirect while letting ordinary consent-page navigations
     * render inside the WebView.
     */
    private final class ConsentWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        /**
         * The renderer died; the gateway must not die with it.
         *
         * <p>The default (super) behaviour kills the whole app process, which
         * takes the OmniRoute gateway down together with a consent page. A
         * Chromium-internal CHECK on the in-process GPU thread (SIGTRAP) is
         * exactly the crash this guards against. Returning true means the app
         * handles the death itself: the dead WebView is torn down and a fresh
         * one reloads the authorization URL, so the user can retry the consent
         * instead of losing the gateway.
         */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (webView != null && webView == view) {
                Log.w(TAG, "OAuth WebView renderer died (crashed=" + detail.didCrash()
                        + "); rebuilding it instead of killing the process");
                rebuildConsentWebView();
            }
            return true;
        }

        private boolean handleNavigation(Uri uri) {
            if (uri == null || uri.getScheme() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase();
            if (OAuthConfig.CALLBACK_SCHEME.equalsIgnoreCase(scheme)) {
                handleCallback(uri);
                return true;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (isLoopbackCallback(uri)) {
                    handleCallback(uri);
                    return true;
                }
                return false;
            }
            // A non-http(s) scheme that is not ours (e.g. a provider's own app
            // scheme): hand it to the system and stop the WebView from erroring.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                // No handler; nothing useful to do.
            }
            return true;
        }
    }

    private static boolean isLoopbackCallback(Uri uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (!loopback) {
            return false;
        }
        return uri.getQueryParameter("code") != null
                || uri.getQueryParameter("access_token") != null
                || uri.getQueryParameter("error") != null
                || hasFragmentToken(uri);
    }

    private static boolean hasFragmentToken(Uri uri) {
        String fragment = uri.getFragment();
        if (fragment == null) {
            return false;
        }
        return fragment.contains("access_token=") || fragment.contains("code=")
                || fragment.contains("error=");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && OAuthConfig.CALLBACK_SCHEME.equalsIgnoreCase(data.getScheme())) {
            setIntent(intent);
            handleCallback(data);
        }
    }

    private void handleCallback(Uri redirect) {
        if (completing) {
            return;
        }
        completing = true;
        status.setText(R.string.oauth_status_exchanging);
        executor.execute(() -> {
            try {
                complete(redirect);
            } catch (Exception exception) {
                postError(exception.getMessage());
            }
        });
    }

    private void complete(Uri redirect) {
        OAuthCapture.Result result = OAuthCapture.parse(redirect);
        if (result.isError()) {
            postError(result.error
                    + (result.errorDescription == null ? "" : ": " + result.errorDescription));
            return;
        }
        String pendingState = OAuthConfig.pendingState(this);
        if (pendingState != null && result.state != null && !pendingState.equals(result.state)) {
            postError(getString(R.string.oauth_state_mismatch));
            return;
        }
        // A gateway-driven provider: the gateway holds the client id and the
        // PKCE verifier, and it stores the connection itself. Exchanging the
        // code here keeps the raw access token inside the runtime.
        if (activeProviderKey != null && !activeProviderKey.isEmpty() && result.hasCode()) {
            // The runtime resolved the redirect URI and the flow type when it
            // built the authorization URL. Repeating the URI verbatim matters
            // — a provider that pins its own redirect rejects a mismatched
            // one — and the verifier is only wanted by the PKCE providers.
            String exchangeRedirect = OAuthConfig.pendingRedirect(this);
            if (exchangeRedirect == null || exchangeRedirect.isEmpty()) {
                exchangeRedirect = OAuthConfig.DEFAULT_REDIRECT_URI;
            }
            String verifier = OAuthConfig.pendingVerifier(this);
            if (!"authorization_code_pkce".equals(OAuthConfig.pendingFlowType(this))) {
                verifier = null;
            }
            try {
                RootfsApi.exchange(this, activeProviderKey, result.code,
                        exchangeRedirect, verifier);
            } catch (Exception exception) {
                postError(getString(R.string.oauth_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
                return;
            }
            finishGatewayFlow(OAuthConfig.pendingName(this));
            return;
        }
        OAuthCapture.Result tokens = result;
        if (!result.hasToken() && result.hasCode()) {
            String tokenUrl = OAuthConfig.tokenUrl(this);
            if (tokenUrl.isEmpty()) {
                postError(getString(R.string.oauth_no_token_url));
                return;
            }
            String response;
            try {
                response = OAuthCapture.exchange(tokenUrl, result.code,
                        OAuthConfig.redirectUri(this), OAuthConfig.clientId(this),
                        OAuthConfig.clientSecret(this), OAuthConfig.pendingVerifier(this));
            } catch (Exception exception) {
                postError(exception.getMessage());
                return;
            }
            tokens = OAuthCapture.parseTokenResponse(response);
            if (tokens.isError() || !tokens.hasToken()) {
                postError(tokens.error == null ? "no access_token" : tokens.error);
                return;
            }
        }
        if (!tokens.hasToken()) {
            postError(getString(R.string.oauth_no_code));
            return;
        }
        saveResult(tokens);
    }

    /** Reports a successful gateway exchange and closes the screen. */
    private void finishGatewayFlow(String providerName) {
        OAuthConfig.clearPending(this);
        String name = providerName != null ? providerName : OAuthConfig.pendingName(this);
        mainHandler.post(() -> {
            status.setText(getString(R.string.oauth_device_saved,
                    name == null ? activeProviderKey : name));
            Toast.makeText(this, R.string.oauth_success, Toast.LENGTH_LONG).show();
            completing = false;
            mainHandler.postDelayed(this::finish, 1500);
        });
    }

    /**
     * Drives a device-code (RFC 8628) flow entirely through the rootfs gateway:
     * request a one-time code, show it + the verification URL, then poll until
     * the user authorizes or the code expires. The gateway persists the tokens
     * into {@code storage.sqlite} itself, so on success there is nothing for
     * {@link CredentialStore} to do — the connection just appears.
     */
    private void startDeviceFlow(final String providerKey, final String providerName) {
        configPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        importPanel.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.oauth_status_requesting_code);
        completing = true;
        activeProviderKey = providerKey;

        executor.execute(() -> {
            final RootfsApi.DeviceCode code;
            try {
                code = RootfsApi.requestDeviceCode(this, providerKey);
            } catch (Exception exception) {
                // The runtime reports "device_code" for a few providers it
                // cannot actually mint a device code for — Grok CLI is the
                // one that matters: its upstream device endpoint fails while
                // its callback server works. The official dashboard offers
                // both buttons for exactly that reason. Rather than dying
                // here, offer the callback server when the runtime can
                // serve it.
                if (tryCallbackServerFallback(providerKey, providerName)) {
                    return;
                }
                postError(getString(R.string.oauth_device_code_failed,
                        exception.getMessage() == null ? "" : exception.getMessage()));
                return;
            }
            // Persist provider identity so the poll loop survives a recreate.
            OAuthConfig.savePending(this, code.codeVerifier, null, providerName);
            OAuthConfig.savePendingFlow(this, providerKey,
                    OAuthConfig.FlowKind.DEVICE_CODE);

            final String userCode = code.userCode == null ? "" : code.userCode;
            final String verifyUrl = code.verificationUri == null ? "" : code.verificationUri;
            final int interval = Math.max(code.intervalSeconds, 1);
            final long deadline = System.currentTimeMillis()
                    + (code.expiresIn > 0 ? code.expiresIn : 900) * 1000L;

            mainHandler.post(() -> {
                String display = userCode.isEmpty() ? verifyUrl
                        : getString(R.string.oauth_device_prompt, userCode, verifyUrl);
                status.setText(display);
                // Copy the user code to the clipboard: the verification page
                // asks for it, and typing it from a screenshot is a chore.
                if (!userCode.isEmpty()) {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData
                                .newPlainText("OAuth device code", userCode));
                        Toast.makeText(this,
                                getString(R.string.oauth_device_copied, userCode),
                                Toast.LENGTH_LONG).show();
                    }
                }
                // Open the verification page in the system browser. The
                // in-app WebView is deliberately not used: consent pages break
                // inside an embedded browser, and the user has to sign in there.
                if (!verifyUrl.isEmpty()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)));
                    } catch (Exception exception) {
                        Toast.makeText(this,
                                getString(R.string.oauth_device_open_failed, verifyUrl),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });

            pollDeviceToken(providerKey, code, interval, deadline);
        });
    }

    /** Polls the gateway until the user authorizes or the device code expires. */
    private void pollDeviceToken(final String providerKey,
                                final RootfsApi.DeviceCode code,
                                final int intervalSeconds,
                                final long deadline) {
        if (!completing) {
            return; // cancelled (back / activity destroyed)
        }
        if (System.currentTimeMillis() >= deadline) {
            postError(getString(R.string.oauth_device_expired));
            return;
        }
        RootfsApi.PollResult result;
        try {
            result = RootfsApi.poll(this, providerKey, code);
        } catch (Exception exception) {
            postError(getString(R.string.oauth_failed,
                    exception.getMessage() == null ? "" : exception.getMessage()));
            return;
        }
        if (result.success) {
            OAuthConfig.clearPending(this);
            final String name = result.name == null ? providerKey : result.name;
            final String email = result.email;
            mainHandler.post(() -> {
                status.setText(getString(R.string.oauth_device_success,
                        name, email == null ? "" : email));
                Toast.makeText(this, R.string.oauth_success, Toast.LENGTH_LONG).show();
                configPanel.setVisibility(View.VISIBLE);
                completing = false;
                // Give the user a beat to read the success line, then close.
                mainHandler.postDelayed(this::finish, 1500);
            });
            return;
        }
        // The gateway marks "still waiting for the user" with `pending:true`.
        // Only a hard error stops the loop.
        //
        // This is the rule the official dashboard uses, and it is the only
        // rule that works for every provider: a poll response without an
        // error field means "not yet", not "failed". WorkBuddy's upstream
        // answers `{"code":11217,"msg":"11217:login ing..."}` while the user
        // has not approved yet, and the gateway forwards that as a response
        // with no error field at all.
        if (!result.pending) {
            postError(getString(R.string.oauth_failed,
                    result.error == null ? "" : result.error));
            return;
        }
        // slow_down asks us to back off; otherwise keep the provider's cadence.
        final int wait = (result.error != null && result.error.contains("slow_down"))
                ? intervalSeconds + 5 : intervalSeconds;
        mainHandler.postDelayed(() ->
                pollDeviceToken(providerKey, code, wait, deadline), wait * 1000L);
    }

    private void saveResult(OAuthCapture.Result tokens) {
        String name = OAuthConfig.pendingName(this);
        if (name == null || name.isEmpty()) {
            name = "OAuth";
        }
        if (OAuthConfig.saveProvider(this)) {
            String url = OAuthConfig.apiBase(this);
            if (url.isEmpty()) {
                url = OAuthConfig.tokenUrl(this);
            }
            CredentialStore.add(this, CredentialStore.Kind.PROVIDER,
                    name, url, tokens.accessToken);
        }
        if (tokens.refreshToken != null && !tokens.refreshToken.isEmpty()) {
            OAuthConfig.saveRefreshToken(this, name, tokens.refreshToken, tokens.expiresIn);
        }
        OAuthConfig.clearPending(this);
        mainHandler.post(() -> {
            Toast.makeText(this, R.string.oauth_success, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void postError(String message) {
        OAuthConfig.clearPending(this);
        mainHandler.post(() -> {
            String text = getString(R.string.oauth_failed, message == null ? "" : message);
            status.setText(text);
            completing = false;
            configPanel.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        });
    }

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
        stopZedListener();
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
