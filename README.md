# N1 YouTube TV Ad Closer

A small Android AccessibilityService for Android TV boxes that only monitors the
official YouTube TV package (`com.google.android.youtube.tv`).

It clicks visible, enabled ad controls with these exact labels:

- 隐藏广告 / 隱藏廣告 / Hide ad
- 关闭广告面板 / 關閉廣告面板 / Close ad panel
- 跳过广告 / 跳過廣告 / Skip ad / Skip ads

The service does not handle other packages and does not click advertisement
content or “Learn more” actions.

## Compatibility

- Android 7.0 and newer
- Tested target: Android 9 (API 28), 32-bit `armeabi-v7a`
- Official YouTube TV package

## Build

The GitHub Actions workflow builds a debug APK with JDK 17, Gradle 8.5, and
Android Gradle Plugin 8.2.2.

## Enable over ADB

Install the APK, preserve the existing accessibility service list, append:

```text
io.github.leowood2000.youtubetvadcloser/.YouTubeAdCloserService
```

Then set `accessibility_enabled` to `1`.

