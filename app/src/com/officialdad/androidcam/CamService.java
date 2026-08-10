package com.officialdad.androidcam;

import android.app.Notification;
import android.graphics.Rect;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Camera2 -> MediaCodec H.264 -> Annex-B over an abstract unix socket.
 *
 * There is deliberately no Activity. The host starts this with
 * `am start-foreground-service`, which never wakes or unlocks the phone — waking a
 * locked device starts face unlock, which opens a camera of its own and pushes the
 * device past the system-wide open-camera limit, so our own open gets rejected.
 * Being a foreground service is also what gets us past the Android 9+ ban on camera
 * access from the background.
 */
public class CamService extends Service {
    static final String TAG = "android-cam";
    static final String SOCKET = "android-cam";
    static final String MIME = "video/avc";

    // Defaults when the start intent carries no extras, so an old host still gets a stream.
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int FPS = 30;
    static final int BITRATE = 8_000_000;
    static final int ZOOM = 100; // percent; 100 = no zoom

    // Requested by the host, applied (clamped to what the camera offers) at the next
    // client connect. Volatile: written on the main thread, read on the net thread.
    private volatile int reqWidth = WIDTH;
    private volatile int reqHeight = HEIGHT;
    private volatile int reqFps = FPS;
    private volatile int reqBitrate = BITRATE;
    private volatile int reqZoom = ZOOM;

