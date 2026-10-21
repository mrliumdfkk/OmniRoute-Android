package com.example.omniroute;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The provider catalogue the OAuth screen offers, plus the persisted state of
 * the form and of the flow in flight.
 *
 * <h2>Where the table below comes from</h2>
 * <p>Every built-in provider is driven by the rootfs gateway, not by the app.
 * The gateway knows each provider's client id, scopes and redirect URI, and
 * it persists the resulting connection itself. The app only has to pick the
 * right transport — and the transport is <em>not</em> a property of the
 * provider alone: it is a property of what the gateway's provider registry
 * offers.
 *
 * <p>The registry was read out of the runtime that ships with this app
 * ({@code .build/next/server/chunks/97071.js}, module {@code 860969}) and
 * cross-checked against the live gateway, which reported the same answer for
 * every one of the 28 registered providers. The audit produced this table:
 *
 * <table>
 *   <caption>Gateway flow kinds, verified against the live gateway</caption>
 *   <tr><th>Kind</th><th>Transport</th><th>Providers</th></tr>
 *   <tr><td>{@link FlowKind#DEVICE_CODE}</td>
 *       <td>{@code GET /api/oauth/{p}/device-code} then
 *           {@code POST /api/oauth/{p}/poll}</td>
 *       <td>github, kimi-coding, ghe-copilot, kiro, amazon-q, kilocode,
 *           codebuddy-cn, codebuddy-intl, workbuddy, muse-code</td></tr>
 *   <tr><td>{@link FlowKind#CALLBACK_SERVER}</td>
 *       <td>{@code GET /api/oauth/{p}/start-callback-server} then
 *           {@code POST /api/oauth/{p}/poll-callback}</td>
 *       <td>codex (port 1455), xai-oauth (56121), openference (56123),
 *           grok-cli (56122)</td></tr>
 *   <tr><td>{@link FlowKind#BROWSER}</td>
 *       <td>{@code GET /api/oauth/{p}/authorize} then
 *           {@code POST /api/oauth/{p}/exchange}</td>
 *       <td>antigravity, agy, qoder, cline, clinepass, zed-hosted</td></tr>
 *   <tr><td>{@link FlowKind#IMPORT_TOKEN}</td>
 *       <td>{@code POST /api/oauth/{p}/import-token}</td>
 *       <td>claude, grok-cli, devin-cli, devin-desktop</td></tr>
 * </table>
 *
 * <p>Four registry entries do not fit any of the four kinds and are therefore
 * not offered as a one-tap login:
 * <ul>
 *   <li>{@code zed} imports LLM credentials from the OS keychain — the
 *       gateway answers "zed has no browser OAuth flow" for every OAuth
 *       action.</li>
 *   <li>{@code cursor} and {@code trae} report {@code flowType:"import_token"}
 *       but the generic import endpoint rejects them ("import-token not
 *       supported for provider: cursor"); they have their own dedicated
 *       endpoints ({@code /api/oauth/cursor/import} and
 *       {@code /api/oauth/trae/import}) with extra required fields, so the app
 *       uses {@link FlowKind#IMPORT_TOKEN} with the dedicated route.</li>
 *   <li>{@code qoder} and {@code gitlab-duo} need server-side configuration
 *       the user has to provide first; the gateway answers
 *       {@code supported:false} for both.</li>
 * </ul>
 *
 * <h2>Why the buttons bind to a key, not to an index</h2>
 * <p>The layout used to declare a fixed row of buttons and bind them to
 * hard-coded preset indexes while this catalogue was edited independently. Any
 * change to the list silently paired a button with the wrong provider — a
 * "Google" button started a WorkBuddy device-code flow. Every button is now
 * generated from {@link #PRESETS} and carries the provider key with it, so the
 * mapping cannot drift.
 */
final class OAuthConfig {
    private OAuthConfig() {}

    /** Custom URI scheme the app answers as an OAuth redirect target. */
    static final String CALLBACK_SCHEME = "omniroute-oauth";
    /** Default redirect URI handed to the authorize endpoint. */
    static final String DEFAULT_REDIRECT_URI = CALLBACK_SCHEME + "://callback";

    /**
     * How a provider's tokens are obtained.
     *
     * <p>The kind is chosen per provider from what the gateway actually
     * supports, not from what the provider's own documentation says. Getting
     * this wrong is not cosmetic: asking a device-code provider for an
     * authorization URL yields "Provider does not support device code flow",
     * and asking a callback-server provider to redirect into the app yields a
     * redirect URI the provider will not accept.
     */
    enum FlowKind {
        /**
         * RFC 8628 device authorization. The gateway mints the code, the app
         * shows the user code and verification URL, then polls until the user
         * approves. No redirect is involved and the gateway stores the
         * connection itself.
         */
        DEVICE_CODE,
        /**
         * Authorization code with PKCE, where the gateway runs a loopback
         * callback server. The app opens the authorization URL and then polls
         * {@code /poll-callback} until the gateway reports the connection.
         *
         * <p>Used for providers whose redirect URI is pinned to a fixed port
         * (Codex on 1455, Grok CLI on 56122, xAI on 56121, Openference on
         * 56123) — the app cannot intercept that, but it does not have to: the
         * gateway's own listener on the device's loopback receives the
         * redirect.
         */
        CALLBACK_SERVER,
        /**
         * Authorization code with PKCE, where the app captures the redirect
         * itself and hands the code to {@code /exchange}. Used for providers
         * whose redirect URI the gateway lets the caller choose.
         *
         * <p>Claude is <em>not</em> in this group: its redirect URI is pinned
         * to {@code https://platform.claude.com/oauth/code/callback}, which no
         * local app can intercept.
         */
        BROWSER,
        /**
         * No browser flow at all: the user pastes a token they already hold
         * and the gateway stores it. Claude is the important case — its
         * redirect URI is fixed to Anthropic's own site, so the authorization
         * code is never observable locally.
         */
        IMPORT_TOKEN,
        /**
         * Zed's native-app sign-in.
         *
         * <p>Zed does not speak plain OAuth. The browser is sent to
         * {@code https://zed.dev/native_app_signin?native_app_port=…&native_app_public_key=…}
         * and comes back to {@code http://127.0.0.1:<port>/?user_id=…&access_token=…}
         * with the access token encrypted to the public key the runtime
         * generated.
         *
         * <p>Neither the runtime nor the dashboard can stand in for the app
         * here: the runtime refuses {@code start-callback-server} for this
         * provider and has no device route for it, and the dashboard's
         * fallback is to have the user copy the callback URL out of the
         * browser's address bar by hand. The app can own the loopback port
         * itself, capture the redirect and hand it to the runtime, so the
         * sign-in stays a single tap.
         */
        ZED_NATIVE_APP,
        /**
         * The runtime refuses to run an OAuth flow for this provider, and
         * there is no import route for it either.
         *
         * <p>Qoder and GitLab Duo land here. The gateway answers
         * {@code supported:false} for both — Qoder because its browser OAuth
         * is experimental and switched off by default, GitLab Duo because no
         * OAuth application has been registered on the server — and neither
         * is in the generic import whitelist ("devin-desktop, devin-cli,
         * grok-cli, claude").
         *
         * <p>So there is nothing the app can paste and nothing it can open.
         * Offering an import box for them would only produce a confusing
         * "import-token not supported for provider: qoder" after the user
         * pasted a valid token. The dashboard treats them the same way: as
         * providers that take a manually entered API key or PAT.
         */
        MANUAL_KEY
    }

    /**
     * Which import endpoint a provider uses.
     *
     * <p>The generic {@code POST /api/oauth/{p}/import-token} route only
     * accepts the providers in the gateway's own whitelist — "devin-desktop,
     * devin-cli, grok-cli, claude". Cursor and Trae report
     * {@code flowType:"import_token"} but are not in that whitelist, so they
     * need their dedicated routes — and those routes take extra fields the
     * generic one does not know about.
     */
    enum ImportStyle {
        /** {@code POST /api/oauth/{p}/import-token} with {@code {"token": ...}}. */
        GENERIC,
        /** {@code POST /api/oauth/cursor/import} with an access token. */
        CURSOR,
        /** {@code POST /api/oauth/trae/import} with a Cloud-IDE-JWT. */
        TRAE
    }

    /**
     * A provider template.
     *
     * <p>For gateway-driven kinds the URL/scopes fields are not used by the
     * app: the gateway owns those values. They are still recorded so the
     * "custom" form can be pre-filled when a user wants to inspect or
     * override them.
     */
    static final class Preset {
        final String name;
        final String providerKey;   // rootfs provider id, e.g. "github", "claude"
        final FlowKind flowKind;
        final String authorizeUrl;
        final String tokenUrl;
        final String scopes;
        /** Base URL stored as the provider credential's URL when saving. */
        final String apiBaseUrl;
        final boolean requiresPkce;
        /**
         * True when a single tap can complete the flow without the user
         * having to hand-write anything.
         */
        final boolean oneTap;
        /**
         * The loopback port the provider pins its redirect URI to, or 0 when
         * the provider lets the caller choose.
         *
         * <p>A pinned port means the provider will only redirect to
         * {@code http://localhost:<port>/<path>}. The app cannot intercept
         * that — but it does not have to: the gateway runs the listener itself
         * and the app polls {@code /poll-callback} until it reports the
         * connection.
         */
        final int fixedPort;
        /**
         * True when the provider can only be logged into by pasting a token
         * the user already holds.
         */
        final boolean importOnly;
        /** Which import route the pasted token is submitted to. */
        final ImportStyle importStyle;
        /**
         * A short note shown under the button, explaining what the tap will
         * actually do. Empty for entries that need no explanation.
         */
        final String note;

        Preset(String name, String providerKey, FlowKind flowKind,
               String authorizeUrl, String tokenUrl,
               String scopes, String apiBaseUrl, boolean requiresPkce,
               boolean oneTap) {
            this(name, providerKey, flowKind, authorizeUrl, tokenUrl, scopes,
                    apiBaseUrl, requiresPkce, oneTap, 0, false, ImportStyle.GENERIC, "");
        }

        Preset(String name, String providerKey, FlowKind flowKind,
               String authorizeUrl, String tokenUrl,
               String scopes, String apiBaseUrl, boolean requiresPkce,
               boolean oneTap, int fixedPort, boolean importOnly,
               ImportStyle importStyle) {
            this(name, providerKey, flowKind, authorizeUrl, tokenUrl, scopes,
                    apiBaseUrl, requiresPkce, oneTap, fixedPort, importOnly,
                    importStyle, "");
        }

        Preset(String name, String providerKey, FlowKind flowKind,
               String authorizeUrl, String tokenUrl,
               String scopes, String apiBaseUrl, boolean requiresPkce,
               boolean oneTap, int fixedPort, boolean importOnly,
               ImportStyle importStyle, String note) {
            this.name = name;
            this.providerKey = providerKey;
            this.flowKind = flowKind;
            this.authorizeUrl = authorizeUrl;
            this.tokenUrl = tokenUrl;
            this.scopes = scopes;
            this.apiBaseUrl = apiBaseUrl;
            this.requiresPkce = requiresPkce;
            this.oneTap = oneTap;
            this.fixedPort = fixedPort;
            this.importOnly = importOnly;
            this.importStyle = importStyle;
            this.note = note;
        }

        /** A gateway-driven preset, i.e. one with a non-empty provider key. */
        boolean isGatewayDriven() {
            return providerKey != null && !providerKey.isEmpty();
        }

        /**
         * True when the gateway has to run the loopback listener, because the
         * provider pins the redirect URI to a fixed port.
         */
        boolean needsCallbackServer() {
            return flowKind == FlowKind.CALLBACK_SERVER;
        }
    }

    /**
     * The one-click providers, in the order the dashboard lists them.
     *
     * <p>Every entry was verified against the runtime's own provider
     * registry and against the live gateway, which reported
     * {@code "flowType":"device_code"} for exactly these providers. A
     * provider whose flow type is not {@code device_code} does not belong in
     * this table — the app would ask the gateway for a device code and get
     * "Provider does not support device code flow" back.
     *
     * <p>Device-code providers need no client id, no client secret and no
     * hand-written redirect URI: the gateway registers the client and mints
     * the code. That is what makes them genuinely one tap.
     *
     * <p>Grok CLI is here even though the gateway also knows how to serve it
     * through the callback server: the runtime reports
     * {@code flowType:"device_code"} for it, and the app follows the runtime,
     * not the dashboard's internal shortcut.
     *
     * <p>Claude is deliberately absent: its redirect URI is pinned to
     * {@code https://platform.claude.com/oauth/code/callback}, so the app can
     * never observe the authorization code and the import panel is the only
     * way in.
     */
    static final Preset[] DEVICE_CODE_PRESETS = {
            new Preset(
                    "WorkBuddy",
                    "workbuddy",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://www.workbuddy.ai",
                    false,
                    true),
            new Preset(
                    "GitHub Copilot",
                    "github",
                    FlowKind.DEVICE_CODE,
                    "https://github.com/login/device",
                    "https://github.com/login/oauth/access_token",
                    "read:user",
                    "https://api.github.com",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "需能访问 github.com；若设备码接口超时，请检查网络后重试"),
            new Preset(
                    "Kimi Code CLI",
                    "kimi-coding",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://auth.kimi.com",
                    false,
                    true),
            new Preset(
                    "Kiro AI",
                    "kiro",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://view.awsapps.com",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "需先在服务端配置 AWS 区域与账号"),
            new Preset(
                    "Amazon Q",
                    "amazon-q",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://view.awsapps.com",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "需先在服务端配置 AWS 区域与账号"),
            new Preset(
                    "CodeBuddy CN",
                    "codebuddy-cn",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://copilot.tencent.com",
                    false,
                    true),
            new Preset(
                    "CodeBuddy Intl",
                    "codebuddy-intl",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://www.codebuddy.ai",
                    false,
                    true),
            new Preset(
                    "Kilo Code",
                    "kilocode",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://kilocode.ai",
                    false,
                    true),
            new Preset(
                    "Muse Code (Meta)",
                    "muse-code",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://api.meta.ai",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "上游设备授权接口可能不可用，失败时可改用「Grok Build（粘贴令牌）」同类方式导入"),
            new Preset(
                    "Grok Build",
                    "grok-cli",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://grok.com",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "服务端设备码接口不可用时会自动改用回环回调授权（端口 56122 由运行时监听）"),
            new Preset(
                    "GitHub Enterprise Copilot",
                    "ghe-copilot",
                    FlowKind.DEVICE_CODE,
                    "",
                    "",
                    "",
                    "https://github.com",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "需先在服务端填写 gheUrl"),
    };

    /**
     * Providers whose redirect URI is pinned to a fixed loopback port, so the
     * gateway has to run the callback listener itself.
     *
     * <p>The app opens the authorization URL in the system browser and then
     * polls {@code /poll-callback}. The browser on the device reaches the
     * gateway's listener directly, because both live on the same loopback
     * interface.
     *
     * <p>Only Codex, xAI and Openference belong here. Grok CLI used to be
     * listed as well, but the runtime serves it as a <em>device-code</em>
     * provider — it hands out a one-time code instead of a pinned-port
     * redirect — so the callback-server path never worked for it.
     */
    static final Preset[] CALLBACK_SERVER_PRESETS = {
            new Preset(
                    "OpenAI Codex",
                    "codex",
                    FlowKind.CALLBACK_SERVER,
                    "https://auth.openai.com/oauth/authorize",
                    "https://auth.openai.com/oauth/token",
                    "openid profile email offline_access",
                    "https://api.openai.com",
                    true,
                    true,
                    1455,
                    false,
                    ImportStyle.GENERIC,
                    "回调端口 1455 由运行时监听，请勿关闭弹出的浏览器"),
            new Preset(
                    "xAI OAuth (Grok)",
                    "xai-oauth",
                    FlowKind.CALLBACK_SERVER,
                    "https://auth.x.ai/oauth2/authorize",
                    "https://auth.x.ai/oauth2/token",
                    "openid profile email offline_access grok-cli:access api:access",
                    "https://api.x.ai",
                    true,
                    true,
                    56121,
                    false,
                    ImportStyle.GENERIC,
                    "回调端口 56121 由运行时监听，请勿关闭弹出的浏览器"),
            new Preset(
                    "Openference",
                    "openference",
                    FlowKind.CALLBACK_SERVER,
                    "https://openference.com/app/oauth/authorize",
                    "https://openference.com/oauth/token",
                    "openid profile email model:invoke offline_access",
                    "https://openference.com",
                    true,
                    true,
                    56123,
                    false,
                    ImportStyle.GENERIC,
                    "回调端口 56123 由运行时监听，请勿关闭弹出的浏览器"),
    };

    /**
     * Providers that redirect back into the app.
     *
     * <p>The gateway builds the authorization URL (client id, PKCE challenge
     * and redirect URI included) and performs the code exchange, so the app
     * never holds a raw access token. The user still has to approve the
     * consent page in a browser.
     *
     * <p>Only providers whose redirect URI the gateway lets the caller choose
     * belong here. Claude does <em>not</em>: its redirect URI is hard-coded to
     * {@code https://platform.claude.com/oauth/code/callback}, so the app could
     * never capture the redirect — it belongs in the import-token list.
     */
    static final Preset[] BROWSER_PRESETS = {
            new Preset(
                    "Antigravity",
                    "antigravity",
                    FlowKind.BROWSER,
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    "openid email profile",
                    "https://www.googleapis.com",
                    true,
                    true),
            new Preset(
                    "Antigravity CLI",
                    "agy",
                    FlowKind.BROWSER,
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    "openid email profile",
                    "https://www.googleapis.com",
                    true,
                    true),
            new Preset(
                    "Cline",
                    "cline",
                    FlowKind.BROWSER,
                    "https://api.cline.bot/api/v1/auth/authorize",
                    "",
                    "",
                    "https://api.cline.bot",
                    true,
                    true),
            new Preset(
                    "ClinePass",
                    "clinepass",
                    FlowKind.BROWSER,
                    "https://api.cline.bot/api/v1/auth/authorize",
                    "",
                    "",
                    "https://api.clline.bot",
                    true,
                    true),
            new Preset(
                    "Qoder",
                    "qoder",
                    FlowKind.MANUAL_KEY,
                    "",
                    "",
                    "",
                    "https://qoder.com",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "服务端未开放浏览器 OAuth（需 QODER_OAUTH_*），且不支持导入令牌；请在网页端手动填入访问令牌"),
            new Preset(
                    "GitLab Duo",
                    "gitlab-duo",
                    FlowKind.MANUAL_KEY,
                    "",
                    "",
                    "",
                    "https://gitlab.com",
                    true,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "需先在 gitlab.com 注册 OAuth 应用并填入客户端凭据；服务端未配置前无法登录，且不支持导入令牌"),
            new Preset(
                    "Zed Hosted Models",
                    "zed-hosted",
                    FlowKind.ZED_NATIVE_APP,
                    "",
                    "",
                    "",
                    "https://zed.dev",
                    false,
                    true,
                    0,
                    false,
                    ImportStyle.GENERIC,
                    "App 会监听本机回环端口接收 Zed 原生签名回调，请勿关闭弹出的浏览器"),
    };

    /**
     * Providers that cannot be logged into from the app at all: the user has
     * to paste a token they already hold.
     *
     * <p>Claude is the important one. Its redirect URI is fixed to
     * {@code https://platform.claude.com/oauth/code/callback}, so the consent
     * page redirects to Anthropic's own site and the authorization code is
     * never observable locally. The gateway exposes
     * {@code POST /api/oauth/claude/import-token} for exactly this case.
     *
     * <p>Cursor and Trae are in this list for a different reason: they
     * report {@code flowType:"import_token"} but the generic import route
     * rejects them, so the app posts to their dedicated routes instead.
     */
    static final Preset[] IMPORT_TOKEN_PRESETS = {
            new Preset(
                    "Claude Code（粘贴令牌）",
                    "claude",
                    FlowKind.IMPORT_TOKEN,
                    "",
                    "",
                    "",
                    "https://api.anthropic.com",
                    false,
                    false,
                    0,
                    true,
                    ImportStyle.GENERIC,
                    "回调固定在 Anthropic 站点，App 无法截获授权码"),
            new Preset(
                    "Cursor IDE（粘贴令牌）",
                    "cursor",
                    FlowKind.IMPORT_TOKEN,
                    "",
                    "",
                    "",
                    "https://cursor.com",
                    false,
                    false,
                    0,
                    true,
                    ImportStyle.CURSOR,
                    "运行时已关闭 Cursor 浏览器登录，仅支持导入令牌"),
            new Preset(
                    "Trae（粘贴令牌）",
                    "trae",
                    FlowKind.IMPORT_TOKEN,
                    "",
                    "",
                    "",
                    "https://trae.ai",
                    false,
                    false,
                    0,
                    true,
                    ImportStyle.TRAE,
                    "运行时已关闭 Trae 浏览器登录，仅支持导入令牌"),
            new Preset(
                    "Devin CLI（粘贴令牌）",
                    "devin-cli",
                    FlowKind.IMPORT_TOKEN,
                    "",
                    "",
                    "",
                    "https://devin.ai",
                    false,
                    false,
                    0,
                    true,
                    ImportStyle.GENERIC,
                    "运行时已禁用 Devin 浏览器登录，仅支持导入 API 密钥"),
            new Preset(
                    "Devin Desktop（粘贴令牌）",
                    "devin-desktop",
                    FlowKind.IMPORT_TOKEN,
                    "",
                    "",
                    "",
                    "https://devin.ai",
                    false,
                    false,
                    0,
                    true,
                    ImportStyle.GENERIC,
                    "运行时已禁用 Devin Desktop 浏览器登录，仅支持导入 API 密钥"),
            new Preset(
                    "Grok Build（粘贴令牌）",
                    "grok-cli",
                    FlowKind.IMPORT_TOKEN,
                    "",
                    "",
                    "",
                    "https://grok.com",
                    false,
                    false,
                    0,
                    true,
                    ImportStyle.GENERIC,
                    "设备码授权失败时，可用已有的 Grok 令牌直接导入"),
    };

    /**
     * The advanced escape hatch: a hand-written OAuth client.
     *
     * <p>Every field starts empty on purpose. It exists for providers the
     * gateway does not know about, and those providers' operators are the only
     * ones who can fill in a client id and secret.
     */
    static final Preset CUSTOM_PRESET =
            new Preset("自定义", "", FlowKind.BROWSER, "", "", "", "", false, false);

    /**
     * All presets the form can show, one-click providers first.
     */
    static final Preset[] PRESETS = buildPresets();

    private static Preset[] buildPresets() {
        int count = DEVICE_CODE_PRESETS.length + CALLBACK_SERVER_PRESETS.length
                + BROWSER_PRESETS.length + IMPORT_TOKEN_PRESETS.length + 1;
        Preset[] all = new Preset[count];
        int at = 0;
        System.arraycopy(DEVICE_CODE_PRESETS, 0, all, at, DEVICE_CODE_PRESETS.length);
        at += DEVICE_CODE_PRESETS.length;
        System.arraycopy(CALLBACK_SERVER_PRESETS, 0, all, at, CALLBACK_SERVER_PRESETS.length);
        at += CALLBACK_SERVER_PRESETS.length;
        System.arraycopy(BROWSER_PRESETS, 0, all, at, BROWSER_PRESETS.length);
        at += BROWSER_PRESETS.length;
        System.arraycopy(IMPORT_TOKEN_PRESETS, 0, all, at, IMPORT_TOKEN_PRESETS.length);
        at += IMPORT_TOKEN_PRESETS.length;
        all[all.length - 1] = CUSTOM_PRESET;
        return all;
    }

    /** Index of the custom preset within {@link #PRESETS}. */
    static final int CUSTOM_PRESET_INDEX = PRESETS.length - 1;

    /**
     * Index of the first preset for a provider key, or -1 when it is unknown.
     *
     * <p>A provider can appear more than once — Grok CLI, for instance, is
     * both a device-code provider and an import-token one. This returns the
     * first (and therefore the one-click) entry.
     */
    static int indexOfProvider(String providerKey) {
        if (providerKey == null || providerKey.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < PRESETS.length; i++) {
            if (providerKey.equals(PRESETS[i].providerKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The import route a provider's pasted token has to go to.
     *
     * <p>Looked up per provider rather than per index, because a provider
     * can be listed more than once and only the import-token entry knows
     * which route the gateway expects. The generic route only accepts the
     * gateway's own whitelist; Cursor and Trae have dedicated routes.
     */
    static ImportStyle importStyleFor(String providerKey) {
        if (providerKey == null || providerKey.isEmpty()) {
            return ImportStyle.GENERIC;
        }
        for (Preset preset : IMPORT_TOKEN_PRESETS) {
            if (providerKey.equals(preset.providerKey)) {
                return preset.importStyle;
            }
        }
        return ImportStyle.GENERIC;
    }

    private static final String FILE = "omniroute-oauth";
    private static final String KEY_PRESET = "preset_index";
    private static final String KEY_AUTHORIZE = "authorize_url";
    private static final String KEY_TOKEN = "token_url";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_CLIENT_SECRET = "client_secret";
    private static final String KEY_SCOPES = "scopes";
    private static final String KEY_REDIRECT = "redirect_uri";
    private static final String KEY_API_BASE = "api_base";
    private static final String KEY_EXTERNAL = "use_external";
    private static final String KEY_SAVE_PROVIDER = "save_provider";

    /**
     * Separate preference file for the tokens of hand-written OAuth clients.
     *
     * <p>Kept apart from {@link #FILE} so that clearing the pending flow
     * never drops a refresh token the user still needs.
     */
    private static final String TOKEN_FILE = "omniroute-oauth-tokens";

    // Pending-flow state.
    private static final String KEY_PENDING_VERIFIER = "pending_verifier";
    private static final String KEY_PENDING_STATE = "pending_state";
    private static final String KEY_PENDING_NAME = "pending_name";
    private static final String KEY_PENDING_PROVIDER = "pending_provider";
    private static final String KEY_PENDING_FLOW = "pending_flow";
    private static final String KEY_PENDING_REDIRECT = "pending_redirect";
    private static final String KEY_PENDING_FLOW_TYPE = "pending_flow_type";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int presetIndex(Context context) {
        int value = prefs(context).getInt(KEY_PRESET, 0);
        return value >= 0 && value < PRESETS.length ? value : 0;
    }

    static void saveForm(Context context, int presetIndex, String authorizeUrl,
                         String tokenUrl, String clientId, String clientSecret,
                         String scopes, String redirectUri, String apiBase,
                         boolean useExternal, boolean saveProvider) {
        prefs(context).edit()
                .putInt(KEY_PRESET, presetIndex)
                .putString(KEY_AUTHORIZE, authorizeUrl)
                .putString(KEY_TOKEN, tokenUrl)
                .putString(KEY_CLIENT_ID, clientId)
                .putString(KEY_CLIENT_SECRET, clientSecret)
                .putString(KEY_SCOPES, scopes)
                .putString(KEY_REDIRECT, redirectUri)
                .putString(KEY_API_BASE, apiBase)
                .putBoolean(KEY_EXTERNAL, useExternal)
                .putBoolean(KEY_SAVE_PROVIDER, saveProvider)
                .apply();
    }

    static String authorizeUrl(Context context) { return prefs(context).getString(KEY_AUTHORIZE, ""); }
    static String tokenUrl(Context context) { return prefs(context).getString(KEY_TOKEN, ""); }
    static String clientId(Context context) { return prefs(context).getString(KEY_CLIENT_ID, ""); }
    static String clientSecret(Context context) { return prefs(context).getString(KEY_CLIENT_SECRET, ""); }
    static String scopes(Context context) { return prefs(context).getString(KEY_SCOPES, ""); }
    static String redirectUri(Context context) {
        return prefs(context).getString(KEY_REDIRECT, DEFAULT_REDIRECT_URI);
    }
    static String apiBase(Context context) { return prefs(context).getString(KEY_API_BASE, ""); }
    static boolean useExternal(Context context) {
        return prefs(context).getBoolean(KEY_EXTERNAL, true);
    }
    static boolean saveProvider(Context context) {
        return prefs(context).getBoolean(KEY_SAVE_PROVIDER, true);
    }

    static void savePending(Context context, String verifier, String state, String name) {
        prefs(context).edit()
                .putString(KEY_PENDING_VERIFIER, verifier)
                .putString(KEY_PENDING_STATE, state)
                .putString(KEY_PENDING_NAME, name)
                .apply();
    }

    static String pendingVerifier(Context context) {
        return prefs(context).getString(KEY_PENDING_VERIFIER, null);
    }

    static String pendingState(Context context) {
        return prefs(context).getString(KEY_PENDING_STATE, null);
    }

    static String pendingName(Context context) {
        return prefs(context).getString(KEY_PENDING_NAME, null);
    }

    static String pendingProvider(Context context) {
        return prefs(context).getString(KEY_PENDING_PROVIDER, null);
    }

    static OAuthConfig.FlowKind pendingFlow(Context context) {
        String value = prefs(context).getString(KEY_PENDING_FLOW, null);
        if (value == null) {
            return null;
        }
        for (OAuthConfig.FlowKind kind : OAuthConfig.FlowKind.values()) {
            if (kind.name().equals(value)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Remembers which provider and transport the flow in flight belongs to.
     *
     * <p>Needed because the app process can be killed between the redirect
     * and the exchange: on restart the activity has to know whether it is
     * finishing a device-code poll, a callback-server poll or a browser
     * redirect.
     */
    static void savePendingFlow(Context context, String provider, OAuthConfig.FlowKind flow) {
        prefs(context).edit()
                .putString(KEY_PENDING_PROVIDER, provider)
                .putString(KEY_PENDING_FLOW, flow == null ? null : flow.name())
                .apply();
    }

    /**
     * Remembers the redirect URI the runtime resolved for the flow in flight.
     *
     * <p>The runtime does not always use the URI the app asked for: a
     * provider that pins its own redirect (Claude sends the browser to
     * Anthropic's site) makes the runtime return a different one, and the
     * exchange has to repeat it verbatim or the provider rejects the code.
     * Persisting it also lets the exchange survive the app being killed
     * between the redirect and the swap.
     */
    static void savePendingRedirect(Context context, String redirectUri) {
        prefs(context).edit()
                .putString(KEY_PENDING_REDIRECT, redirectUri)
                .apply();
    }

    /**
     * The redirect URI the runtime resolved, or {@code null} when it did not
     * resolve one.
     */
    static String pendingRedirect(Context context) {
        return prefs(context).getString(KEY_PENDING_REDIRECT, null);
    }

    /**
     * The flow type the runtime reported for the provider in flight.
     *
     * <p>Used to decide whether the exchange needs the PKCE verifier: the
     * runtime rejects an exchange that omits it for a PKCE provider, and a
     * plain authorization-code provider has none to send.
     */
    static void savePendingFlowType(Context context, String flowType) {
        prefs(context).edit()
                .putString(KEY_PENDING_FLOW_TYPE, flowType)
                .apply();
    }

    static String pendingFlowType(Context context) {
        return prefs(context).getString(KEY_PENDING_FLOW_TYPE, null);
    }

    static void clearPending(Context context) {
        prefs(context).edit()
                .remove(KEY_PENDING_VERIFIER)
                .remove(KEY_PENDING_STATE)
                .remove(KEY_PENDING_NAME)
                .remove(KEY_PENDING_PROVIDER)
                .remove(KEY_PENDING_FLOW)
                .remove(KEY_PENDING_REDIRECT)
                .remove(KEY_PENDING_FLOW_TYPE)
                .apply();
    }

    /**
     * Remembers a refresh token for a hand-written (custom) OAuth client.
     *
     * <p>Only the custom flow needs this: the gateway-driven presets are
     * persisted by the runtime itself, which keeps the refresh token in
     * {@code storage.sqlite} and rotates it for us.
     */
    static void saveRefreshToken(Context context, String name, String refreshToken,
                                 String expires) {
        context.getApplicationContext()
                .getSharedPreferences(TOKEN_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(name + ".refresh", refreshToken)
                .putString(name + ".expires", expires)
                .apply();
    }
}
