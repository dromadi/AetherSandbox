package com.aether.sample;

import android.app.Activity;
import android.hardware.camera2.*;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Ordinary Camera2 preview + H.264 encoder. No Aether dependency or special camera API. */
public final class SampleCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private MediaCodec encoder;
    private Surface preview, input;
    private TextView status;
    private Thread drain;
    private volatile boolean running;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);
        status = new TextView(this); status.setText("Camera2: menyiapkan"); layout.addView(status);
        var texture = new TextureView(this); texture.setSurfaceTextureListener(this);
        layout.addView(texture, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        cameraThread = new HandlerThread("sample-camera2"); cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }
    private void failed(Throwable error) {
        runOnUiThread(() -> { if (!destroyed) status.setText("Camera2 gagal: " + error); });
        try { Files.write(new File(getFilesDir(), "camera2-error.txt").toPath(), error.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { }
    }
    @SuppressWarnings("MissingPermission")
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        texture.setDefaultBufferSize(160, 120);
        preview = new Surface(texture);
        cameraHandler.post(() -> {
            try {
                if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("Guest has no camera permission");
                if (checkPermission(android.Manifest.permission.CAMERA, android.os.Process.myPid(), android.os.Process.myUid()) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("Guest camera context permission failed");
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", 160, 120);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 200000);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder = MediaCodec.createEncoderByType("video/avc");
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                input = encoder.createInputSurface(); encoder.start();
                CameraManager manager = getSystemService(CameraManager.class);
                String id = manager.getCameraIdList()[0];
                manager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        if (destroyed) { device.close(); return; }
                        camera = device;
                        try {
                            device.createCaptureSession(Arrays.asList(preview, input), new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession session) {
                                    if (destroyed) { session.close(); return; }
                                    try {
                                        CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                        request.addTarget(preview); request.addTarget(input);
                                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                                        startDrain();
                                    } catch (Throwable error) { failed(error); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession session) { failed(new IllegalStateException("Session rejected")); }
                            }, cameraHandler);
                        } catch (Throwable error) { failed(error); }
                    }
                    @Override public void onDisconnected(CameraDevice device) { device.close(); }
                    @Override public void onError(CameraDevice device, int error) { failed(new IllegalStateException("Camera error " + error)); device.close(); }
                }, cameraHandler);
            } catch (Throwable error) { failed(error); }
        });
    }
    private void startDrain() {
        running = true;
        drain = new Thread(() -> {
            var info = new MediaCodec.BufferInfo();
            int frames = 0; long bytes = 0; long previousTimestamp = -1;
            try {
                while (running) {
                    int index = encoder.dequeueOutputBuffer(info, 10000);
                    if (index < 0) continue;
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (info.presentationTimeUs <= previousTimestamp)
                            throw new IllegalStateException("Encoder timestamps must remain monotonic across video loops");
                        previousTimestamp = info.presentationTimeUs;
                        frames++; bytes += info.size;
                        Files.write(new File(getFilesDir(), "camera2-encoded.txt").toPath(),
                            (frames + ":" + bytes).getBytes(StandardCharsets.UTF_8));
                        final int count = frames;
                        runOnUiThread(() -> { if (!destroyed) status.setText("Camera2: " + count + " frame encoded"); });
                    }
                    encoder.releaseOutputBuffer(index, false);
                }
            } catch (Throwable error) { if (running) failed(error); }
        }, "sample-camera2-encoder"); drain.start();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (camera != null) camera.close();
        running = false;
        if (drain != null) try { drain.join(2000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) {} encoder.release(); }
        if (preview != null) preview.release();
        if (input != null) input.release();
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
}
