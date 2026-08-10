# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A companion APK that streams a phone camera to a Linux host as raw H.264, for old/vendor
phones that `scrcpy --video-source=camera` cannot serve (scrcpy needs Android 12+, and some
ROMs revoke CAMERA from the shell uid outright). One Java class, no Activity, no Gradle.

Pipeline: `Camera2 → MediaCodec H.264 → Annex-B over localabstract:android-cam socket
→ adb forward → ffmpeg → v4l2loopback sink`.

## Commands

```bash
./build.sh              # javac + d8 + aapt2 + apksigner directly; needs $ANDROID_HOME (or ~/Android/Sdk)
./build.sh --install    # + adb install -r -g
./run.sh                # forward, start service, pipe into $SINK (default /dev/video0)
./run.sh --check        # streams 3s, reads one frame back out of the sink — the repo's only test
adb logcat -s android-cam   # service-side diagnostics, prints the clamped "streaming WxH@fps" line
adb shell dumpsys activity service com.officialdad.androidcam/.CamService
                            # camera probe: one JSON line (ids/facing/orientation/zoom/sizes/fps)
```

`run.sh` honours `PORT`, `SINK`, `ANDROID_SERIAL`, and the stream config `WIDTH`, `HEIGHT`,
`FPS`, `BITRATE`, `ZOOM` as environment variables.

**Compile check without an Android SDK** (none may be installed on this machine): download a
standalone `android.jar` (e.g. `https://github.com/Sable/android-platforms/raw/master/android-30/android.jar`)
and run `javac -nowarn -classpath android.jar -d <out> app/src/.../CamService.java`. Use
`-classpath`, not `-bootclasspath` — android.jar lacks a working `LambdaMetafactory`, the
JDK supplies it. A "deprecated API" note is expected (the old `createCaptureSession`
overload; the replacement needs API 28, min-sdk is 21).

## Architecture and invariants

Everything lives in `app/src/com/officialdad/androidcam/CamService.java`. The invariants
below are load-bearing; the README's Design notes explain each at length.

- **Never start an Activity or wake the phone.** The host uses `am start-foreground-service`
  precisely because waking a locked phone triggers face unlock, which opens its own camera
  and pushes the device past the system-wide open-camera limit, killing our open. This is
  the reason the app has no UI at all.
- **The socket carries H.264 Annex-B only, from the first byte.** The host-side client is
  ffmpeg reading the stream raw (`-f h264 -i tcp://…`), so nothing else may ever be written
  to it — no handshake, no reply frames. That is why stream config rides the **start intent**
  (`--ei width/height/fps/bitrate/zoom`, `--es camera` extras) and the probe answers via
  **`Service.dump()`** (`dumpsys activity service`), the only reply channel. Contract for
  hosts: README ▸ "Host integration contract"; probe JSON shape also pinned in
  android-cam-tui#5.
- **Clamp, never fail.** Requested size snaps to the nearest the camera reports
  (`nearestSize`, area-only for now), fps to a matching AE range else auto (`fpsRange`),
  zoom to max digital zoom (`zoomCrop`, centered `SCALER_CROP_REGION` — crop-before-encode
  so no bitrate is spent on discarded pixels). Missing/zero extras keep previous or default
  values (1280x720@30, 8 Mbps, no zoom), which is the whole backward-compat story.
- **One client at a time; camera held only while connected.** Config changes apply at the
  next client connect, not mid-stream.
- **Rotation is deliberately not phone-side.** Raw Annex-B has no rotation metadata and
  Camera2 can't rotate into the encoder surface; hosts rotate with ffmpeg `-vf transpose`.
- API ceilings are deliberate: `min-sdk 21`, `target-sdk 32` (<33 avoids POST_NOTIFICATIONS
  prompt, <34 avoids FGS-type enforcement), and prefer API-21 camera APIs
  (`SCALER_CROP_REGION` over `CONTROL_ZOOM_RATIO`) — the phones this app exists for are old.

## Cross-repo coupling

`../android-cam-tui` (github.com/officialdad/android-cam-tui) consumes this as its
"companion" backend: its `src/companion.ts` bundles a prebuilt `src/android-cam.apk` with a
pinned sha256, and its `prepPhone()` mirrors `run.sh`'s forward/start/wait sequence. Any
change to the intent-extras contract, socket name, package name, or `.CamService` class
name breaks that repo; after protocol changes the bundled APK there must be rebuilt from
this repo and its sha256 pin updated. Reference hardware: OPPO CPH1931, ColorOS,
Android 10, unrooted, works screen-off and locked.
