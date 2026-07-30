package io.github.leowood2000.youtubetvadcloser;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class YouTubeAdCloserService extends AccessibilityService {
    private static final String TAG = "YTAdCloser";
    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube.tv";
    private static final long EVENT_THROTTLE_MS = 180;
    private static final long CLICK_DEBOUNCE_MS = 1400;

    private static final Set<String> HIDE_AD_LABELS = new HashSet<>(Arrays.asList(
            "隐藏广告",
            "隱藏廣告",
            "hide ad",
            "关闭广告面板",
            "關閉廣告面板",
            "close ad panel"
    ));

    private static final Set<String> SKIP_AD_LABELS = new HashSet<>(Arrays.asList(
            "跳过广告",
            "跳過廣告",
            "跳过",
            "跳過",
            "skip ad",
            "skip ads",
            "skip"
    ));

    private long lastEventAt;
    private long lastClickAt;
    private String lastClickedLabel = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected; monitoring YouTube TV only");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        if (!YOUTUBE_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastEventAt < EVENT_THROTTLE_MS) {
            return;
        }
        lastEventAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        try {
            inspectTree(root);
        } finally {
            root.recycle();
        }
    }

    private boolean inspectTree(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) {
            return false;
        }

        String label = nodeLabel(node);
        if (isAdControlLabel(label) && clickNodeOrAncestor(node, label)) {
            return true;
        }

        int childCount = node.getChildCount();
        for (int index = 0; index < childCount; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            try {
                if (inspectTree(child)) {
                    return true;
                }
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo original, String label) {
        long now = SystemClock.uptimeMillis();
        if (label.equals(lastClickedLabel) && now - lastClickAt < CLICK_DEBOUNCE_MS) {
            return false;
        }

        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(original);
        try {
            for (int depth = 0; depth <= 4 && current != null; depth++) {
                if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                    boolean clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (clicked) {
                        lastClickedLabel = label;
                        lastClickAt = now;
                        Log.i(TAG, "Clicked YouTube ad control: " + label);
                        current.recycle();
                        current = null;
                        return true;
                    }
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

    private static boolean isAdControlLabel(String label) {
        return !label.isEmpty()
                && (HIDE_AD_LABELS.contains(label) || SKIP_AD_LABELS.contains(label));
    }

    private static String nodeLabel(AccessibilityNodeInfo node) {
        CharSequence description = node.getContentDescription();
        if (description != null && description.length() > 0) {
            return normalize(description);
        }

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return normalize(text);
        }
        return "";
    }

    private static String normalize(CharSequence value) {
        return value.toString().trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
    }
}
