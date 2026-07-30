package io.github.leowood2000.youtubetvadcloser;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;

public final class MainActivity extends Activity {
    private TextView statusView;
    private boolean rootRequested;
    private Boolean rootGranted;

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
        updateStatus();
        requestRootInForeground();
    }

    private void updateStatus() {
        boolean enabled = isServiceEnabled();
        statusView.setText(enabled
                ? getString(R.string.status_enabled)
                : getString(R.string.status_disabled));
        if (rootGranted != null) {
            statusView.append(rootGranted
                    ? getString(R.string.root_granted)
                    : getString(R.string.root_denied));
        }
    }

    private void requestRootInForeground() {
        if (rootRequested) {
            return;
        }
        rootRequested = true;
        new Thread(() -> {
            boolean granted = false;
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", "id").start();
                granted = process.waitFor() == 0;
            } catch (IOException exception) {
                // The status below tells the user that root was unavailable.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            rootGranted = granted;
            runOnUiThread(this::updateStatus);
        }, "RootPermissionRequest").start();
    }

    private boolean isServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }

        ComponentName component = new ComponentName(this, YouTubeAdCloserService.class);
        String[] components = enabledServices.split(":");
        for (String value : components) {
            ComponentName enabledComponent = ComponentName.unflattenFromString(value);
            if (component.equals(enabledComponent)) {
                return true;
            }
        }
        return false;
    }
}
