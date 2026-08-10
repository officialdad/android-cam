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
`WIDTH`, `HEIGHT`, `FPS`, `BITRATE` and `ZOOM` select the stream quality (see below).

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
| `zoom`    | `int` | 100       | percent, 150 = 1.5x digital; clamped to `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`, ≤100 = off |
| `camera`  | `string` | back camera | any id the device reports (see probe below); unknown id falls back to back, `""` resets |
| `torch`   | `int` | 0         | `1` on / `0` off; **applies mid-stream** on the live capture session, absent keeps state |

```bash
adb shell am start-foreground-service -n com.officialdad.androidcam/.CamService \
    --ei width 1920 --ei height 1080 --ei fps 30 --ei bitrate 8000000 \
    --ei zoom 150 --es camera 1
```

**Probe.** With the service running, Android's dump channel answers with one line of JSON
describing every camera — the scrcpy `--list-camera-sizes` equivalent:

```bash
adb shell dumpsys activity service com.officialdad.androidcam/.CamService
# … {"cameras":[{"id":"0","facing":"back","orientation":90,"maxZoom":4.0,
#   "sizes":["4000x3000","1920x1080","1280x720"],"fpsRanges":[[15,15],[15,30],[30,30]]}],
#   "requested":{"camera":null,"width":1280,"height":720,"fps":30,"bitrate":8000000,
#     "zoom":100,"torch":false},
#   "applied":{"camera":"0","width":1280,"height":720,"fps":30,"fpsAuto":false,
#     "zoom":100,"torch":false},
#   "connected":false} …
```

ActivityManager wraps it in headers/indentation: parse the line containing `"cameras"`
from its first `{` to its last `}`. `sizes` are encoder-capable output sizes, descending
by area; `orientation` is `SENSOR_ORIENTATION` degrees (pick the host-side `transpose`
from it); `maxZoom` bounds the zoom UI. `applied` is what the clamps produce for the
current request — read `applied.fps` back for ffmpeg's `-r` instead of assuming
(`fpsAuto: true` means no AE range matched and the fps is an encoder hint, not a
promise). Reading characteristics needs no CAMERA grant, so the probe works even when
streaming would not. An old APK prints no JSON line — hosts fall back to assuming
1280x720@30 on the back camera.

Rules the host can rely on:

- **Clamp, never fail.** An unsupported size lands on the nearest one the camera offers;
  an unsupported fps leaves the camera on auto AE; an unknown camera id falls back to the
  back camera. Zero/negative/absent extras keep the previous (or default) values. The
  stream carries no reply, so the host cannot learn the clamped result from it — probe
  first to ask for what exists, or check the `streaming WxH@fps` line in
  `adb logcat -s android-cam`.
- **Config applies at the next client connect**, not mid-stream — except `torch`, which
  re-issues the live repeating request (a reconnect for a fill light would blank the
  video). To re-configure anything else, re-run `am` (idempotent on a live service), then
  reconnect the stream client.
- **Both compat directions hold.** Old host + new APK: no extras, defaults. New host +
  old APK: extras ignored, 1280x720@30 stream anyway — nothing to detect or tolerate.
- The socket carries H.264 Annex-B only, from the first byte. Nothing else will ever be
  written to it, so pointing ffmpeg straight at it stays correct.

Remaining wiring in android-cam-tui: `prepPhone()` appends the `--ei` extras from its
config; `COMPANION_CAM` in `companion.ts` grows real size/fps options; the bundled
`src/android-cam.apk` gets rebuilt from this repo (its sha256 pin in `companion.ts`
updates with it).

## Deferred

Latency has not been measured against scrcpy's camera path yet. High-speed capture modes
and codec selection are out of scope for the hardware this targets. The `android-cam-tui`
backend that consumes this lives in that repo.
