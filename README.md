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

`PORT`, `SINK` and `ANDROID_SERIAL` are honoured as environment variables.

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

`min-sdk 21`, `target-sdk 32` — under 33 to avoid the `POST_NOTIFICATIONS` runtime prompt,
under 34 to avoid foreground-service-type enforcement.

## Deferred

Camera, resolution and fps are hardcoded to the back camera at 1280x720@30; there is no
control channel. Latency has not been measured against scrcpy's camera path yet. The
`android-cam-tui` backend that consumes this lives in that repo.
