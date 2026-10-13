package com.aether.sandbox.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestProfileTest {
    @Test public void generatedIdsCanBePersistedAndValidated() {
        String first = TestProfile.newAndroidId(), second = TestProfile.newAndroidId();
        assertEquals(first, TestProfile.requireAndroidId(first));
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> TestProfile.requireAndroidId("../../data"));
    }
}
