package com.example.omniroute;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Captures the dashboard's one-time bootstrap token.
 *
 * <p>OmniRoute refuses to finish first-run onboarding from a non-loopback peer
 * until the operator proves server-side access by pasting a token that is
 * printed only to the server log. An embedded WebView is exactly such a peer:
 * its request carries no forwarding headers, so the locality check falls back
 * to "remote" even though the dashboard is reached over 127.0.0.1.
 *
 * <p>Reading that token back out of the log by hand is unreliable. The line is
 * written once, near the start of a run, and is then buried under the runtime's
 * own output — hundreds of lines of proxied syscall chatter — so any scan that
 * only looks at the tail of the file finds nothing on precisely the run that
 * needs it. The log pump therefore scrapes the token as the bytes stream past
 * and keeps it here, where the settings screen can show it no matter how large
 * the log has since grown.
 *
 * <p>The value is per-process: the server mints a fresh token every time it
 * starts, so a stored token is valid only until the next restart. The pump
 * clears it at the start of each run for that reason.
 */
final class BootstrapToken {
    private static final String FILE = "omniroute-bootstrap";
    private static final String KEY_TOKEN = "token";
    private static final String MARKER = "[BOOTSTRAP]";

    private BootstrapToken() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Persists a token; null or blank input is ignored. */
    static void save(Context context, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        prefs(context).edit().putString(KEY_TOKEN, token).apply();
    }

    /** Returns the captured token, or null when none is held. */
    static String load(Context context) {
        String value = prefs(context).getString(KEY_TOKEN, null);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Drops a token captured from an earlier server run.
     *
     * <p>Called at the start of each run: the server generates a new token per
     * process, so continuing to offer the old one would send the user to the
     * wizard with a value that has already been retired.
     */
    static void clear(Context context) {
        prefs(context).edit().remove(KEY_TOKEN).apply();
    }

    /**
     * Returns the token carried by the last {@code [BOOTSTRAP]} line in
     * {@code text}, or null when there is none.
     *
     * <p>The server prints it as {@code [BOOTSTRAP] ... continue: <token>} on a
     * single line, so the token is whatever follows the final {@code ": "}
     * there. The match is deliberately loose: the token's own shape is the
     * server's business, and pinning a format here would break the day it
     * changes. The last complete match wins, because a re-print supersedes an
     * earlier one.
     */
    static String scan(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String found = null;
        for (String line : text.split("\n")) {
            if (!line.contains(MARKER)) {
                continue;
            }
            int separator = line.lastIndexOf(": ");
            if (separator < 0 || separator + 2 >= line.length()) {
                continue;
            }
            String candidate = line.substring(separator + 2).trim();
            if (!candidate.isEmpty()) {
                found = candidate;
            }
        }
        return found;
    }
}
