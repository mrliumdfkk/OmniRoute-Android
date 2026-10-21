package com.example.omniroute;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Thin HTTP client for the OmniRoute Node gateway running inside the rootfs.
 *
 * <p>The rootfs already implements every OAuth/device-code flow upstream (see
 * {@code src/app/api/oauth/[provider]/[action]/route.ts}): requesting a device
 * code, polling for a token, exchanging an authorization code, and persisting
 * the result into {@code storage.sqlite}. This class is the Android-side bridge
 * to those endpoints so the app does not reimplement (and drift from) the
 * server logic. The dashboard is always reached at {@code 127.0.0.1:PORT}.
 */
final class RootfsApi {
    private RootfsApi() {}

    /** A parsed device-code response from {@code GET /api/oauth/{p}/device-code}. */
    static final class DeviceCode {
        final String deviceCode;
        final String userCode;
        final String verificationUri;
        final int intervalSeconds;
        final int expiresIn;
        final String codeVerifier;       // server-side PKCE for poll (some providers)
        /**
         * Provider-specific extras the poll needs.
         *
         * <p>Kiro and Amazon Q register an OIDC client on the fly, so the
         * device-code response carries the freshly registered client id, its
         * secret, the region and the auth method. The gateway hands this blob
         * straight to the provider's pollToken(), so the app has to echo it
         * back verbatim in the poll's {@code extraData} field — otherwise the
         * poll fails with an "invalid client" error from the OIDC endpoint.
         *
         * <p>Null for every other provider.
         */
        final JSONObject extraData;

        DeviceCode(String deviceCode, String userCode, String verificationUri,
                   int intervalSeconds, int expiresIn, String codeVerifier) {
            this(deviceCode, userCode, verificationUri, intervalSeconds, expiresIn,
                    codeVerifier, null);
        }