    private volatile boolean running;
    private LocalServerSocket server;
    private HandlerThread camThread;
    private Handler camHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, notification());
        camThread = new HandlerThread("cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        running = true;
        new Thread(this::serve, "net").start();
    }

    /**
     * The host's config channel is the start intent itself:
     * `am start-foreground-service -n …/.CamService --ei width 1920 --ei height 1080
     * --ei fps 30 --ei bitrate 8000000`. The socket stays pure H.264 — the host-side
     * client is ffmpeg reading the stream raw, so it cannot speak a handshake, and a
     * reply frame would corrupt its input. `am` can be re-run on a live service to
     * change the config; it takes effect on the next client connect.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) { // null on a START_STICKY restart: keep the current config
            reqWidth = positiveOr(intent.getIntExtra("width", 0), reqWidth);
            reqHeight = positiveOr(intent.getIntExtra("height", 0), reqHeight);
            reqFps = positiveOr(intent.getIntExtra("fps", 0), reqFps);
            reqBitrate = positiveOr(intent.getIntExtra("bitrate", 0), reqBitrate);
            reqZoom = positiveOr(intent.getIntExtra("zoom", 0), reqZoom);
        }
        return START_STICKY;
    }

    private static int positiveOr(int v, int fallback) {
        return v > 0 ? v : fallback;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        // accept() only unblocks by closing the socket out from under it.
        closeQuietly(server);
        camThread.quitSafely();
        super.onDestroy();
    }

    /** One client at a time. The camera is held only while somebody is connected. */
    private void serve() {
        try {
            server = new LocalServerSocket(SOCKET);
            Log.i(TAG, "listening on localabstract:" + SOCKET);
            while (running) {
                LocalSocket sock = server.accept();
                Log.i(TAG, "client connected");
                try {
                    stream(sock);
                } catch (Exception e) {
                    Log.w(TAG, "stream ended: " + e);
                } finally {
                    closeQuietly(sock);
                }
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "socket error", e);
        } finally {
            closeQuietly(server);
            stopSelf();
        }
    }

    private void stream(LocalSocket sock) throws Exception {
        CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
        String id = backCameraId(mgr);
        CameraCharacteristics chars = mgr.getCameraCharacteristics(id);
        Size size = nearestSize(chars, reqWidth, reqHeight);
        int fps = reqFps;
        int bitrate = reqBitrate;
        int zoom = reqZoom;
        Log.i(TAG, "streaming " + size + "@" + fps + " " + bitrate + "bps zoom " + zoom + "%");

        MediaFormat fmt = MediaFormat.createVideoFormat(MIME, size.getWidth(), size.getHeight());
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        // 1s keyframes so a host that connects mid-stream syncs fast.
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        Surface surface = null;
        CameraDevice cam = null;
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            cam = openCamera(mgr, id, chars, surface, fps, zoom);
            drain(codec, sock.getOutputStream());
        } finally {
            if (cam != null) cam.close(); // also tears down its capture session
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            if (surface != null) surface.release();
        }
    }

    /** Blocks until the camera is open and repeating into {@code surface}. */
    private CameraDevice openCamera(CameraManager mgr, String id, CameraCharacteristics chars,
            Surface surface, int fps, int zoom) throws Exception {
        Log.i(TAG, "opening camera " + id);

        ArrayBlockingQueue<Object> opened = new ArrayBlockingQueue<>(1);
        mgr.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice d) {
                opened.offer(d);
            }

            @Override
            public void onDisconnected(CameraDevice d) {
                d.close();
                opened.offer(new IOException("camera disconnected"));
            }

            @Override
            public void onError(CameraDevice d, int err) {
                d.close();
                opened.offer(new IOException("camera error " + err));
            }
        }, camHandler);
        CameraDevice cam = (CameraDevice) await(opened, "camera open");

        try {
            ArrayBlockingQueue<Object> configured = new ArrayBlockingQueue<>(1);
            cam.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            configured.offer(s);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            configured.offer(new IOException("capture session failed"));
                        }
                    }, camHandler);
            CameraCaptureSession session =
                    (CameraCaptureSession) await(configured, "capture session");

            CaptureRequest.Builder req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            req.addTarget(surface);
            Range<Integer> range = fpsRange(chars, fps);
            if (range != null) req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            Rect crop = zoomCrop(chars, zoom);
            if (crop != null) req.set(CaptureRequest.SCALER_CROP_REGION, crop);
            session.setRepeatingRequest(req.build(), null, camHandler);
            return cam;
        } catch (Exception e) {
            cam.close();
            throw e;
        }
    }

    private void drain(MediaCodec codec, OutputStream out) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[64 * 1024];
        while (running) {
            int idx = codec.dequeueOutputBuffer(info, 500_000);
            // <0 is TRY_AGAIN_LATER or FORMAT_CHANGED; SPS/PPS also arrive in-band as a
            // CODEC_CONFIG buffer, so there is nothing to do for the format change.
            if (idx < 0) continue;
            try {
                if (info.size > 0) {
                    if (buf.length < info.size) buf = new byte[info.size];
                    ByteBuffer bb = codec.getOutputBuffer(idx);
                    bb.position(info.offset);
                    bb.get(buf, 0, info.size);
                    out.write(buf, 0, info.size); // encoder output is already Annex-B
                }
            } finally {
                codec.releaseOutputBuffer(idx, false);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
        out.flush();
    }

    private static Object await(ArrayBlockingQueue<Object> q, String what) throws Exception {
        Object r = q.poll(10, TimeUnit.SECONDS);
        if (r == null) throw new IOException(what + " timed out");
        if (r instanceof Exception) throw (Exception) r;
        return r;
    }

    private static String backCameraId(CameraManager mgr) throws CameraAccessException, IOException {
        String[] ids = mgr.getCameraIdList();
        if (ids.length == 0) throw new IOException("no cameras");
        for (String id : ids) {
            Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids[0];
    }

    /** Nearest supported AE range to {@code fps}, or null to leave the camera on auto. */
    private static Range<Integer> fpsRange(CameraCharacteristics chars, int fps) {
        Range<Integer>[] ranges =
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() != fps) continue;
            if (best == null || r.getLower() > best.getLower()) best = r; // prefer fixed fps-fps
        }
        return best;
    }

    /**
     * Digital zoom: centered crop of the active sensor array, {@code zoom} in percent
     * (150 = 1.5x), clamped to the camera's max digital zoom. Null at 100% or below —
     * SCALER_CROP_REGION is (0,0)-origin in active-array coordinates. CONTROL_ZOOM_RATIO
     * would be cleaner but needs API 30; the phones this app exists for are older.
     */
    private static Rect zoomCrop(CameraCharacteristics chars, int zoom) {
        if (zoom <= 100) return null;
        Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) return null;
        Float max = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        float z = Math.min(zoom / 100f, max == null ? 1f : max);
        if (z <= 1f) return null;
        int w = Math.round(active.width() / z);
        int h = Math.round(active.height() / z);
        int x = (active.width() - w) / 2;
        int y = (active.height() - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    /** The requested size if the camera offers it, else the supported size nearest by area. */
    private static Size nearestSize(CameraCharacteristics chars, int w, int h) {
        StreamConfigurationMap map =
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(MediaCodec.class);
        if (sizes == null || sizes.length == 0) return new Size(w, h); // let the camera decide
        long want = (long) w * h;
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            // ponytail: nearest by area only; weigh aspect ratio in if a host ever needs it
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - want);
            if (diff < bestDiff) {
                best = s;
                bestDiff = diff;
            }
        }
        return best;
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(TAG, "Android Cam", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, TAG);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Android Cam")
                .setContentText("Streaming camera to host")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private static void closeQuietly(Object c) {
        try {
            if (c instanceof LocalServerSocket) ((LocalServerSocket) c).close();
            else if (c instanceof LocalSocket) ((LocalSocket) c).close();
        } catch (IOException ignored) {
        }
    }
}
