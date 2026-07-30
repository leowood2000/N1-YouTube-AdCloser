# N1 YouTube TV Ad Closer

A small root-assisted Android AccessibilityService for the N1 TV box that only
monitors the official YouTube TV package (`com.google.android.youtube.tv`).

The N1's Cobalt-based YouTube TV player does not expose its ad controls through
Android's accessibility tree. This app therefore:

- checks only while YouTube TV is in the foreground;
- takes a low-frequency root screenshot;
- detects the active white Skip button at the bottom-right;
- sends the same DPAD_CENTER key as the remote control.

It does not traverse or click inaccessible nodes and does not monitor other apps.

## Compatibility

- Android 7.0 and newer
- Tested target: Android 9 (API 28), 32-bit `armeabi-v7a`
- Official YouTube TV package
- Root access through `su`

## Build

The GitHub Actions workflow builds a debug APK with JDK 17, Gradle 8.5, and
Android Gradle Plugin 8.2.2.

## Enable over ADB

Install the APK, preserve the existing accessibility service list, append:

```text
io.github.leowood2000.youtubetvadcloser/.YouTubeAdCloserService
```

Then set `accessibility_enabled` to `1`.

SuperSU asks for root access the first time screen detection starts. Grant it
permanently for unattended operation.