        DeviceCode(String deviceCode, String userCode, String verificationUri,
                   int intervalSeconds, int expiresIn, String codeVerifier,
                   JSONObject extraData) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.intervalSeconds = intervalSeconds;
            this.expiresIn = expiresIn;
            this.codeVerifier = codeVerifier;
            this.extraData = extraData;
        }
    }

    /** A poll result from {@code POST /api/oauth/{p}/poll}. */
    static final class PollResult {
        final boolean success;
        final boolean pending;          // still waiting for the user to approve
        final String error;            // upstream error string when !success
        final String name;            // display name of the new connection
        final String email;

        PollResult(boolean success, String error, String name, String email) {
            this(success, false, error, name, email);
        }

        PollResult(boolean success, boolean pending, String error, String name, String email) {
            this.success = success;
            this.pending = pending;
            this.error = error;
            this.name = name;
            this.email = email;
        }
    }

    /** Base URL of the in-rootfs gateway, e.g. {@code http://127.0.0.1:20128}. */
    static String baseUrl(Context context) {
        return AppSettings.dashboardUrl(context);
    }

    // --- gateway authentication -------------------------------------------

    /**
     * The gateway guards every {@code /api/oauth/*} route behind a dashboard
     * session. Without a session cookie the runtime answers
     * {@code 401 {"error":{"message":"Authentication required"}}} — which used
     * to make every in-app OAuth attempt fail with a bare, unexplained 401.
     *
     * <p>The app therefore authenticates once and keeps the resulting session
     * cookie. The cookie is shared with the dashboard WebView (both live in the
     * same {@code CookieManager} store), so a session the user already
     * established in the dashboard is reused instead of prompting again.
     */
    private static final String COOKIE_NAME = "auth_token";
    private static final String PREFS = "omniroute-gateway";
    private static final String KEY_SESSION = "auth_token";
    private static final String KEY_CSRF = "csrf_token";

    /** Reads the cached dashboard session cookie, or {@code null}. */
    private static synchronized String cachedSession(Context context) {
        String value = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SESSION, null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static synchronized void cacheSession(Context context, String token) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SESSION, token)
                .apply();
    }

    /**
     * Returns the session cookie to send with gateway requests.
     *
     * <p>Order of preference:
     * <ol>
     *   <li>A session already established in the dashboard WebView — reused so
     *       the user is not asked for the dashboard password twice.</li>
     *   <li>The cached session from a previous App request.</li>
     *   <li>A fresh login using the dashboard password the runtime was seeded
     *       with.</li>
     * </ol>
     *
     * @return the cookie value, or {@code null} when the gateway is
     *     unreachable or refuses the password.
     */
    private static synchronized String sessionCookie(Context context) {
        String fromWebView = webViewSessionCookie(context);
        if (fromWebView != null) {
            cacheSession(context, fromWebView);
            return fromWebView;
        }
        String cached = cachedSession(context);
        if (cached != null) {
            return cached;
        }
        String fresh = login(context);
        if (fresh != null) {
            cacheSession(context, fresh);
        }
        return fresh;
    }

    /**
     * Reads the dashboard session cookie out of the WebView cookie store.
     *
     * <p>{@code CookieManager.getCookie} must run on a thread with a Looper, so
     * the caller has to be on the main thread; this returns {@code null}
     * otherwise rather than deadlocking.
     */
    private static String webViewSessionCookie(Context context) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            return null;
        }
        try {
            String jar = android.webkit.CookieManager.getInstance()
                    .getCookie(baseUrl(context));
            if (jar == null) {
                return null;
            }
            for (String part : jar.split(";")) {
                String entry = part.trim();
                if (entry.startsWith(COOKIE_NAME + "=")) {
                    String value = entry.substring(COOKIE_NAME.length() + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // A missing WebView provider must never break the OAuth flow.
        }
        return null;
    }

    /**
     * Logs into the dashboard and returns the session cookie value.
     *
     * <p>The runtime is seeded with {@code INITIAL_PASSWORD}, so the default
     * dashboard password is known. If the user changed it, the login fails and
     * the caller surfaces an actionable message rather than a bare 401.
     */
    private static String login(Context context) {
        String password = RootfsInstaller.DEFAULT_DASHBOARD_PASSWORD;
        try {
            String body = "{\"password\":\"" + password + "\"}";
            Response response = postJson(baseUrl(context) + "/api/auth/login", body);
            if (response.status < 200 || response.status >= 300) {
                return null;
            }
            String cookie = response.cookie(COOKIE_NAME);
            return cookie;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Fetches a CSRF token for a dashboard write.
     *
     * <p>Dashboard writes are guarded by a same-origin check plus a
     * session-bound CSRF token. The gateway exempts {@code /api/oauth/*} from
     * the origin check (it is called by the dashboard's own XHR layer), but
     * the token is still accepted, so sending it is harmless and future-proof.
     */
    private static String csrfToken(Context context) {
        try {
            JSONObject json = new JSONObject(getJson(context, baseUrl(context) + "/api/auth/csrf"));
            return optString(json, "token");
        } catch (Exception exception) {
            return null;
        }
    }

    /** {@code GET /api/oauth/{provider}/device-code}. Runs off the main thread. */
    static DeviceCode requestDeviceCode(Context context, String provider) throws IOException {
        String url = baseUrl(context) + "/api/oauth/" + Uri.encode(provider) + "/device-code";
        String body = getJson(context, url);
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("invalid JSON: " + body, e);
        }
        String dc = optString(json, "device_code");
        if (dc == null) {
            // Some providers name it differently; fall back before failing.
            dc = optString(json, "deviceCode");
        }
        if (dc == null) {
            throw new IOException(optString(json, "error") != null
                    ? optString(json, "error") : "no device_code in response");
        }
        // Kiro and Amazon Q return the freshly registered OIDC client
        // alongside the device code. Keep it: the poll needs it.
        JSONObject extraData = null;
        String clientId = optString(json, "_clientId");
        String clientSecret = optString(json, "_clientSecret");
        String region = optString(json, "_region");
        String authMethod = optString(json, "_authMethod");
        if (clientId != null || clientSecret != null || region != null) {
            extraData = new JSONObject();
            try {
                if (clientId != null) {
                    extraData.put("_clientId", clientId);
                }
                if (clientSecret != null) {
                    extraData.put("_clientSecret", clientSecret);
                }
                if (region != null) {
                    extraData.put("_region", region);
                }
                if (authMethod != null) {
                    extraData.put("_authMethod", authMethod);
                }
            } catch (Exception ignored) {
                extraData = null;
            }
        }
        return new DeviceCode(
                dc,
                optString(json, "user_code") != null ? optString(json, "user_code")
                        : optString(json, "userCode"),
                optString(json, "verification_uri") != null ? optString(json, "verification_uri")
                        : optString(json, "verificationUri"),
                json.optInt("interval", 5),
                json.optInt("expires_in", 900),
                optString(json, "code_verifier") != null ? optString(json, "code_verifier")
                        : optString(json, "codeVerifier"),
                extraData);
    }

    /**
     * {@code POST /api/oauth/{provider}/poll}. Returns a {@link PollResult}; when
     * {@code success} the connection is already persisted server-side.
     *
     * <h3>Why "still waiting" is detected defensively</h3>
     * <p>The gateway forwards the upstream provider's answer, and the upstream
     * providers do not agree on how to say "not yet":
     * <ul>
     *   <li>GitHub and Muse Code answer
     *       {@code {"success":false,"error":"authorization_pending","pending":true}}
     *       — an explicit error code plus the {@code pending} flag.</li>
     *   <li>WorkBuddy answers {@code {"success":false,"pending":false}} with
     *       <em>no</em> error field at all, because its upstream returns a
     *       numeric {@code code} plus a {@code msg}. The gateway forwards
     *       {@code error: undefined}, which JSON.stringify drops from the
     *       response body.</li>
     * </ul>
     * <p>The old implementation treated "no error field" as a hard failure and
     * aborted on the very first poll, so WorkBuddy could never be logged into.
     * The rule applied here matches the one the official dashboard uses:
     * <em>only a response that carries an error and is not flagged pending is
     * a failure</em>; everything else means "keep waiting".
     */
    static PollResult poll(Context context, String provider, DeviceCode code) throws IOException {
        String url = baseUrl(context) + "/api/oauth/" + Uri.encode(provider) + "/poll";
        JSONObject payload = new JSONObject();
        try {
            payload.put("deviceCode", code.deviceCode);
            if (code.codeVerifier != null) {
                payload.put("codeVerifier", code.codeVerifier);
            }
            // Kiro and Amazon Q need the client they registered on the fly.
            if (code.extraData != null) {
                payload.put("extraData", code.extraData);
            }
        } catch (Exception ignored) {
        }
        int status;
        String body;
        try {
            Response r = postJson(context, url, payload.toString());
            status = r.status;
            body = r.body;
        } catch (IOException io) {
            return new PollResult(false, io.getMessage(), null, null);
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception e) {
            return new PollResult(false, "invalid response: " + body, null, null);
        }
        boolean ok = status >= 200 && status < 300 && json.optBoolean("success", false);
        if (ok) {
            JSONObject connection = json.optJSONObject("connection");
            JSONObject tokens = json.optJSONObject("tokens");
            String name = tokens != null ? optString(tokens, "name") : null;
            String email = tokens != null ? optString(tokens, "email") : null;
            if (name == null && connection != null) {
                name = optString(connection, "displayName");
                if (name == null) {
                    name = optString(connection, "email");
                }
            }
            if (email == null && connection != null) {
                email = optString(connection, "email");
            }
            return new PollResult(true, null, name, email);
        }
        // The gateway reports "still waiting" through a dedicated `pending`
        // flag; `error` alone is ambiguous because it also carries the
        // slow_down signal and hard failures.
        boolean pending = json.optBoolean("pending", false);
        String err = optString(json, "error");
        if (err == null) {
            // No error field at all. The official dashboard treats this as
            // "still waiting", and so must we: WorkBuddy's upstream answers
            // with a numeric code while the user has not approved yet, and
            // the gateway forwards that as an absent error field.
            String description = optString(json, "errorDescription");
            if (description == null) {
                description = optString(json, "msg");
            }
            if (description == null) {
                description = optString(json, "code");
            }
            return new PollResult(false, true, description, null, null);
        }
        if (pending || "authorization_pending".equals(err) || "slow_down".equals(err)) {
            return new PollResult(false, true, err, null, null);
        }
        return new PollResult(false, false, err, null, null);
    }

    /**
     * {@code POST /api/oauth/{provider}/poll-callback}.
     *
     * <p>Used by the providers whose redirect URI is pinned to a fixed loopback
     * port (Codex on 1455, xAI on 56121, Grok on 56122, Openference on
     * 56123). The gateway runs the listener itself, so the app never sees the
     * authorization code — it only asks whether the gateway caught it yet.
     */
    static PollResult pollCallback(Context context, String provider) throws IOException {
        String url = baseUrl(context) + "/api/oauth/" + Uri.encode(provider) + "/poll-callback";
        Response response = postJson(context, url, "{}");
        JSONObject json;
        try {
            json = new JSONObject(response.body);
        } catch (Exception exception) {
            return new PollResult(false, "invalid response: " + response.body, null, null);
        }
        if (response.status >= 200 && response.status < 300 && json.optBoolean("success", false)) {
            JSONObject connection = json.optJSONObject("connection");
            String name = connection != null ? optString(connection, "displayName") : null;
            String email = connection != null ? optString(connection, "email") : null;
            return new PollResult(true, null, name, email);
        }
        boolean pending = json.optBoolean("pending", false);
        String error = optString(json, "error");
        if (error == null) {
            return new PollResult(false, true, null, null, null);
        }
        // "no_server" means the gateway's listener is gone; that is fatal and
        // must not be retried until the code expires.
        if ("no_server".equals(error)) {
            return new PollResult(false, false, "回调服务器未运行，请重新开始授权", null, null);
        }
        if (pending) {
            return new PollResult(false, true, error, null, null);
        }
        String description = optString(json, "errorDescription");
        return new PollResult(false, false, description != null ? description : error, null, null);
    }

    /**
     * {@code GET /api/oauth/{provider}/start-callback-server}.
     *
     * <p>Asks the gateway to open its loopback listener and to build the
     * authorization URL bound to it.
     */
    static final class CallbackServerInfo {
        final String authUrl;
        final String codeVerifier;
        final String redirectUri;
        final int serverPort;

        CallbackServerInfo(String authUrl, String codeVerifier, String redirectUri, int serverPort) {
            this.authUrl = authUrl;
            this.codeVerifier = codeVerifier;
            this.redirectUri = redirectUri;
            this.serverPort = serverPort;
        }
    }

    static CallbackServerInfo startCallbackServer(Context context, String provider)
            throws IOException {
        String url = baseUrl(context) + "/api/oauth/" + Uri.encode(provider)
                + "/start-callback-server";
        String body = getJson(context, url);
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("invalid JSON: " + body, e);
        }
        String authUrl = optString(json, "authUrl");
        if (authUrl == null) {
            throw new IOException(optString(json, "error") != null
                    ? optString(json, "error")
                    : "provider does not support a callback-server flow");
        }
        return new CallbackServerInfo(
                authUrl,
                optString(json, "codeVerifier"),
                optString(json, "redirectUri"),
                json.optInt("serverPort", 0));
    }

    /**
     * {@code POST /api/oauth/cursor/login/start}.
     *
     * <p>Starts Cursor's <em>hosted</em> deep-control login. The runtime
     * mints a login session and hands back a {@code loginUrl} the user opens
     * in a browser; approving it there makes the runtime poll
     * {@code api2.cursor.sh/auth/poll} and store the connection.
     *
     * <p>This is Cursor's real one-click path. The alternative — pasting an
     * access token — requires a token the user has to fish out of a
     * developer console, which is not a login at all.
     */
    static CursorLogin startCursorLogin(Context context) throws IOException {
        String url = baseUrl(context) + "/api/oauth/cursor/login/start";
        Response response = postJson(context, url, "{}");
        JSONObject json;
        try {
            json = new JSONObject(response.body);
        } catch (Exception e) {
            throw new IOException("invalid response: " + response.body, e);
        }
        if (response.status < 200 || response.status >= 300) {
            throw new IOException(describeError(response.body));
        }
        String sessionId = optString(json, "sessionId");
        String loginUrl = optString(json, "loginUrl");
        if (sessionId == null || loginUrl == null) {
            throw new IOException("no sessionId/loginUrl in response: " + response.body);
        }
        return new CursorLogin(sessionId, loginUrl, json.optInt("expiresInSeconds", 900));
    }

    /**
     * {@code POST /api/oauth/cursor/login/poll}.
     *
     * <p>Answers {@code {"status":"pending"}} until the user approves the
     * login in the browser, then {@code {"status":"ok","success":true,
     * "connection":{…}}}. A hard failure comes back as
     * {@code {"status":"error","error":"…"}}.
     */
    static PollResult pollCursorLogin(Context context, String sessionId)
            throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
        } catch (Exception ignored) {
        }
        Response response = postJson(context,
                baseUrl(context) + "/api/oauth/cursor/login/poll", payload.toString());
        JSONObject json;
        try {
            json = new JSONObject(response.body);
        } catch (Exception e) {
            return new PollResult(false, "invalid response: " + response.body, null, null);
        }
        String status = optString(json, "status");
        if ("ok".equals(status)) {
            JSONObject connection = json.optJSONObject("connection");
            String email = connection != null ? optString(connection, "email") : null;
            return new PollResult(true, null, email, email);
        }
        if ("error".equals(status)) {
            return new PollResult(false, false, optString(json, "error"), null, null);
        }
        // "pending" — the user has not approved the login yet.
        return new PollResult(false, true, null, null, null);
    }

    /**
     * {@code POST /api/oauth/cursor/login/cancel}.
     *
     * <p>Best-effort cleanup of a login session the user abandoned.
     */
    static void cancelCursorLogin(Context context, String sessionId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sessionId", sessionId);
        } catch (Exception ignored) {
        }
        try {
            postJson(context, baseUrl(context) + "/api/oauth/cursor/login/cancel",
                    payload.toString());
        } catch (IOException ignored) {
            // The session expires on its own; a failed cancel is harmless.
        }
    }

    /** A Cursor deep-control login session. */
    static final class CursorLogin {
        final String sessionId;
        final String loginUrl;
        final int expiresInSeconds;

        CursorLogin(String sessionId, String loginUrl, int expiresInSeconds) {
            this.sessionId = sessionId;
            this.loginUrl = loginUrl;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    /**
     * {@code POST /api/oauth/{provider}/import-token}.
     *
     * <p>Stores a token the user already holds. The gateway normalises it and
     * persists the connection, so the app never has to know the provider's
     * token shape.
     *
     * <p>Only the providers in the gateway's own whitelist accept this route.
     * Cursor and Trae are <em>not</em> in it — they have dedicated routes, see
     * {@link #importCursorToken} and {@link #importTraeToken}.
     */
    static PollResult importToken(Context context, String provider, String token)
            throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
        } catch (Exception ignored) {
        }
        Response response = postJson(context,
                baseUrl(context) + "/api/oauth/" + Uri.encode(provider) + "/import-token",
                payload.toString());
        return readImportResponse(response);
    }

    /**
     * {@code POST /api/oauth/cursor/import}.
     *
     * <p>Cursor reports {@code flowType:"import_token"} but the generic import
     * route rejects it with "import-token not supported for provider: cursor".
     * Its own route takes the access token plus the machine id, and it
     * validates the token against {@code https://cursor.com/api/auth/me}
     * before storing the connection.
     */
    static PollResult importCursorToken(Context context, String accessToken)
            throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("accessToken", accessToken);
        } catch (Exception ignored) {
        }
        Response response = postJson(context,
                baseUrl(context) + "/api/oauth/cursor/import", payload.toString());
        return readImportResponse(response);
    }

    /**
     * {@code POST /api/oauth/trae/import}.
     *
     * <p>Like Cursor, Trae has a dedicated import route. It takes the
     * Cloud-IDE-JWT the user copies from the {@code Authorization} header of
     * a {@code solo.trae.ai} request; the remaining identity fields are
     * optional and the gateway fills in sane defaults.
     */
    static PollResult importTraeToken(Context context, String accessToken)
            throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("accessToken", accessToken);
        } catch (Exception ignored) {
        }
        Response response = postJson(context,
                baseUrl(context) + "/api/oauth/trae/import", payload.toString());
        return readImportResponse(response);
    }

    /**
     * Reads a response from any of the import routes.
     *
     * <p>All three routes answer with the same shape on success
     * ({@code {"success":true,"connection":{...}}}), so one reader serves
     * them all.
     */
    private static PollResult readImportResponse(Response response) throws IOException {
        JSONObject json;
        try {
            json = new JSONObject(response.body);
        } catch (Exception exception) {
            throw new IOException("invalid response: " + response.body, exception);
        }
        if (response.status >= 200 && response.status < 300 && json.optBoolean("success", false)) {
            JSONObject connection = json.optJSONObject("connection");
            String name = connection != null ? optString(connection, "displayName") : null;
            String email = connection != null ? optString(connection, "email") : null;
            return new PollResult(true, null, name, email);
        }
        String message = describeError(response.body);
        throw new IOException(message.isEmpty()
                ? "HTTP " + response.status : "HTTP " + response.status + ": " + message);
    }

    // --- low-level helpers ------------------------------------------------

    /**
     * The flow the gateway reports for a provider.
     *
     * <p>This is the authoritative description of how a provider logs in. The
     * provider registry lives in the runtime, and a rootfs imported from
     * somewhere else can know a different set of providers, a different
     * redirect URI or a different flow type. Anything the app hard-codes
     * about a provider is therefore only a hint that the runtime is free to
     * overrule — which is what keeps a stale app table from sending a
     * provider down a flow the gateway does not serve.
     */
    static final class FlowInfo {
        final String providerKey;
        /**
         * "device_code", "authorization_code_pkce", "authorization_code" or
         * "import_token".
         */
        final String flowType;
        final String authUrl;
        final String redirectUri;
        /** True unless the runtime explicitly refused the provider. */
        final boolean supported;
        /** The loopback port the provider pins its redirect URI to, 0 when it does not. */
        final int fixedPort;
        /** The path the provider pins its redirect URI to, e.g. "/auth/callback". */
        final String callbackPath;
        /** The PKCE verifier the runtime generated, when it generated one. */
        final String codeVerifier;
        /** The state the runtime generated, when it generated one. */
        final String state;
        /** Why the runtime refuses the provider, when {@link #supported} is false. */
        final String error;

        FlowInfo(String providerKey, String flowType, String authUrl,
                 String redirectUri, boolean supported, int fixedPort,
                 String callbackPath, String codeVerifier, String state,
                 String error) {
            this.providerKey = providerKey;
            this.flowType = flowType;
            this.authUrl = authUrl;
            this.redirectUri = redirectUri;
            this.supported = supported;
            this.fixedPort = fixedPort;
            this.callbackPath = callbackPath;
            this.codeVerifier = codeVerifier;
            this.state = state;
            this.error = error;
        }

        boolean isDeviceCode() {
            return "device_code".equals(flowType);
        }

        boolean isImportToken() {
            return "import_token".equals(flowType);
        }

        /**
         * True when the exchange step needs the PKCE verifier the runtime
         * generated.
         *
         * <p>The gateway rejects an exchange without a verifier for a PKCE
         * provider, and a plain authorization-code provider has no verifier
         * to send.
         */
        boolean needsCodeVerifier() {
            return "authorization_code_pkce".equals(flowType);
        }

        /**
         * True when the app has to run the loopback listener itself.
         *
         * <p>Two different providers need that, in two different ways.
         *
         * <p>Zed's native-app sign-in is the plain case: the runtime hands
         * back a {@code redirectUri} on the loopback interface and expects
         * the app to capture the redirect there. The runtime cannot run the
         * listener for it — it refuses {@code start-callback-server} for this
         * provider — so the port is the app's to bind.
         *
         * <p>The other case is a provider that pins a fixed port <em>and</em>
         * lets the runtime own the listener (Codex on 1455, xAI on 56121,
         * Openference on 56123). There the app only polls
         * {@code /poll-callback}.
         *
         * <p>The two are told apart by the redirect URI: the runtime's own
         * listener is a gateway-internal thing and its redirect never leaves
         * the loopback interface, whereas Zed's redirect is handed to the app
         * as the value to listen on.
         */
        boolean needsAppLoopbackListener() {
            return isZedNativeApp();
        }

        /**
         * True for Zed's native-app sign-in.
         *
         * <p>Detected from the runtime's own answer rather than from the
         * provider name: the runtime builds the authorization URL itself and
         * that URL carries the {@code native_app_port} and
         * {@code native_app_public_key} parameters Zed expects, and the
         * redirect it returns points at the loopback interface. A provider
         * that is added to the runtime later with the same shape is then
         * handled correctly without an app change.
         */
        boolean isZedNativeApp() {
            if (authUrl != null && authUrl.contains("native_app_signin")) {
                return true;
            }
            if (codeVerifier != null && codeVerifier.startsWith("zed-rsa-pkcs1:")) {
                return true;
            }
            return false;
        }

        /**
         * The loopback port the app has to listen on, or 0 when it does not
         * have to listen at all.
         *
         * <p>Read from the redirect URI the runtime returned, because that is
         * the exact URL the browser will come back to. Falls back to the
         * {@code native_app_port} parameter of the authorization URL.
         */
        int appListenerPort() {
            String redirect = redirectUri;
            if (redirect != null) {
                int index = redirect.indexOf("://");
                if (index >= 0) {
                    String rest = redirect.substring(index + 3);
                    int slash = rest.indexOf('/');
                    if (slash >= 0) {
                        rest = rest.substring(0, slash);
                    }
                    int colon = rest.lastIndexOf(':');
                    if (colon > 0) {
                        try {
                            return Integer.parseInt(rest.substring(colon + 1));
                        } catch (NumberFormatException ignored) {
                            // Fall through to the parameter below.
                        }
                    }
                }
            }
            if (authUrl != null) {
                int index = authUrl.indexOf("native_app_port=");
                if (index >= 0) {
                    String tail = authUrl.substring(index + "native_app_port=".length());
                    int amp = tail.indexOf('&');
                    if (amp >= 0) {
                        tail = tail.substring(0, amp);
                    }
                    try {
                        return Integer.parseInt(tail);
                    } catch (NumberFormatException ignored) {
                        // No usable port; the caller uses the default.
                    }
                }
            }
            return 0;
        }

        /**
         * True when the app can intercept the provider's redirect.
         *
         * <p>Most providers honour the {@code redirect_uri} the app asks for,
         * so the browser comes back to {@code omniroute-oauth://callback}.
         * Some do not: Claude pins its redirect to
         * {@code https://platform.claude.com/oauth/code/callback} regardless,
         * so the authorization code lands on Anthropic's own site and the
         * app never sees it. The runtime still reports such a provider as
         * {@code authorization_code_pkce}, so the flow type alone cannot
         * distinguish "the app can catch it" from "the app would wait
         * forever".
         *
         * <p>The redirect the provider will actually use is embedded in the
         * authorization URL, so that is what the app checks. A provider
         * whose redirect is a loopback port the gateway pins (Codex, xAI,
         * Openference) is handled by the callback server instead and never
         * reaches this test.
         */
        boolean isRedirectCapturable() {
            if (authUrl == null || authUrl.isEmpty()) {
                return false;
            }
            String effective = effectiveRedirectUri();
            if (effective == null || effective.isEmpty()) {
                return false;
            }
            return effective.equals(OAuthConfig.DEFAULT_REDIRECT_URI);
        }

        /**
         * The redirect URI the provider will really use.
         *
         * <p>Read from the {@code redirect_uri} parameter of the
         * authorization URL, because that is the value the provider honours
         * — the runtime's own {@code redirectUri} field only says what the
         * app asked for.
         */
        String effectiveRedirectUri() {
            if (authUrl == null) {
                return null;
            }
            int index = authUrl.indexOf("redirect_uri=");
            if (index < 0) {
                return null;
            }
            String tail = authUrl.substring(index + "redirect_uri=".length());
            int amp = tail.indexOf('&');
            if (amp >= 0) {
                tail = tail.substring(0, amp);
            }
            try {
                return java.net.URLDecoder.decode(tail, "UTF-8");
            } catch (Exception exception) {
                return tail;
            }
        }
    }

    /**
     * Asks the gateway which flow a provider supports.
     *
     * <p>This is the single source of truth for the one-click list. The app
     * does not keep its own table of provider capabilities: the runtime owns
     * the provider registry, it can be replaced wholesale by an imported
     * rootfs, and a hardcoded table silently goes stale.
     *
     * <p>Returns {@code null} only when the gateway cannot be reached or the
     * provider is unknown to it, in which case the caller falls back to the
     * preset's own hint.
     */
    static FlowInfo probeFlow(Context context, String provider, String redirectUri) {
        String url = baseUrl(context) + "/api/oauth/" + Uri.encode(provider)
                + "/authorize?redirect_uri=" + Uri.encode(redirectUri);
        try {
            return parseFlowInfo(provider, getJson(context, url));
        } catch (HttpStatusException http) {
            // The gateway answered, but with a refusal that carries its own
            // explanation — Devin answers 410 "Browser OAuth disabled for
            // devin-cli — use import-token via …". That text is the most
            // actionable thing the user can be shown, so it must not be
            // swallowed by a generic connection error.
            return parseFlowInfo(provider, http.body);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Turns an {@code /authorize} answer into a {@link FlowInfo}.
     *
     * <p>Accepts the raw body because the gateway may answer 2xx (a JSON
     * object describing the flow) or 4xx/5xx (a JSON object describing why
     * the provider cannot be used). Both carry the same shape.
     */
    private static FlowInfo parseFlowInfo(String provider, String body) {        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception exception) {
            return null;
        }
        String flowType = optString(json, "flowType");
        if (flowType == null) {
            // The gateway refused without naming a flow, e.g. Devin's
            // "Browser OAuth disabled — use import-token". It has no
            // browser flow at all, so the token route is the only way in.
            String error = optString(json, "error");
            if (error == null) {
                JSONObject errorObject = json.optJSONObject("error");
                if (errorObject != null) {
                    error = optString(errorObject, "message");
                }
            }
            if (error == null) {
                return null;
            }
            return new FlowInfo(provider, "import_token", null, null,
                    false, 0, null, null, null, error);
        }
        // "supported":false comes with a human-readable reason (for
        // example "Qoder browser OAuth is experimental and disabled by
        // default"), which is more useful than the flow type alone.
        boolean supported = json.optBoolean("supported", true);
        String error = optString(json, "error");
        if (!supported && error == null) {
            error = "provider is not available in this runtime";
        }
        return new FlowInfo(provider, flowType,
                optString(json, "authUrl"),
                optString(json, "redirectUri"),
                supported,
                json.optInt("fixedPort", 0),
                optString(json, "callbackPath"),
                optString(json, "codeVerifier"),
                optString(json, "state"),
                error);
    }

    /**
     * The authorization URL the gateway built for a provider.
     *
     * <p>Asking the server for the URL removes the need to hand-write a client
     * id: the gateway already knows the client id, the PKCE challenge and the
     * redirect URI for every provider it supports. The returned
     * {@code state} and {@code codeVerifier} must be used verbatim.
     */
    static final class AuthorizeInfo {
        final String authUrl;
        final String state;
        final String codeVerifier;
        final String redirectUri;
        /**
         * The flow the runtime resolved for this provider.
         *
         * <p>Needed because the exchange step only wants a PKCE verifier for
         * the providers that use one: the runtime rejects an exchange that
         * omits the verifier for a PKCE provider, and a plain
         * authorization-code provider has no verifier to send.
         */
        final String flowType;

        AuthorizeInfo(String authUrl, String state, String codeVerifier, String redirectUri) {
            this(authUrl, state, codeVerifier, redirectUri, null);
        }

        AuthorizeInfo(String authUrl, String state, String codeVerifier, String redirectUri,
                      String flowType) {
            this.authUrl = authUrl;
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.redirectUri = redirectUri;
            this.flowType = flowType;
        }
    }

    /**
     * {@code GET /api/oauth/{provider}/authorize}.
     *
     * @return the built authorization URL, or {@code null} when the provider
     *     cannot complete a browser flow.
     */
    static AuthorizeInfo requestAuthorize(Context context, String provider, String redirectUri)
            throws IOException {
        String url = baseUrl(context) + "/api/oauth/" + Uri.encode(provider)
                + "/authorize?redirect_uri=" + Uri.encode(redirectUri);
        String body = getJson(context, url);
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("invalid JSON: " + body, e);
        }
        String authUrl = optString(json, "authUrl");
        if (authUrl == null) {
            throw new IOException(optString(json, "error") != null
                    ? optString(json, "error")
                    : "provider does not support a browser authorization flow");
        }
        return new AuthorizeInfo(
                authUrl,
                optString(json, "state"),
                optString(json, "codeVerifier"),
                optString(json, "redirectUri"),
                optString(json, "flowType"));
    }

    /**
     * {@code POST /api/oauth/{provider}/exchange}.
     *
     * <p>The gateway exchanges the authorization code and stores the resulting
     * connection itself, so the app never has to hold a raw access token.
     */
    /**
     * {@code POST /api/oauth/zed-hosted/exchange}, Zed's native-app variant.
     *
     * <p>Zed's sign-in does not hand out an authorization code. The browser is
     * redirected to {@code http://127.0.0.1:<port>/?user_id=…&access_token=…}
     * where the access token is encrypted with the public key the runtime
     * generated, and the runtime decrypts it with the matching private key.
     * The private key travels as the {@code codeVerifier}, so all three
     * values have to be posted together:
     *
     * <ul>
     *   <li>{@code code} — the callback URL exactly as the browser received
     *       it, including the query string;</li>
     *   <li>{@code redirectUri} — the loopback URL the runtime told the app
     *       to listen on;</li>
     *   <li>{@code codeVerifier} — the runtime's private key, prefixed with
     *       {@code zed-rsa-pkcs1:}.</li>
     * </ul>
     *
     * <p>The runtime's own error messages are good, but it does not validate
     * that the token it ends up with is usable, so the app checks the
     * callback URL itself before posting: a callback without {@code user_id}
     * or {@code access_token} is a failed sign-in, not a Zed session.
     */
    static boolean exchangeZedNativeApp(Context context, String callbackUrl,
                                        String redirectUri, String privateKeyVerifier)
            throws IOException {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            throw new IOException("missing Zed callback URL");
        }
        if (!callbackUrl.contains("user_id=") || !callbackUrl.contains("access_token=")) {
            throw new IOException("Zed callback is missing user_id or access_token");
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("code", callbackUrl);
            if (redirectUri != null && !redirectUri.isEmpty()) {
                payload.put("redirectUri", redirectUri);
            }
            if (privateKeyVerifier != null && !privateKeyVerifier.isEmpty()) {
                payload.put("codeVerifier", privateKeyVerifier);
            }
        } catch (Exception ignored) {
        }
        Response response = postJson(context,
                baseUrl(context) + "/api/oauth/zed-hosted/exchange",
                payload.toString());
        if (response.status >= 200 && response.status < 300) {
            return true;
        }
        throw new IOException("HTTP " + response.status + ": " + describeError(response.body));
    }

    static boolean exchange(Context context, String provider, String code,
                            String redirectUri, String codeVerifier) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("code", code);
            if (redirectUri != null && !redirectUri.isEmpty()) {
                payload.put("redirectUri", redirectUri);
            }
            if (codeVerifier != null && !codeVerifier.isEmpty()) {
                payload.put("codeVerifier", codeVerifier);
            }
        } catch (Exception ignored) {
        }
        Response response = postJson(context,
                baseUrl(context) + "/api/oauth/" + Uri.encode(provider) + "/exchange",
                payload.toString());
        if (response.status >= 200 && response.status < 300) {
            return true;
        }
        throw new IOException("HTTP " + response.status + ": " + describeError(response.body));
    }

    private static final class Response {
        final int status;
        final String body;
        /** Raw {@code Set-Cookie} header values returned by the gateway. */
        final java.util.List<String> setCookies;

        Response(int status, String body) {
            this(status, body, java.util.Collections.<String>emptyList());
        }

        Response(int status, String body, java.util.List<String> setCookies) {
            this.status = status;
            this.body = body;
            this.setCookies = setCookies;
        }

        /** Extracts one cookie value by name, or {@code null}. */
        String cookie(String name) {
            for (String header : setCookies) {
                for (String part : header.split(";")) {
                    String entry = part.trim();
                    if (entry.startsWith(name + "=")) {
                        String value = entry.substring(name.length() + 1).trim();
                        if (!value.isEmpty()) {
                            return value;
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * Performs a GET against the gateway, authenticating when needed.
     *
     * <p>The gateway is loopback-only, so the session cookie is sent over the
     * same connection the app already trusts.
     */
    private static String getJson(Context context, String urlStr) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            applyAuth(context, connection);
            int status = connection.getResponseCode();
            InputStream input = (status >= 200 && status < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(input);
            if (status < 200 || status >= 300) {
                throw new HttpStatusException(status, body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * An HTTP answer that was not 2xx, with its body kept intact.
     *
     * <p>The body matters: the gateway explains its refusals in JSON
     * ("Browser OAuth disabled for devin-cli — use import-token via …"),
     * and that text is what the user should read. A plain
     * {@code IOException("HTTP 410")} would throw it away.
     */
    static final class HttpStatusException extends IOException {
        final int status;
        final String body;

        HttpStatusException(int status, String body) {
            super("HTTP " + status
                    + (body == null || body.isEmpty() ? "" : ": " + body));
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * Adds the dashboard session cookie to a request.
     *
     * <p>Called from a background thread, so the WebView cookie store is not
     * consulted here; the cached or freshly-minted session is used instead.
     */
    private static void applyAuth(Context context, HttpURLConnection connection) {
        String session = cachedSession(context);
        if (session == null) {
            session = login(context);
            if (session != null) {
                cacheSession(context, session);
            }
        }
        if (session != null) {
            connection.setRequestProperty("Cookie", COOKIE_NAME + "=" + session);
        }
        connection.setRequestProperty("Origin", baseUrl(context));
    }

    /** Turns a gateway error body into a short human-readable message. */
    private static String describeError(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = optString(error, "message");
                if (message != null) {
                    return message;
                }
            }
            String plain = optString(json, "error");
            if (plain != null) {
                return plain;
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static String getJson(String urlStr) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            int status = connection.getResponseCode();
            InputStream input = (status >= 200 && status < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(input);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status
                        + (body.isEmpty() ? "" : ": " + body));
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static Response postJson(String urlStr, String jsonBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream input = (status >= 200 && status < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            return new Response(status, readAll(input), connection.getHeaderFields()
                    .getOrDefault("Set-Cookie", java.util.Collections.<String>emptyList()));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * POSTs a JSON body to the gateway with the dashboard session attached.
     */
    private static Response postJson(Context context, String urlStr, String jsonBody)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            applyAuth(context, connection);
            String csrf = csrfToken(context);
            if (csrf != null) {
                connection.setRequestProperty("x-csrf-token", csrf);
            }
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream input = (status >= 200 && status < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            return new Response(status, readAll(input));
        } finally {
            connection.disconnect();
        }
    }

    private static String optString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (input == null) {
            return "";
        }
        byte[] chunk = new byte[1 << 12];
        int count;
        while ((count = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, count);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
