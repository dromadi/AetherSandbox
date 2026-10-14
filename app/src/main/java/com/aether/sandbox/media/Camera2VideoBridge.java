package com.aether.sandbox.media;

import android.content.Context;
import android.hardware.camera2.*;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executor;
import kotlin.Unit;

/** Process-local, opt-in Camera2 Surface prototype. Never opens a physical camera. */
public final class Camera2VideoBridge implements AutoCloseable {
    private final Context host;
    private final Uri video;
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final List<VideoDevice> devices = new ArrayList<>();
    private volatile boolean closed;
    private Camera2VideoBridge(Context host, Uri video) { this.host = host; this.video = video; }

    public static Camera2VideoBridge install(Context host, Uri video) {
        Camera2VideoBridge bridge = new Camera2VideoBridge(host, video);
        try {
            // Validate the selected file before intercepting a guest's camera request.
            try (android.content.res.AssetFileDescriptor file = host.getContentResolver().openAssetFileDescriptor(video, "r")) {
                if (file == null) throw new IllegalArgumentException("Video tidak dapat dibuka");
            }
            if (Build.VERSION.SDK_INT >= 28) {
                if (!HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/camera2/impl/CameraMetadataNative;",
                    "Landroid/hardware/camera2/CaptureRequest$Builder;"))
                    throw new IllegalStateException("Metadata kamera internal tidak tersedia");
            }
            // Probe request creation at install time so unsupported OS versions fail explicitly.
            requestBuilder("0");
            for (Method method : CameraManager.class.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!method.getName().equals("openCamera") || types.length != 3 || types[0] != String.class) continue;
                final boolean handlerApi = types[1] == CameraDevice.StateCallback.class && types[2] == Handler.class;
                final boolean executorApi = types[1] == Executor.class && types[2] == CameraDevice.StateCallback.class;
                if (!handlerApi && !executorApi) continue;
                bridge.hooks.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (bridge.closed) throw new IllegalStateException("Kamera video telah ditutup");
                            CameraDevice.StateCallback callback = (CameraDevice.StateCallback) param.args[handlerApi ? 1 : 2];
                            if (callback == null) throw new IllegalArgumentException("Callback kamera diperlukan");
                            Executor executor = handlerApi ? executor((Handler) param.args[2]) : (Executor) param.args[1];
                            if (executor == null) throw new IllegalArgumentException("Executor kamera diperlukan");
                            String id = (String) param.args[0];
                            // Preserve standard invalid-camera-id behaviour without opening camera hardware.
                            ((CameraManager) param.thisObject).getCameraCharacteristics(id);
                            VideoDevice device = bridge.new VideoDevice(id, callback, executor);
                            synchronized (bridge.devices) { bridge.devices.add(device); }
                            param.setResult(null);
                            executor.execute(() -> { if (!device.closed) callback.onOpened(device); });
                        } catch (Throwable failure) {
                            // Xposed logs thrown callback errors and falls back to the original call.
                            // Set throwable explicitly: video mode must never fall back to the physical camera.
                            param.setThrowable(failure);
                        }
                    }
                }));
            }
            if (bridge.hooks.isEmpty()) throw new IllegalStateException("Camera2 openCamera tidak dapat dihubungkan");
            return bridge;
        } catch (Throwable failure) {
            bridge.close();
            throw new IllegalStateException("Kamera video Camera2 tidak tersedia pada perangkat ini", failure);
        }
    }

    private static Executor executor(Handler handler) {
        Handler target = handler;
        if (target == null) {
            if (Looper.myLooper() == null) throw new IllegalArgumentException("Handler atau Looper diperlukan");
            target = new Handler(Looper.myLooper());
        }
        final Handler queue = target;
        return command -> { if (!queue.post(command)) throw new IllegalStateException("Thread callback telah berhenti"); };
    }

    private static CaptureRequest.Builder requestBuilder(String id) throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        Object nativeMetadata = metadata.getDeclaredConstructor().newInstance();
        for (Constructor<?> constructor : CaptureRequest.Builder.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length < 3 || types[0] != metadata) continue;
            Object[] args = new Object[types.length]; args[0] = nativeMetadata;
            boolean supported = true;
            for (int i = 1; i < types.length; i++) {
                if (types[i] == boolean.class) args[i] = false;
                else if (types[i] == int.class) args[i] = -1;
                else if (types[i] == String.class) args[i] = id;
                else if (Set.class.isAssignableFrom(types[i])) args[i] = Collections.emptySet();
                else supported = false;
            }
            if (!supported) continue;
            constructor.setAccessible(true);
            CaptureRequest.Builder builder = (CaptureRequest.Builder) constructor.newInstance(args);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            return builder;
        }
        throw new IllegalStateException("CaptureRequest.Builder tidak kompatibel");
    }

    @Override public void close() {
        closed = true;
        synchronized (devices) { for (VideoDevice device : new ArrayList<>(devices)) device.close(); devices.clear(); }
        for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
        hooks.clear();
    }

    private final class VideoDevice extends CameraDevice {
        final String id;
        final StateCallback callback;
        final Executor callbacks;
        volatile boolean closed;
        VideoSession session;
        VideoDevice(String id, StateCallback callback, Executor executor) { this.id = id; this.callback = callback; callbacks = executor; }
        private void checkOpen() { if (closed) throw new IllegalStateException("CameraDevice telah ditutup"); }
        @Override public String getId() { return id; }
        @Override public CaptureRequest.Builder createCaptureRequest(int template) {
            checkOpen();
            if (template != TEMPLATE_PREVIEW && template != TEMPLATE_RECORD)
                throw new UnsupportedOperationException("Kamera video hanya mendukung preview/record");
            try { return requestBuilder(id); } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public void createCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback cb, Handler handler) {
            configure(outputs, cb, executor(handler));
        }
        @Override public void createCaptureSessionByOutputConfigurations(List<OutputConfiguration> outputs, CameraCaptureSession.StateCallback cb, Handler handler) {
            List<Surface> surfaces = new ArrayList<>(); for (OutputConfiguration output : outputs) surfaces.add(output.getSurface());
            configure(surfaces, cb, executor(handler));
        }
        @android.annotation.TargetApi(28)
        @Override public void createCaptureSession(SessionConfiguration config) {
            if (config.getSessionType() != SessionConfiguration.SESSION_REGULAR || config.getInputConfiguration() != null)
                throw new UnsupportedOperationException("High-speed/reprocess belum didukung");
            List<Surface> surfaces = new ArrayList<>();
            for (OutputConfiguration output : config.getOutputConfigurations()) surfaces.addAll(output.getSurfaces());
            configure(surfaces, config.getStateCallback(), config.getExecutor());
        }
        private void configure(List<Surface> outputs, CameraCaptureSession.StateCallback cb, Executor executor) {
            checkOpen();
            if (outputs.isEmpty() || outputs.size() > 2 || outputs.stream().anyMatch(s -> s == null || !s.isValid()))
                throw new IllegalArgumentException("Gunakan satu atau dua Surface preview/encoder yang valid");
            if (session != null) session.close();
            session = new VideoSession(this, outputs, cb, executor);
            VideoSession configured = session;
            executor.execute(() -> { if (!configured.closed && !closed) cb.onConfigured(configured); });
        }
        @Override public void close() {
            if (closed) return; closed = true;
            if (session != null) session.close();
            callbacks.execute(() -> callback.onClosed(this));
        }
        @Override public CaptureRequest.Builder createReprocessCaptureRequest(TotalCaptureResult result) { throw unsupported(); }
        @Override public void createReprocessableCaptureSession(InputConfiguration input, List<Surface> outputs, CameraCaptureSession.StateCallback cb, Handler handler) { throw unsupported(); }
        @Override public void createReprocessableCaptureSessionByConfigurations(InputConfiguration input, List<OutputConfiguration> outputs, CameraCaptureSession.StateCallback cb, Handler handler) { throw unsupported(); }
        @Override public void createConstrainedHighSpeedCaptureSession(List<Surface> outputs, CameraCaptureSession.StateCallback cb, Handler handler) { throw unsupported(); }
    }

    private final class VideoSession extends CameraCaptureSession {
        final VideoDevice device;
        final Set<Surface> outputs;
        final StateCallback callback;
        final Executor callbacks;
        final List<SyntheticMediaPipeline> pipelines = new ArrayList<>();
        volatile boolean closed;
        int sequence;
        VideoSession(VideoDevice device, List<Surface> outputs, StateCallback callback, Executor callbacks) {
            this.device = device; this.outputs = new HashSet<>(outputs); this.callback = callback; this.callbacks = callbacks;
        }
        @Override public CameraDevice getDevice() { return device; }
        @Override public boolean isReprocessable() { return false; }
        @Override public Surface getInputSurface() { return null; }
        @Override public int setRepeatingRequest(CaptureRequest request, CaptureCallback listener, Handler handler) {
            if (closed || device.closed) throw new IllegalStateException("CaptureSession telah ditutup");
            if (listener != null) throw new UnsupportedOperationException("Capture metadata callback belum didukung");
            if (request.getTargets().isEmpty() || !outputs.containsAll(request.getTargets()))
                throw new IllegalArgumentException("Target request tidak terdaftar");
            stopRepeating();
            for (Surface target : request.getTargets()) {
                pipelines.add(new SyntheticMediaPipeline(host, video, target, message -> {
                    if (message.startsWith("Video gagal")) {
                        android.util.Log.e("AetherCamera2", message);
                        if (!closed) callbacks.execute(() -> device.callback.onError(device, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE));
                    }
                    return Unit.INSTANCE;
                }, false));
            }
            callbacks.execute(() -> { if (!closed) callback.onActive(this); });
            return ++sequence;
        }
        @Override public void stopRepeating() { for (SyntheticMediaPipeline pipeline : pipelines) pipeline.close(); pipelines.clear(); }
        @Override public void abortCaptures() { stopRepeating(); }
        @Override public void close() {
            if (closed) return; closed = true; stopRepeating();
            callbacks.execute(() -> callback.onClosed(this));
        }
        @Override public void prepare(Surface surface) { if (!outputs.contains(surface)) throw new IllegalArgumentException("Surface tidak terdaftar"); }
        @Override public void finalizeOutputConfigurations(List<OutputConfiguration> configs) { throw unsupported(); }
        @Override public int capture(CaptureRequest request, CaptureCallback cb, Handler handler) { throw unsupported(); }
        @Override public int captureBurst(List<CaptureRequest> requests, CaptureCallback cb, Handler handler) { throw unsupported(); }
        @Override public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback cb, Handler handler) { throw unsupported(); }
    }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Fitur Camera2 ini belum didukung kamera video"); }
}
