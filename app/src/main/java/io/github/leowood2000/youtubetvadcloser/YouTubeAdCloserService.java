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
    private static final long CAPTION_CHECK_DELAY_MS = 2800;
    private static final long CAPTION_RETRY_INTERVAL_MS = 3000;
    private static final long CAPTION_RESTORE_TIMEOUT_MS = 30000;

    // On the N1's 1920x1080 Cobalt UI, the active Skip button is a large white
    // pill in this normalized area. The unavailable countdown is dark.
    private static final double REGION_LEFT = 0.85;
    private static final double REGION_RIGHT = 0.92;
    private static final double REGION_TOP = 0.875;
    private static final double REGION_BOTTOM = 0.93;
    private static final double ACTIVE_WHITE_RATIO = 0.55;
    private static final double PLAYBACK_SURFACE_DARK_RATIO = 0.90;

    // The CC icon is filled white while captions are enabled and is only a
    // white outline while they are disabled.
    private static final double CC_REGION_LEFT = 0.886;
    private static final double CC_REGION_RIGHT = 0.902;
    private static final double CC_REGION_TOP = 0.719;
    private static final double CC_REGION_BOTTOM = 0.742;
    private static final double CC_BUTTON_PRESENT_RATIO = 0.07;
    private static final double CC_ENABLED_WHITE_RATIO = 0.42;
    private static final double MAIN_TIMELINE_BRIGHT_RATIO = 0.025;

    private ScheduledExecutorService screenScanner;
    private long lastClickAt;
    private long nextCaptionCheckAt;
    private long captionRestoreDeadlineAt;
    private boolean rootFailureLogged;
    private boolean skipButtonLatched;
    private boolean captionRestorePending;

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
        if (captionRestorePending && now >= captionRestoreDeadlineAt) {
            captionRestorePending = false;
            Log.i(TAG, "Caption restore timed out while another ad was active");
        }
        if (now - lastClickAt < CLICK_DEBOUNCE_MS) {
            return;
        }

        Bitmap screenshot = captureScreenAsRoot();
        if (screenshot == null) {
            return;
        }

        boolean shouldCheckCaptions = false;
        try {
            double whiteRatio = skipButtonWhiteRatio(screenshot);
            double darkRatio = playbackSurfaceDarkRatio(screenshot);
            boolean mainControlsVisible =
                    mainTimelineBrightRatio(screenshot)
                            >= MAIN_TIMELINE_BRIGHT_RATIO;
            boolean skipButtonActive = whiteRatio >= ACTIVE_WHITE_RATIO
                    && darkRatio >= PLAYBACK_SURFACE_DARK_RATIO
                    && !mainControlsVisible;

            if (!skipButtonActive) {
                skipButtonLatched = false;
                shouldCheckCaptions = captionRestorePending
                        && now >= nextCaptionCheckAt;
            } else if (!skipButtonLatched) {
                String eventPath = findPhicommRemoteEventPath();
                if (eventPath == null) {
                    logRootFailureOnce("Phicomm remote input device not found");
                } else if (pressPhysicalRemoteKey(eventPath, 28)) {
                    lastClickAt = now;
                    skipButtonLatched = true;
                    captionRestorePending = true;
                    nextCaptionCheckAt = now + CAPTION_CHECK_DELAY_MS;
                    captionRestoreDeadlineAt = now + CAPTION_RESTORE_TIMEOUT_MS;
                    Log.i(TAG, String.format(
                            Locale.ROOT,
                            "Pressed physical remote OK for skip button (white ratio %.3f)",
                            whiteRatio
                    ));
                }
            }
        } finally {
            screenshot.recycle();
        }

        if (shouldCheckCaptions) {
            checkAndRestoreCaptions(now);
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

    private static double playbackSurfaceDarkRatio(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = (int) (width * 0.20);
        int right = (int) (width * 0.80);
        int top = (int) (height * 0.20);
        int bottom = (int) (height * 0.58);

        int sampled = 0;
        int dark = 0;
        for (int y = top; y < bottom; y += 2) {
            for (int x = left; x < right; x += 2) {
                int color = bitmap.getPixel(x, y);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                sampled++;
                if (red <= 35 && green <= 35 && blue <= 35) {
                    dark++;
                }
            }
        }
        return sampled == 0 ? 0 : (double) dark / sampled;
    }

    private void checkAndRestoreCaptions(long now) {
        String eventPath = findPhicommRemoteEventPath();
        if (eventPath == null) {
            logRootFailureOnce("Phicomm remote input device not found");
            nextCaptionCheckAt = now + CAPTION_RETRY_INTERVAL_MS;
            return;
        }
        if (!isYouTubeForeground() || !pressPhysicalRemoteKey(eventPath, 28)) {
            nextCaptionCheckAt = now + CAPTION_RETRY_INTERVAL_MS;
            return;
        }

        SystemClock.sleep(500);
        Bitmap controls = captureScreenAsRoot();
        if (controls == null) {
            nextCaptionCheckAt = now + CAPTION_RETRY_INTERVAL_MS;
            return;
        }

        double timelineRatio;
        double whiteRatio;
        try {
            timelineRatio = mainTimelineBrightRatio(controls);
            whiteRatio = captionButtonWhiteRatio(controls);
        } finally {
            controls.recycle();
        }

        if (timelineRatio < MAIN_TIMELINE_BRIGHT_RATIO) {
            pressPhysicalRemoteKey(eventPath, 158);
            nextCaptionCheckAt = now + CAPTION_RETRY_INTERVAL_MS;
            Log.i(TAG, String.format(
                    Locale.ROOT,
                    "Waiting for main video before caption restore "
                            + "(timeline ratio %.3f)",
                    timelineRatio
            ));
            return;
        }

        if (whiteRatio >= CC_ENABLED_WHITE_RATIO) {
            pressPhysicalRemoteKey(eventPath, 158);
            captionRestorePending = false;
            Log.i(TAG, String.format(
                    Locale.ROOT,
                    "Captions already enabled (CC white ratio %.3f)",
                    whiteRatio
            ));
            return;
        }
        if (whiteRatio < CC_BUTTON_PRESENT_RATIO) {
            pressPhysicalRemoteKey(eventPath, 158);
            captionRestorePending = false;
            Log.i(TAG, String.format(
                    Locale.ROOT,
                    "No CC button detected after skip (white ratio %.3f)",
                    whiteRatio
            ));
            return;
        }

        // RIGHT then LEFT enters seek mode without changing the final playback
        // position. OK confirms seeking and enters the button row. YouTube
        // remembers the previous button focus, so move RIGHT past every button
        // to clamp at the rightmost Settings button, then LEFT once to CC.
        // BACK closes the controls, matching the physical remote workflow.
        StringBuilder command = new StringBuilder();
        appendKeyPress(command, eventPath, 106); // DPAD_RIGHT
        appendKeyPress(command, eventPath, 105); // DPAD_LEFT
        appendKeyPress(command, eventPath, 28);  // DPAD_CENTER
        for (int index = 0; index < 12; index++) {
            appendKeyPress(command, eventPath, 106);
        }
        appendKeyPress(command, eventPath, 105); // Settings -> CC
        appendKeyPress(command, eventPath, 28);  // Enable CC
        command.append("sleep 0.60; ");
        appendKeyPress(command, eventPath, 158); // BACK

        if (runRootCommand(command.toString())) {
            captionRestorePending = false;
            Log.i(TAG, String.format(
                    Locale.ROOT,
                    "Restored captions after skip (CC white ratio %.3f)",
                    whiteRatio
            ));
        } else {
            nextCaptionCheckAt = now + CAPTION_RETRY_INTERVAL_MS;
        }
    }

    private static double mainTimelineBrightRatio(Bitmap bitmap) {
        double leftTime = brightPixelRatio(
                bitmap,
                0.047,
                0.091,
                0.611,
                0.646
        );
        double rightTime = brightPixelRatio(
                bitmap,
                0.914,
                0.956,
                0.611,
                0.646
        );
        return Math.max(leftTime, rightTime);
    }

    private static double brightPixelRatio(
            Bitmap bitmap,
            double regionLeft,
            double regionRight,
            double regionTop,
            double regionBottom
    ) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = (int) (width * regionLeft);
        int right = (int) (width * regionRight);
        int top = (int) (height * regionTop);
        int bottom = (int) (height * regionBottom);

        int sampled = 0;
        int bright = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int color = bitmap.getPixel(x, y);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                sampled++;
                if (red >= 160 && green >= 160 && blue >= 160) {
                    bright++;
                }
            }
        }
        return sampled == 0 ? 0 : (double) bright / sampled;
    }

    private static double captionButtonWhiteRatio(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = (int) (width * CC_REGION_LEFT);
        int right = (int) (width * CC_REGION_RIGHT);
        int top = (int) (height * CC_REGION_TOP);
        int bottom = (int) (height * CC_REGION_BOTTOM);

        int sampled = 0;
        int white = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
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

    private boolean pressPhysicalRemoteKey(String eventPath, int scanCode) {
        StringBuilder command = new StringBuilder();
        appendKeyPress(command, eventPath, scanCode);
        return runRootCommand(command.toString());
    }

    private static void appendKeyPress(
            StringBuilder command,
            String eventPath,
            int scanCode
    ) {
        command.append("sendevent ").append(eventPath)
                .append(" 1 ").append(scanCode).append(" 1; ")
                .append("sendevent ").append(eventPath).append(" 0 0 0; ")
                .append("sleep 0.10; ")
                .append("sendevent ").append(eventPath)
                .append(" 1 ").append(scanCode).append(" 0; ")
                .append("sendevent ").append(eventPath).append(" 0 0 0; ")
                .append("sleep 0.18; ");
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
