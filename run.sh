#!/usr/bin/env bash
# Starts the phone-side service and pipes its H.264 stream into a v4l2loopback sink.
# `--check` instead grabs one frame back out of the sink and fails if nothing arrived.
set -euo pipefail

PKG=com.officialdad.androidcam
SOCKET=android-cam
PORT=${PORT:-27183}
SINK=${SINK:-/dev/video0}
ADB=(adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"})

[ -e "$SINK" ] || { echo "no $SINK — modprobe v4l2loopback first" >&2; exit 1; }

"${ADB[@]}" forward "tcp:$PORT" "localabstract:$SOCKET" >/dev/null
# WIDTH/HEIGHT/FPS/BITRATE/ZOOM/CAMERA env vars ride the start intent as extras; the
# service clamps them to what the camera supports, and absent extras keep its defaults
# (1280x720@30, no zoom, back camera). ZOOM is percent: 150 = 1.5x digital. CAMERA is a
# camera2 id — list them: adb shell dumpsys activity service $PKG/.CamService
EXTRAS=()
[ "${WIDTH:-}" ] && EXTRAS+=(--ei width "$WIDTH")
[ "${HEIGHT:-}" ] && EXTRAS+=(--ei height "$HEIGHT")
[ "${FPS:-}" ] && EXTRAS+=(--ei fps "$FPS")
[ "${BITRATE:-}" ] && EXTRAS+=(--ei bitrate "$BITRATE")
[ "${ZOOM:-}" ] && EXTRAS+=(--ei zoom "$ZOOM")
[ "${FOCUS:-}" ] && EXTRAS+=(--ef focus "$FOCUS") # diopters: 0 = infinity, 2 = 50cm; -1 = autofocus
[ "${CAMERA:-}" ] && EXTRAS+=(--es camera "$CAMERA")
[ "${TORCH:-}" ] && EXTRAS+=(--ei torch "$TORCH")   # 1|0, toggles mid-stream too
# Never `am start` an activity here: waking a locked phone starts face unlock, which
# opens a camera of its own and pushes the device past the open-camera limit.
"${ADB[@]}" shell am start-foreground-service -n "$PKG/.CamService" \
    ${EXTRAS[@]+"${EXTRAS[@]}"} >/dev/null

# The service binds its socket a beat after the process starts; wait for it rather than
# racing ffmpeg against it. /proc/net/unix lists abstract sockets with a leading @.
for _ in $(seq 30); do
    "${ADB[@]}" shell "grep -q @$SOCKET /proc/net/unix" 2>/dev/null && ready=1 && break
    sleep 0.2
done
[ "${ready:-}" ] || {
    echo "service never listened — check: adb logcat -d -s android-cam" >&2
    echo "most likely CAMERA is not granted: adb shell pm grant $PKG android.permission.CAMERA" >&2
    exit 1
}

# Annex-B carries no timestamps, so ffmpeg guesses a 1200k tbr and then drops ~98% of
# the frames converting down to it. `-r` on the input plus passthrough keeps them all.
FFMPEG=(ffmpeg -hide_banner -loglevel warning
    -fflags nobuffer -flags low_delay -r "${FPS:-30}"
    -f h264 -i "tcp://127.0.0.1:$PORT" -fps_mode passthrough)

if [ "${1:-}" != "--check" ]; then
    exec "${FFMPEG[@]}" -vf format=yuv420p -f v4l2 "$SINK"
fi

frame=$(mktemp --suffix=.jpg)
trap 'rm -f "$frame"' EXIT
"${FFMPEG[@]}" -vf format=yuv420p -f v4l2 "$SINK" & pump=$!
trap 'kill $pump 2>/dev/null; rm -f "$frame"' EXIT
sleep 3
ffmpeg -hide_banner -loglevel error -y -f v4l2 -i "$SINK" -frames:v 1 "$frame"
[ -s "$frame" ] || { echo "FAIL: no frame read back from $SINK" >&2; exit 1; }
echo "OK: $(ffprobe -v error -show_entries stream=width,height -of csv=p=0:s=x "$frame") frame from $SINK"
