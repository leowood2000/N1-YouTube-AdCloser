# N1 YouTube TV Ad Closer + Volume Key Fix

A small root-assisted Android AccessibilityService for the N1 TV box that only
monitors the official YouTube TV package (`com.google.android.youtube.tv`).

The N1's Cobalt-based YouTube TV player does not expose its ad controls through
Android's accessibility tree. This app therefore:

- checks only while YouTube TV is in the foreground;
- takes a low-frequency root screenshot;
- detects the active white Skip button at the bottom-right;
- sends the same DPAD_CENTER key as the remote control.
- intercepts `KEYCODE_VOLUME_UP/DOWN` before YouTube consumes them;
- adjusts `STREAM_MUSIC` directly, including long-press repeat.

It does not traverse or click inaccessible nodes and only monitors YouTube TV.

## Volume controls

- Single press changes media volume by one step.
- Holding a volume key starts repeating after 400 ms.
- Repeat interval is 100 ms and stops immediately on key release.
- Other remote keys are not consumed.

The service requests `FLAG_REQUEST_FILTER_KEY_EVENTS` both in XML and again
programmatically. The programmatic request is required by some Android 9 TV
firmware even when `canRequestFilterKeyEvents` is declared.

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
