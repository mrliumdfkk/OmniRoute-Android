package com.example.omniroute;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/**
 * Hand-maintained list of credentials.
 *
 * <p>Entries are pasted in by the user and stored locally; nothing is read from
 * the OmniRoute database, so a schema change upstream cannot break this screen.
 * The whole list is rendered on the UI thread because it is small by nature.
 */
public final class CredentialListActivity extends Activity {

    private static final String EXTRA_KIND = "kind";

    private CredentialStore.Kind kind;
    private LinearLayout container;
    private TextView emptyView;

    static Intent intentFor(Context context, CredentialStore.Kind kind) {
        Intent intent = new Intent(context, CredentialListActivity.class);
        intent.putExtra(EXTRA_KIND, kind.name());
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credential_list);
        applyWindowInsets(findViewById(R.id.list_root));

        kind = readKind();
        container = findViewById(R.id.list_container);
        emptyView = findViewById(R.id.list_empty);

        boolean isProvider = kind == CredentialStore.Kind.PROVIDER;
        ((TextView) findViewById(R.id.list_title)).setText(isProvider
                ? R.string.settings_providers_title
                : R.string.settings_client_keys_title);
        ((TextView) findViewById(R.id.list_hint)).setText(isProvider
                ? R.string.settings_providers_hint
                : R.string.settings_client_keys_hint);
        emptyView.setText(isProvider
                ? R.string.cred_empty_providers
                : R.string.cred_empty_keys);

        findViewById(R.id.list_back).setOnClickListener(v -> finish());
        findViewById(R.id.list_add).setOnClickListener(v -> showAddDialog());

        render();
    }

    private CredentialStore.Kind readKind() {
        String raw = getIntent().getStringExtra(EXTRA_KIND);
        try {
            return CredentialStore.Kind.valueOf(raw == null ? "" : raw);
        } catch (IllegalArgumentException ignored) {
            return CredentialStore.Kind.CLIENT_KEY;
        }
    }

    private void render() {
        List<CredentialStore.Entry> entries = CredentialStore.load(this, kind);
        container.removeAllViews();
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (CredentialStore.Entry entry : entries) {
            container.addView(buildRow(inflater, entry));
        }
    }

    private View buildRow(LayoutInflater inflater, CredentialStore.Entry entry) {
        View row = inflater.inflate(R.layout.item_credential, container, false);

        TextView name = row.findViewById(R.id.item_name);
        name.setText(entry.name.isEmpty() ? getString(R.string.cred_unnamed) : entry.name);

        TextView url = row.findViewById(R.id.item_url);
        if (entry.url.isEmpty()) {
            // Client keys carry no base url; the address is shown on the
            // settings screen instead.
            url.setVisibility(View.GONE);
        } else {
            url.setText(entry.url);
        }

        TextView secret = row.findViewById(R.id.item_secret);
        ImageView toggle = row.findViewById(R.id.item_toggle);
        // Each row owns its own reveal state; it resets when the screen reloads
        // so a key is never left visible by accident.
        final boolean[] revealed = {false};
        secret.setText(CredentialStore.mask(entry.secret));
        toggle.setOnClickListener(v -> {
            revealed[0] = !revealed[0];
            secret.setText(revealed[0] ? entry.secret : CredentialStore.mask(entry.secret));
            toggle.setImageResource(revealed[0] ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            toggle.setContentDescription(getString(
                    revealed[0] ? R.string.cred_hide : R.string.cred_show));
        });

        // Tapping the value copies it, which is the main reason this screen
        // exists: moving a key into another app without retyping it.
        secret.setOnClickListener(v -> copy(entry.secret));

        row.findViewById(R.id.item_delete).setOnClickListener(v -> confirmDelete(entry));
        return row;
    }

    private void copy(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), value));
        Toast.makeText(this, R.string.cred_copied, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(CredentialStore.Entry entry) {
        String label = entry.name.isEmpty() ? getString(R.string.cred_unnamed) : entry.name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.cred_delete)
                .setMessage(getString(R.string.cred_delete_confirm, label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cred_delete, (dialog, which) -> {
                    CredentialStore.remove(this, kind, entry.id);
                    render();
                })
                .show();
    }

    private void showAddDialog() {
        boolean isProvider = kind == CredentialStore.Kind.PROVIDER;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText nameField = addField(form, getString(R.string.cred_name_hint));
        EditText urlField = isProvider ? addField(form, getString(R.string.cred_url_hint)) : null;
        EditText secretField = addField(form, isProvider
                ? getString(R.string.cred_secret_hint_provider)
                : getString(R.string.cred_secret_hint_key));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isProvider
                        ? R.string.settings_providers_title
                        : R.string.settings_client_keys_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cred_add, null)
                .create();

        // Wired after show() so an invalid submit can keep the dialog open
        // instead of losing what the user typed.
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String secret = secretField.getText().toString().trim();
                    if (secret.isEmpty()) {
                        Toast.makeText(this, R.string.cred_secret_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CredentialStore.add(this, kind,
                            nameField.getText().toString(),
                            urlField == null ? "" : urlField.getText().toString(),
                            secret);
                    dialog.dismiss();
                    render();
                }));
        dialog.show();
    }

    private EditText addField(LinearLayout parent, String hint) {
        EditText field = new EditText(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
        field.setLayoutParams(params);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setMaxLines(1);
        // A programmatic EditText gets the platform's default background: a
        // light filled box with a coloured underline, which reads as a foreign
        // element inside the app's dark dialog. Using the app's own input
        // drawable keeps the dialog consistent with every other form.
        field.setBackgroundResource(R.drawable.bg_input);
        field.setTextColor(getColor(R.color.text_primary));
        field.setHintTextColor(getColor(R.color.text_secondary));
        // bg_input is a plain shape without insets, so the padding the
        // platform background used to provide has to be set by hand —
        // otherwise the text sits flush against the border.
        int padH = (int) (12 * getResources().getDisplayMetrics().density);
        int padV = (int) (10 * getResources().getDisplayMetrics().density);
        field.setPadding(padH, padV, padH, padV);
        parent.addView(field);
        return field;
    }

    /** Same edge-to-edge handling as the other screens. */
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
