package io.github.leowood2000.youtubetvadcloser;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

public final class YouTubeAdCloserService extends AccessibilityService {
    private static final String TAG = "YTAdCloser";
    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube.tv";

    private static final long ACCESSIBILITY_ACTION_DEBOUNCE_MS = 700;
    private static final long SEGMENT_MENU_TIMEOUT_MS = 2500;

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
    private long lastAccessibilityActionAt;
    private long segmentMenuOpenedAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
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
        // The screenshot fallback was removed in 1.3.0: root screencap
        // triggered N1 system_server TaskSnapshot -> libjpeg crashes.
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

    @Override
    public void onInterrupt() {
        stopVolumeRepeat();
        Log.w(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        stopVolumeRepeat();
        super.onDestroy();
    }
}
