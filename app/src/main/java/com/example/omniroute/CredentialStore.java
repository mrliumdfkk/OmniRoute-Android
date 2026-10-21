package com.example.omniroute;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Local, app-private store for credentials the user pastes in by hand.
 *
 * <p>This is deliberately a plain-text notebook, not a vault: the values are
 * only ever read back for display and copy, and nothing here is used to
 * authenticate the app itself. Storing them in SharedPreferences (private mode)
 * keeps them inside the app sandbox, which is the same protection level the
 * rootfs data already has. It is not a substitute for a real keystore and is
 * not meant to hold anything the user would not keep in a notes app.
 *
 * <p>Two independent lists are kept:
 * <ul>
 *   <li>{@link Kind#CLIENT_KEY} — keys the user creates <em>in</em> OmniRoute
 *       and hands to clients (IDE plugins, other apps).</li>
 *   <li>{@link Kind#PROVIDER} — upstream provider credentials the user pastes
 *       <em>into</em> OmniRoute, kept here so the values are findable later.</li>
 * </ul>
 */
final class CredentialStore {

    enum Kind {
        /** Keys created in OmniRoute for clients to call it with. */
        CLIENT_KEY("client_keys"),
        /** Upstream provider credentials (name + base url + api key). */
        PROVIDER("providers");

        final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /** A single entry. Unused fields are empty strings, never null. */
    static final class Entry {
        String id;
        String name;
        String url;
        String secret;

        Entry(String id, String name, String url, String secret) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.secret = secret == null ? "" : secret;
        }
    }

    private static final String FILE = "omniroute-credentials";

    private CredentialStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static List<Entry> load(Context context, Kind kind) {
        String raw = prefs(context).getString(kind.key, "[]");
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                entries.add(new Entry(
                        object.optString("id"),
                        object.optString("name"),
                        object.optString("url"),
                        object.optString("secret")));
            }
        } catch (JSONException ignored) {
            // A corrupted blob must not crash the settings screen; treat it as
            // empty rather than losing the whole screen.
        }
        return entries;
    }

    static void save(Context context, Kind kind, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("name", entry.name);
                object.put("url", entry.url);
                object.put("secret", entry.secret);
            } catch (JSONException ignored) {
                continue;
            }
            array.put(object);
        }
        prefs(context).edit().putString(kind.key, array.toString()).apply();
    }

    /**
     * Appends an entry and returns the updated list. The id is derived from the
     * current time so it stays unique without a counter to persist.
     */
    static List<Entry> add(Context context, Kind kind, String name, String url, String secret) {
        List<Entry> entries = load(context, kind);
        entries.add(new Entry(
                Long.toString(System.currentTimeMillis(), 36),
                name.trim(), url.trim(), secret.trim()));
        save(context, kind, entries);
        return entries;
    }

    static List<Entry> remove(Context context, Kind kind, String id) {
        List<Entry> entries = load(context, kind);
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.id.equals(id)) {
                kept.add(entry);
            }
        }
        save(context, kind, kept);
        return kept;
    }

    /**
     * Masks a secret for display. Short values are masked entirely so a
     * three-character key does not end up fully visible.
     */
    static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= 8) {
            return "••••••••";
        }
        return secret.substring(0, 6) + "••••••••" + secret.substring(secret.length() - 4);
    }
}
