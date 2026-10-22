package com.aether.sandbox.domain;

/** Fixed guest-serial script. No input from an APK filename enters the shell. */
public final class GuestApkInstallCommand {
    public static String create(String nonce) {
        if (nonce == null || !nonce.matches("[A-Za-z0-9]{8,64}"))
            throw new IllegalArgumentException("Invalid marker");
        String prefix = "AETHER_APK_" + nonce + "_";
        return "printf '\\n" + prefix + "BEGIN\\n'; " +
            "mkdir -p /mnt/aether-bridge; found=0; " +
            "for dev in /dev/block/sda /dev/block/sdb /dev/block/sdc /dev/block/hda /dev/block/hdb /dev/block/vda /dev/block/vdb; do " +
            "[ -b \"$dev\" ] || continue; " +
            "mount -t vfat -o ro \"$dev\" /mnt/aether-bridge 2>/dev/null || continue; " +
            "if [ -f /mnt/aether-bridge/transfer.apk ]; then found=1; break; fi; " +
            "umount /mnt/aether-bridge; done; " +
            "if [ \"$found\" = 1 ] && " +
            "cp /mnt/aether-bridge/transfer.apk /data/local/tmp/aether-transfer.apk && " +
            "pm install -r /data/local/tmp/aether-transfer.apk; then " +
            "printf '" + prefix + "OK\\n'; else printf '" + prefix + "FAIL\\n'; fi; " +
            "rm -f /data/local/tmp/aether-transfer.apk; " +
            "umount /mnt/aether-bridge 2>/dev/null; " +
            "printf '" + prefix + "END\\n'\n";
    }
    private GuestApkInstallCommand() {}
}
