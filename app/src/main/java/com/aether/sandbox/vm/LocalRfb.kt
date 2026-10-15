package com.aether.sandbox.vm

import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.*

/** RFB 3.8, raw truecolor rectangles, private Unix socket. No TCP listener or web server. */
class LocalRfb(private val file: File) : AutoCloseable {
    private val socket = LocalSocket()
    private lateinit var output: DataOutputStream
    @Volatile private var closed = false
    suspend fun frames(consumer: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        socket.connect(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.soTimeout = 15_000
        val input = DataInputStream(socket.inputStream)
        output = DataOutputStream(socket.outputStream)
        val version = ByteArray(12); input.readFully(version)
        require(String(version, Charsets.US_ASCII) == "RFB 003.008\n") { "Versi display tidak didukung" }
        output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); output.flush()
        val count = input.readUnsignedByte(); require(count in 1..32)
        val security = ByteArray(count); input.readFully(security)
        require(security.contains(1.toByte())) { "Display privat tidak menawarkan protokol lokal" }
        output.writeByte(1); output.flush(); require(input.readInt() == 0)
        output.writeByte(1); output.flush()
        var width = input.readUnsignedShort(); var height = input.readUnsignedShort()
        fun valid(w: Int, h: Int) { require(w in 1..2048 && h in 1..2048 && w.toLong() * h <= 2048L * 2048) { "Display terlalu besar" } }
        valid(width, height)
        input.readFully(ByteArray(16))
        val nameLength = input.readInt(); require(nameLength in 0..4096); input.readFully(ByteArray(nameLength))
        synchronized(this@LocalRfb) {
            // SetPixelFormat: 32 bpp, little endian, truecolor, RGB shifts 16/8/0.
            output.write(byteArrayOf(0,0,0,0,32,24,0,1,0,(-1).toByte(),0,(-1).toByte(),0,(-1).toByte(),16,8,0,0,0,0))
            output.writeByte(2); output.writeByte(0); output.writeShort(2); output.writeInt(0); output.writeInt(-223)
            output.flush()
        }
        var pixels = IntArray(width * height)
        socket.soTimeout = 0 // An idle desktop may legitimately produce no incremental update.
        fun request(incremental: Boolean) = synchronized(this@LocalRfb) {
            output.writeByte(3); output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0); output.writeShort(0); output.writeShort(width); output.writeShort(height); output.flush()
        }
        request(false)
        while (currentCoroutineContext().isActive && !closed) {
            when (input.readUnsignedByte()) {
                0 -> {
                    input.readUnsignedByte(); val rectangles = input.readUnsignedShort(); require(rectangles <= 4096)
                    repeat(rectangles) {
                        val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
                        when (val encoding = input.readInt()) {
                            -223 -> { valid(w, h); width = w; height = h; pixels = IntArray(w * h) }
                            0 -> {
                                require(x + w <= width && y + h <= height)
                                val bytes = ByteArray(w * h * 4); input.readFully(bytes)
                                var at = 0
                                for (row in y until y + h) for (col in x until x + w) {
                                    pixels[row * width + col] = (0xff shl 24) or (bytes[at].toInt() and 255) or
                                        ((bytes[at + 1].toInt() and 255) shl 8) or ((bytes[at + 2].toInt() and 255) shl 16)
                                    at += 4
                                }
                            }
                            else -> error("Encoding display tidak didukung: $encoding")
                        }
                    }
                    consumer(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
                    delay(100); request(true)
                }
                2 -> Unit // Bell.
                3 -> { input.readFully(ByteArray(3)); val length = input.readInt(); require(length in 0..65536); input.readFully(ByteArray(length)) }
                else -> error("Pesan display tidak didukung")
            }
        }
    }
    @Synchronized fun pointer(x: Int, y: Int, down: Boolean) {
        if (!::output.isInitialized || closed) return
        output.writeByte(5); output.writeByte(if (down) 1 else 0); output.writeShort(x); output.writeShort(y); output.flush()
    }
    @Synchronized fun key(keysym: Int, down: Boolean) {
        if (!::output.isInitialized || closed) return
        output.writeByte(4); output.writeByte(if (down) 1 else 0); output.writeShort(0); output.writeInt(keysym); output.flush()
    }
    override fun close() { closed = true; runCatching { socket.close() } }
}
