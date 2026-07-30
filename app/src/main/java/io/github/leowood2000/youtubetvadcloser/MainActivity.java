package io.github.leowood2000.youtubetvadcloser;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(24);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(64, 64, 64, 64);
        statusView.setBackgroundColor(Color.rgb(18, 18, 18));
        setContentView(
                statusView,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isServiceEnabled();
        statusView.setText(enabled
                ? getString(R.string.status_enabled)
                : getString(R.string.status_disabled));
    }

    private boolean isServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }

        ComponentName component = new ComponentName(this, YouTubeAdCloserService.class);
        String expected = component.flattenToString();
        String[] components = enabledServices.split(":");
        for (String value : components) {
            if (expected.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

