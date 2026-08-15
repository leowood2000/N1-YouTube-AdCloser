package io.github.leowood2000.youtubetvadcloser;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
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

    // Let Cobalt finish its network and video initialization before the first
    // root screencap.  The old 1.2.0 cadence added avoidable load during startup.
    private static final long SCREEN_SCAN_START_DELAY_MS = 12000;
    private static final long SCREEN_SCAN_INTERVAL_MS = 3000;
    private static final long CLICK_DEBOUNCE_MS = 5000;
    private static final long ACCESSIBILITY_ACTION_DEBOUNCE_MS = 700;
    private static final long SEGMENT_MENU_TIMEOUT_MS = 2500;

    // On the N1's 1920x1080 Cobalt UI, the active Skip button is a large white
    // pill in this normalized area. The unavailable countdown is dark.
    private static final double REGION_LEFT = 0.85;
    private static final double REGION_RIGHT = 0.92;
    private static final double REGION_TOP = 0.875;
    private static final double REGION_BOTTOM = 0.93;
    private static final double ACTIVE_WHITE_RATIO = 0.55;
    private static final double PLAYBACK_SURFACE_DARK_RATIO = 0.90;

    private static final double MAIN_TIMELINE_BRIGHT_RATIO = 0.025;

    private ScheduledExecutorService screenScanner;
    private AudioManager audioManager;
    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private int heldVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable repeatVolume = new Runnable() {
        @Override
        public void run() {
            if (heldVolumeKey == KeyEvent.KEYCODE_VOLUME_UP
                    || heldVolumeKey == KeyEvent.KEYCODE_VOLUME_DOWN) {
                changeMediaVolume(heldVolumeKey);
                volumeHandler.postDelayed(this, 100);
            }
        }
    };
    private long lastClickAt;
    private long lastAccessibilityActionAt;
    private long segmentMenuOpenedAt;
    private boolean rootFailureLogged;
    private boolean skipButtonLatched;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        startScreenScanner();
        Log.i(TAG, "Service connected; ad skip and volume key fix enabled");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            heldVolumeKey = keyCode;
            volumeHandler.removeCallbacks(repeatVolume);
            changeMediaVolume(keyCode);
            volumeHandler.postDelayed(repeatVolume, 400);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            stopVolumeRepeat();
        }
        return true;
    }

    private void changeMediaVolume(int keyCode) {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? Math.min(current + 1, maximum)
                : Math.max(current - 1, 0);
        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                target,
                AudioManager.FLAG_SHOW_UI
        );
        Log.i(TAG, "Volume key " + keyCode + ": " + current + " -> " + target);
    }

    private void stopVolumeRepeat() {
        heldVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
        volumeHandler.removeCallbacks(repeatVolume);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null
                || !YOUTUBE_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        // The GKD YouTube rules use these semantic controls:
        // - skip_ad_button / modern_skip_ad_text: skip a full-screen video ad;
        // - Close ad panel / panel_header: close a sponsor-ad panel;
        // - collapsible_ad_cta_overlay_container -> overflow_button -> Close:
        //   open the playback-page ad menu and select Close.
        // The TV Cobalt build normally exposes none of these nodes, so the
        // screenshot path below remains the fallback for the N1 surface.
        applyGkdYouTubeRules();
    }

    private boolean applyGkdYouTubeRules() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }

        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastAccessibilityActionAt < ACCESSIBILITY_ACTION_DEBOUNCE_MS) {
                return false;
            }

            if (segmentMenuOpenedAt != 0
                    && now - segmentMenuOpenedAt <= SEGMENT_MENU_TIMEOUT_MS) {
                AccessibilityNodeInfo closeNode = findTextOrDescription(
                        root, "关闭", "Close");
                if (closeNode != null) {
                    try {
                        if (clickNodeOrClickableParent(closeNode)) {
                            segmentMenuOpenedAt = 0;
                            return true;
                        }
                    } finally {
                        closeNode.recycle();
                    }
                }
            } else {
                segmentMenuOpenedAt = 0;
            }

            AccessibilityNodeInfo skipNode = findNodeByViewIdSuffix(
                    root, "skip_ad_button");
            if (skipNode == null) {
                skipNode = findNodeByViewIdSuffix(root, "modern_skip_ad_text");
            }
            if (skipNode != null) {
                try {
                    if (clickNodeOrClickableParent(skipNode)) {
                        return true;
                    }
                } finally {
                    skipNode.recycle();
                }
            }

            AccessibilityNodeInfo closePanelNode = findTextOrDescription(
                    root, "关闭广告面板", "Close ad panel");
            if (closePanelNode != null) {
                try {
                    if (clickNodeOrClickableParent(closePanelNode)) {
                        return true;
                    }
                } finally {
                    closePanelNode.recycle();
                }
            }

            AccessibilityNodeInfo panelHeader = findNodeByViewIdSuffix(
                    root, "panel_header");
            if (panelHeader != null) {
                try {
                    if (containsAdLabel(panelHeader)) {
                        AccessibilityNodeInfo lastClickable =
                                findLastClickableDescendant(panelHeader);
                        if (lastClickable != null) {
                            try {
                                if (clickNodeOrClickableParent(lastClickable)) {
                                    return true;
                                }
                            } finally {
                                lastClickable.recycle();
                            }
                        }
                    }
                } finally {
                    panelHeader.recycle();
                }
            }

            AccessibilityNodeInfo adOverlay = findNodeByViewIdSuffix(
                    root, "collapsible_ad_cta_overlay_container");
            if (adOverlay != null) {
                try {
                    AccessibilityNodeInfo overflow = findNodeByViewIdSuffix(
                            adOverlay, "overflow_button");
                    if (overflow != null) {
                        try {
                            if (clickNodeOrClickableParent(overflow)) {
                                segmentMenuOpenedAt = now;
                                return true;
                            }
                        } finally {
                            overflow.recycle();
                        }
                    }
                } finally {
                    adOverlay.recycle();
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private AccessibilityNodeInfo findNodeByViewIdSuffix(
            AccessibilityNodeInfo node,
            String suffix
    ) {
        String viewId = node.getViewIdResourceName();
        if (viewId != null && viewId.endsWith("/" + suffix)) {
            return AccessibilityNodeInfo.obtain(node);
        }

        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo result = findNodeByViewIdSuffix(child, suffix);
            child.recycle();
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findTextOrDescription(
            AccessibilityNodeInfo node,
            String... values
    ) {
        if (matchesTextOrDescription(node, values)) {
            return AccessibilityNodeInfo.obtain(node);
        }

        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo result = findTextOrDescription(child, values);
            child.recycle();
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static boolean matchesTextOrDescription(
            AccessibilityNodeInfo node,
            String... values
    ) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        for (String value : values) {
            if ((text != null && value.contentEquals(text))
                    || (description != null && value.contentEquals(description))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAdLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (isAdLabel(text) || isAdLabel(description)) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            boolean found = containsAdLabel(child);
            child.recycle();
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdLabel(CharSequence value) {
        if (value == null) {
            return false;
        }
        String label = value.toString().toLowerCase(Locale.ROOT);
        return label.endsWith("广告") || label.contains("ad ");
    }

    private static AccessibilityNodeInfo findLastClickableDescendant(
            AccessibilityNodeInfo node
    ) {
        AccessibilityNodeInfo result = null;
        if (node.isClickable() && node.isVisibleToUser()) {
            result = AccessibilityNodeInfo.obtain(node);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo childResult = findLastClickableDescendant(child);
            child.recycle();
            if (childResult != null) {
                if (result != null) {
                    result.recycle();
                }
                result = childResult;
            }
        }
        return result;
    }

    private boolean clickNodeOrClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; current != null && depth < 4; depth++) {
                if (current.isVisibleToUser()
                        && current.isEnabled()
                        && current.isClickable()
                        && current.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastAccessibilityActionAt = SystemClock.uptimeMillis();
                    lastClickAt = lastAccessibilityActionAt;
                    Log.i(TAG, "Applied GKD YouTube ad rule via accessibility node");
                    return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) {
                current.recycle();
            }
        }
        return false;
    }

    private void startScreenScanner() {
        if (screenScanner != null && !screenScanner.isShutdown()) {
            return;
        }
        screenScanner = Executors.newSingleThreadScheduledExecutor();
        screenScanner.scheduleWithFixedDelay(
                this::scanForSkipButton,
                SCREEN_SCAN_START_DELAY_MS,
                SCREEN_SCAN_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void scanForSkipButton() {
        if (!isYouTubeForeground()) {
            return;
        }

        // Prefer the exact GKD semantic targets when a future TV build
        // exposes them. The current Cobalt build usually has an empty tree,
        // so continue with the visual fallback below.
        applyGkdYouTubeRules();

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
            double darkRatio = playbackSurfaceDarkRatio(screenshot);
            boolean mainControlsVisible =
                    mainTimelineBrightRatio(screenshot)
                            >= MAIN_TIMELINE_BRIGHT_RATIO;
            boolean skipButtonActive = whiteRatio >= ACTIVE_WHITE_RATIO
                    && darkRatio >= PLAYBACK_SURFACE_DARK_RATIO
                    && !mainControlsVisible;

            if (!skipButtonActive) {
                skipButtonLatched = false;
            } else if (!skipButtonLatched) {
                String eventPath = findPhicommRemoteEventPath();
                if (eventPath == null) {
                    logRootFailureOnce("Phicomm remote input device not found");
                } else if (pressPhysicalRemoteOk(eventPath)) {
                    lastClickAt = now;
                    skipButtonLatched = true;
                    Log.i(TAG, String.format(
                            Locale.ROOT,
                            "Pressed physical remote OK for GKD-compatible ad skip action "
                                    + "(white ratio %.3f)",
                            whiteRatio
                    ));
                }
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

    private boolean pressPhysicalRemoteOk(String eventPath) {
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
        stopVolumeRepeat();
        Log.w(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        stopVolumeRepeat();
        if (screenScanner != null) {
            screenScanner.shutdownNow();
            screenScanner = null;
        }
        super.onDestroy();
    }
}
