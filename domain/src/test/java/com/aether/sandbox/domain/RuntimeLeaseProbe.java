package com.aether.sandbox.domain;

import java.io.File;
import java.io.IOException;

/** Separate JVM used to verify the OS lease, beyond the in-process registry. */
public final class RuntimeLeaseProbe {
    public static void main(String[] args) throws Exception {
        try (RuntimeLease lease = RuntimeLease.acquire(new File(args[0]), args[1])) {
            System.out.println("acquired");
        } catch (IOException busy) {
            System.exit(3);
        }
    }
}
