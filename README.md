# N1 YouTube TV Ad Closer + Volume Key Fix

A small Android AccessibilityService for the N1 TV box that only
monitors the official YouTube TV package (`com.google.android.youtube.tv`).

The N1's Cobalt-based YouTube TV player does not expose its ad controls through
Android's accessibility tree. This app:

- applies the GKD rules for Skip ad, sponsor-ad panel close, and playback-page
  ad More -> Close whenever YouTube TV exposes an accessibility tree;
- intercepts `KEYCODE_VOLUME_UP/DOWN` before YouTube consumes them;
- adjusts `STREAM_MUSIC` directly, including long-press repeat.

## v1.3.0: screenshot fallback removed

The root screenshot fallback (`screencap -p` every 3 s + pixel analysis +
`sendevent`) was removed after it acted as a reliable trigger for a
system_server crash loop on the N1 (Android 9, 32-bit ARM):

- the high-frequency root screencap/sendevent cycle repeatedly changed
  window/surface state while system_server's `TaskSnapshotPersister`
  saved task snapshots;
- `Bitmap.compress -> SkJpegEncoder -> libjpeg start_pass_huff` then
  crashed with SIGSEGV/SIGILL (same function, 16-byte offset apart);
- system_server crashed -> runtime restart -> WiFi/VPN/YouTube dropped and
  `dropbox:netstats_error=disabled` was lost each cycle, feeding the loop.

Note on scope: this is a proven trigger, not a proven root cause. The
native memory corruption may originate in the screencap path, the window
state transitions, the YouTube/Cobalt surface, the GraphicBuffer/vendor
graphics stack, or the JPEG encoder itself; the corruption source has not
been isolated. Also, the pre-reflash N1 likely never actually ran this
scanner (the accessibility service binding was unverified before the
2026-09-21 reflash), so "screenshots were fine before" is not supported.

YouTube TV 5.30.320 (Cobalt) exposes an empty accessibility tree, so the GKD
node path never matches on this build and the screenshot path ran every scan.
With the screenshot path removed, ad skipping on the empty-tree Cobalt build
is no longer automatic; the node rules activate on builds that expose the
tree, and the volume-key fix works everywhere.

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
- No root required since v1.3.0 (screenshot and sendevent paths removed)

## Build

The GitHub Actions workflow builds a debug APK with JDK 17, Gradle 8.5, and
Android Gradle Plugin 8.2.2.

## Enable over ADB

Install the APK, preserve the existing accessibility service list, append:

```text
io.github.leowood2000.youtubetvadcloser/.YouTubeAdCloserService
```

Then set `accessibility_enabled` to `1`.
