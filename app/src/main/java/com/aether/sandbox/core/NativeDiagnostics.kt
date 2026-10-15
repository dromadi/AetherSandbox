package com.aether.sandbox.core

object NativeDiagnostics {
    init { System.loadLibrary("aether_core") }
    external fun abi(): String
    external fun vmProbe(): String
}
