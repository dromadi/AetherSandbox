package com.aether.sandbox.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuestApkInstallCommandTest {
    @Test public void generatedCommandHasSafeMarkersAndOnlyPrivateFixedPath() {
        String script = GuestApkInstallCommand.create("ABC12345");
        assertTrue(script.contains("AETHER_APK_ABC12345_OK"));
        assertTrue(script.contains("AETHER_APK_ABC12345_FAIL"));
        assertTrue(script.contains("mount -t vfat -o ro"));
        assertTrue(script.contains("pm install -r /data/local/tmp/aether-transfer.apk"));
        assertFalse(script.contains("hostfwd"));
    }
    @Test public void rejectsInjectedOrEmptyMarkers() {
        for (String unsafe : new String[] {"", "bad;echo hi", "bad_token", "a", "a\n12345678"}) {
            assertThrows(IllegalArgumentException.class, () -> GuestApkInstallCommand.create(unsafe));
        }
        assertThrows(IllegalArgumentException.class, () -> GuestApkInstallCommand.create(null));
    }
}
