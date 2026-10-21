package com.example.omniroute;

import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import org.json.JSONObject;

/**
 * OAuth 2.0 redirect parsing, PKCE generation and token exchange.
 *
 * <p>The bundled rootfs shipped without playwright, which is what OmniRoute's
 * browser-automation providers used to complete upstream OAuth logins. This
 * class restores that capability without a browser engine: the consent page is
 * rendered in an app WebView (or the system browser) and the redirect is
 * captured here, so a user can turn an OAuth flow into a bearer token stored as
 * a provider credential.
 *
 * <p>No third-party HTTP or JSON library is needed: token exchange uses
 * {@link HttpURLConnection} and responses are read with {@code org.json}, both
 * of which already ship in the Android framework.
 */
final class OAuthCapture {
    private OAuthCapture() {}

    /** Parsed pieces of an OAuth redirect URI. */
    static final class Result {
        final String error;
        final String errorDescription;
        final String code;
        final String state;
        /** Present only for implicit / fragment-token responses. */
        final String accessToken;
        final String refreshToken;
        final String tokenType;
        final String expiresIn;
        final String scope;

        Result(String error, String errorDescription, String code, String state,
               String accessToken, String refreshToken, String tokenType,
               String expiresIn, String scope) {
            this.error = error;
            this.errorDescription = errorDescription;
            this.code = code;
            this.state = state;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.tokenType = tokenType;
            this.expiresIn = expiresIn;
            this.scope = scope;
        }

        boolean isError() {
            return error != null && !error.isEmpty();
        }

        boolean hasCode() {
            return code != null && !code.isEmpty();
        }

        boolean hasToken() {
            return accessToken != null && !accessToken.isEmpty();
        }
    }

    /** PKCE pair for a single authorization request. */
    static final class Pkce {
        final String verifier;
        final String challenge;

        Pkce(String verifier, String challenge) {
            this.verifier = verifier;
            this.challenge = challenge;
        }
    }

    /** Generates a fresh PKCE pair (S256). */
    static Pkce generatePkce() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        // base64url(32 bytes) is exactly 43 chars, the minimum verifier length.
        String verifier = base64Url(bytes);
        String challenge = base64Url(sha256(verifier));
        return new Pkce(verifier, challenge);
    }

    /** Generates a non-secret state token used to bind the redirect to this request. */
    static String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /** Pulls the OAuth parameters out of a redirect, checking both query and fragment. */
    static Result parse(Uri uri) {
        return new Result(
                firstParam(uri, "error"),
                firstParam(uri, "error_description"),
                firstParam(uri, "code"),
                firstParam(uri, "state"),
                firstParam(uri, "access_token"),
                firstParam(uri, "refresh_token"),
                firstParam(uri, "token_type"),
                firstParam(uri, "expires_in"),
                firstParam(uri, "scope"));
    }

    /**
     * Exchanges an authorization code for tokens. Must be called off the main
     * thread; the response body is returned verbatim for the caller to parse.
     */
    static String exchange(String tokenUrl,
                           String code,
                           String redirectUri,
                           String clientId,
                           String clientSecret,
                           String codeVerifier) throws IOException {
        StringBuilder form = new StringBuilder();
        appendParam(form, "grant_type", "authorization_code");
        appendParam(form, "code", code);
        appendParam(form, "redirect_uri", redirectUri);
        appendParam(form, "client_id", clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            appendParam(form, "client_secret", clientSecret);
        }
        if (codeVerifier != null && !codeVerifier.isEmpty()) {
            appendParam(form, "code_verifier", codeVerifier);
        }
        return postForm(tokenUrl, form.toString());
    }

    /** Parses the JSON returned by a token endpoint. */
    static Result parseTokenResponse(String json) {
        try {
            JSONObject object = new JSONObject(json);
            return new Result(
                    opt(object, "error"),
                    opt(object, "error_description"),
                    null,
                    null,
                    opt(object, "access_token"),
                    opt(object, "refresh_token"),
                    opt(object, "token_type"),
                    object.has("expires_in") && !object.isNull("expires_in")
                            ? String.valueOf(object.optInt("expires_in")) : null,
                    opt(object, "scope"));
        } catch (Exception exception) {
            return new Result("invalid_token_response",
                    exception.getMessage() == null ? "invalid JSON" : exception.getMessage(),
                    null, null, null, null, null, null, null);
        }
    }

    private static String postForm(String tokenUrl, String formBody) throws IOException {
        URL url = new URL(tokenUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = formBody.getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream input = (status >= 200 && status < 300)
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readAll(input);
            if (status < 200 || status >= 300) {
                throw new IOException("token endpoint HTTP " + status
                        + (response.isEmpty() ? "" : ": " + response));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String firstParam(Uri uri, String key) {
        if (uri == null) {
            return null;
        }
        String value = uri.getQueryParameter(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // Fragment parameters are not parsed by Uri; pick them out manually so
        // implicit-flow tokens, which live in the fragment, are captured.
        String fragment = uri.getFragment();
        if (fragment != null) {
            for (String pair : fragment.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && key.equals(pair.substring(0, eq))) {
                    try {
                        return Uri.decode(pair.substring(eq + 1));
                    } catch (Exception ignored) {
                        return pair.substring(eq + 1);
                    }
                }
            }
        }
        return null;
    }

    private static String opt(JSONObject object, String key) {
        // optString(name, null) historically returned "" on some API levels when
        // the key was absent, so gate on has()/isNull() to get a real null.
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static void appendParam(StringBuilder form, String key, String value) {
        if (form.length() > 0) {
            form.append('&');
        }
        form.append(enc(key)).append('=').append(enc(value));
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 not supported", impossible);
        }
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
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
