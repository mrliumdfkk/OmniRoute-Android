package com.example.omniroute;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Transparent receiver for the OAuth custom-scheme redirect.
 *
 * <p>The consent page runs in the system browser when the user opts out of the
 * in-app WebView (Google and several others block embedded WebViews), so the
 * redirect target is a custom URI scheme this activity owns. It forwards the
 * redirect to {@link OAuthActivity} and exits, keeping the heavy WebView UI out
 * of the exported component.
 *
 * <p>The activity has no UI on purpose: it only exists so the manifest can own
 * the {@code omniroute-oauth} scheme intent-filter in a component that is safe
 * to expose to other apps.
 */
public final class OAuthCallbackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri redirect = getIntent() != null ? getIntent().getData() : null;
        if (redirect != null) {
            Intent target = new Intent(this, OAuthActivity.class);
            target.setData(redirect);
            target.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(target);
        }
        finish();
    }
}
