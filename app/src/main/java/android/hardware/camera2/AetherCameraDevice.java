package android.hardware.camera2;

/**
 * SDK-only constructor bridge: SDK stubs hide CameraDevice's public runtime constructor.
 * This is an Aether class, not a replacement for the platform CameraDevice.
 */
public abstract class AetherCameraDevice extends CameraDevice {
    protected AetherCameraDevice() { super(); }
}
