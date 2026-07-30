package io.github.leowood2000.youtubetvadcloser;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class YouTubeAdCloserService extends AccessibilityService {
    private static final String TAG = "YTAdCloser";
    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube.tv";

    private static final long SCREEN_SCAN_INTERVAL_MS = 1400;
    private static final long CLICK_DEBOUNCE_MS = 5000;

    // On the N1's 1920x1080 Cobalt UI, the active Skip button is a large white
    // pill in this normalized area. The unavailable countdown is dark.
    private static final double REGION_LEFT = 0.85;
    private static final double REGION_RIGHT = 0.92;
    private static final double REGION_TOP = 0.875;
    private static final double REGION_BOTTOM = 0.93;
    private static final double ACTIVE_WHITE_RATIO = 0.55;

    private ScheduledExecutorService screenScanner;
    private long lastClickAt;
    private boolean rootFailureLogged;
    private boolean skipButtonLatched;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startScreenScanner();
        Log.i(TAG, "Service connected; root visual skip detection enabled");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Cobalt draws the player UI onto a custom surface and exposes no useful
        // button nodes. Events are intentionally not traversed.
    }

    private void startScreenScanner() {
        if (screenScanner != null && !screenScanner.isShutdown()) {
            return;
        }
        screenScanner = Executors.newSingleThreadScheduledExecutor();
        screenScanner.scheduleWithFixedDelay(
                this::scanForSkipButton,
                1000,
                SCREEN_SCAN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void scanForSkipButton() {
        if (!isYouTubeForeground()) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastClickAt < CLICK_DEBOUNCE_MS) {
            return;
        }

        Bitmap screenshot = captureScreenAsRoot();
        if (screenshot == null) {
            return;
        }

        try {
            double whiteRatio = skipButtonWhiteRatio(screenshot);
            if (whiteRatio < ACTIVE_WHITE_RATIO) {
                skipButtonLatched = false;
                return;
            }
            if (skipButtonLatched) {
                return;
            }

            if (pressPhysicalRemoteOk()) {
                lastClickAt = now;
                skipButtonLatched = true;
                Log.i(TAG, String.format(
                        Locale.ROOT,
                        "Pressed physical remote OK for skip button (white ratio %.3f)",
                        whiteRatio
                ));
            }
        } finally {
            screenshot.recycle();
        }
    }

    private boolean isYouTubeForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        try {
            CharSequence packageName = root.getPackageName();
            return packageName != null && YOUTUBE_PACKAGE.contentEquals(packageName);
        } finally {
            root.recycle();
        }
    }

    private Bitmap captureScreenAsRoot() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "screencap -p").start();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap bitmap;
            try (InputStream output = process.getInputStream()) {
                bitmap = BitmapFactory.decodeStream(output, null, options);
            }
            int exitCode = process.waitFor();
            if (exitCode == 0 && bitmap != null) {
                rootFailureLogged = false;
                return bitmap;
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
            logRootFailureOnce("Root screenshot failed with exit code " + exitCode);
        } catch (IOException exception) {
            logRootFailureOnce("Root screenshot failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return null;
    }

    private static double skipButtonWhiteRatio(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = (int) (width * REGION_LEFT);
        int right = (int) (width * REGION_RIGHT);
        int top = (int) (height * REGION_TOP);
        int bottom = (int) (height * REGION_BOTTOM);

        int sampled = 0;
        int white = 0;
        for (int y = top; y < bottom; y += 2) {
            for (int x = left; x < right; x += 2) {
                int color = bitmap.getPixel(x, y);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                sampled++;
                if (red >= 235 && green >= 235 && blue >= 235) {
                    white++;
                }
            }
        }
        return sampled == 0 ? 0 : (double) white / sampled;
    }

    private boolean pressPhysicalRemoteOk() {
        String eventPath = findPhicommRemoteEventPath();
        if (eventPath == null) {
            logRootFailureOnce("Phicomm remote input device not found");
            return false;
        }

        String command = "sendevent " + eventPath + " 1 28 1; "
                + "sendevent " + eventPath + " 0 0 0; "
                + "sleep 0.12; "
                + "sendevent " + eventPath + " 1 28 0; "
                + "sendevent " + eventPath + " 0 0 0";
        return runRootCommand(command);
    }

    private static String findPhicommRemoteEventPath() {
        boolean isPhicommRemote = false;
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/bus/input/devices"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    isPhicommRemote = line.contains("斐讯遥控器");
                    continue;
                }
                if (line.isEmpty()) {
                    isPhicommRemote = false;
                    continue;
                }
                if (!isPhicommRemote || !line.startsWith("H: Handlers=")) {
                    continue;
                }

                String[] handlers = line.substring("H: Handlers=".length())
                        .trim()
                        .split("\\s+");
                for (String handler : handlers) {
                    if (handler.matches("event\\d+")) {
                        return "/dev/input/" + handler;
                    }
                }
            }
        } catch (IOException exception) {
            Log.e(TAG, "Unable to read input device list", exception);
        }
        return null;
    }

    private boolean runRootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                rootFailureLogged = false;
                return true;
            }
            logRootFailureOnce("Root input failed with exit code " + exitCode);
        } catch (IOException exception) {
            logRootFailureOnce("Root input failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }

    private void logRootFailureOnce(String message) {
        if (!rootFailureLogged) {
            rootFailureLogged = true;
            Log.e(TAG, message);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        if (screenScanner != null) {
            screenScanner.shutdownNow();
            screenScanner = null;
        }
        super.onDestroy();
    }
}
