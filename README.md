# android-cam

A companion APK that streams a phone camera to a Linux host, for phones
`scrcpy --video-source=camera` cannot serve.

scrcpy drives the camera as the shell uid. Upstream gates that at Android 12, and on
vendor builds that revoke `CAMERA` from `com.android.shell` outright no patch helps —
the refusal happens on permissions, before any overlay trick runs. Only code running as
an installed app, holding its own user-granted `CAMERA` permission, can open the camera
there. This is that app.

Verified on an OPPO CPH1931 (ColorOS, Android 10, unrooted, **screen off and locked**).

```
Camera2 ──▶ MediaCodec H.264 ──▶ Annex-B over localabstract socket
                                          │  adb forward
                                          ▼
                              ffmpeg ──▶ /dev/video0
```

## Use

```bash
sudo modprobe v4l2loopback exclusive_caps=1 card_label="Phone Cam"
./build.sh --install     # javac + d8 + aapt2 + apksigner; -g grants CAMERA at install
./run.sh                 # streams until Ctrl+C
./run.sh --check         # streams 3s, reads one frame back out of the sink, exits
```

`PORT`, `SINK` and `ANDROID_SERIAL` are honoured as environment variables, and
`WIDTH`, `HEIGHT`, `FPS` and `BITRATE` select the stream quality (see below).

Needs `adb`, `ffmpeg`, a v4l2loopback sink, a JDK, and an Android SDK with build-tools
and a platform (`ANDROID_HOME`, else `~/Android/Sdk`). No Gradle: the app is one class
with no resources and no dependencies, so the SDK tools are driven directly.

If the ROM refuses `install -g`, grant the permission by hand once — Settings ▸ Apps ▸
Android Cam ▸ Permissions ▸ Camera — or try
`adb shell pm grant com.officialdad.androidcam android.permission.CAMERA`.

## Design notes

**There is no Activity.** The host starts the service with `am start-foreground-service`,
which never wakes or unlocks the phone. That matters: waking a locked device starts face
unlock, which opens a camera of its own and pushes the device past the system-wide
open-camera limit, so our own open then gets rejected. Being a foreground service is also
what gets past the Android 9+ ban on camera access from the background — measured, with
the screen off and the keyguard up, frames keep flowing.

**Abstract socket, not TCP.** `adb forward tcp:27183 localabstract:android-cam` reaches a
`LocalServerSocket`, the same shape scrcpy uses. No `INTERNET` permission, no port to
collide with, nothing listening on a network interface.

**One client at a time.** The camera is opened when a client connects and released when it
disconnects, then the service loops back to `accept()`.

**Config rides the start intent, not the socket.** The host picks size/fps/bitrate with
intent extras — `am start-foreground-service -n …/.CamService --ei width 1920 --ei height
1080 --ei fps 30 --ei bitrate 8000000` — and the service clamps them to what the camera
reports (nearest supported size, nearest AE fps range) before starting the encoder. The
socket stays pure H.264 because its client is ffmpeg reading the stream raw: ffmpeg can
neither send a config frame nor tolerate a reply frame ahead of the Annex-B. Missing
extras fall back to 1280x720@30 at 8 Mbps, so an old host keeps working; re-running `am`
against a live service re-configures it at the next connect.

`min-sdk 21`, `target-sdk 32` — under 33 to avoid the `POST_NOTIFICATIONS` runtime prompt,
under 34 to avoid foreground-service-type enforcement.

## Host integration contract

For anything driving this APK (android-cam-tui's `prepPhone()`, run.sh, or hand-rolled
adb): the full config channel is four integer extras on the start intent.

| extra     | type  | default   | clamped to                                              |
|-----------|-------|-----------|---------------------------------------------------------|
| `width`   | `int` | 1280      | nearest supported size by area, together with `height`  |
| `height`  | `int` | 720       | (from `StreamConfigurationMap.getOutputSizes(MediaCodec.class)`) |
| `fps`     | `int` | 30        | AE range with matching upper bound, else camera auto    |
| `bitrate` | `int` | 8 000 000 | not clamped — passed to the encoder as-is               |

```bash
adb shell am start-foreground-service -n com.officialdad.androidcam/.CamService \
    --ei width 1920 --ei height 1080 --ei fps 30 --ei bitrate 8000000
```

Rules the host can rely on:

- **Clamp, never fail.** An unsupported size lands on the nearest one the camera offers;
  an unsupported fps leaves the camera on auto AE. Zero/negative/absent extras keep the
  previous (or default) values. There is no reply channel, so the host cannot learn the
  clamped result — `adb logcat -s android-cam` prints the `streaming WxH@fps` line if it
  matters.
- **Config applies at the next client connect**, not mid-stream. To re-configure, re-run
  `am` (idempotent on a live service), then reconnect the stream client.
- **Both compat directions hold.** Old host + new APK: no extras, defaults. New host +
  old APK: extras ignored, 1280x720@30 stream anyway — nothing to detect or tolerate.
- The socket carries H.264 Annex-B only, from the first byte. Nothing else will ever be
  written to it, so pointing ffmpeg straight at it stays correct.

Remaining wiring in android-cam-tui: `prepPhone()` appends the `--ei` extras from its
config; `COMPANION_CAM` in `companion.ts` grows real size/fps options; the bundled
`src/android-cam.apk` gets rebuilt from this repo (its sha256 pin in `companion.ts`
updates with it).

## Deferred

Camera selection is hardcoded to the back camera. Latency has not been measured against
scrcpy's camera path yet. The `android-cam-tui` backend that consumes this lives in that
repo.
