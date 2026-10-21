package com.aether.sandbox.vm

import android.content.Context
import com.aether.sandbox.domain.Iso9660
import com.aether.sandbox.domain.VmInitrd
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

object PackagedVmEngine {
    fun executable(context: Context, guest: String): File = File(context.applicationInfo.nativeLibraryDir, "libaether_qemu_$guest.so")
    fun available(context: Context) = executable(context, "x86_64").canExecute() && executable(context, "aarch64").canExecute()
    fun directory(context: Context) = File(context.filesDir, "vm-android9").apply { mkdirs() }
    fun prepareFirmware(context: Context): File = File(context.filesDir, "vm-firmware").apply {
        mkdirs()
        for (name in listOf("bios-256k.bin", "vgabios-stdvga.bin", "kvmvapic.bin", "linuxboot_dma.bin", "keymaps/en-us")) {
            val target = File(this, name).also { it.parentFile?.mkdirs() }
            context.assets.open("vm-firmware/$name").use { source -> target.outputStream().use { source.copyTo(it) } }
        }
    }
    suspend fun importIso(context: Context, uri: android.net.Uri) = withContext(Dispatchers.IO) {
        val root = directory(context)
        val pending = File(context.filesDir, "vm-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val iso = File(pending, "guest.iso")
            val digest = MessageDigest.getInstance("SHA-1")
            val limit = 1536L * 1024 * 1024
            require(context.filesDir.usableSpace > limit + 256L * 1024 * 1024) { "Sediakan minimal 1,75 GiB ruang kosong untuk impor" }
            requireNotNull(context.contentResolver.openInputStream(uri)).use { source ->
                iso.outputStream().use { output ->
                    val buffer = ByteArray(65536); var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer); if (read < 0) break
                        count += read; require(count <= limit) { "ISO melebihi 1,5 GiB" }
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val sha1 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sha1 == "1cc85b5ed7c830ff71aecf8405c7281a9c995aa0") { "Gunakan ISO resmi android-x86_64-9.0-r2.iso; checksum tidak cocok" }
            Iso9660.extractBootFiles(iso, pending)
            // A single fixed guest profile; replacement is allowed only while the caller has stopped it.
            val backup = File(context.filesDir, "vm-old-${UUID.randomUUID()}")
            check(root.renameTo(backup)) { "Profil lama tidak dapat disiapkan" }
            if (!pending.renameTo(root)) {
                check(backup.renameTo(root)) { "Impor gagal; profil lama tersimpan di ${backup.name}" }
                error("Impor gagal dipasang; profil lama dipulihkan")
            }
            backup.deleteRecursively()
        } finally { pending.deleteRecursively() }
    }
    fun androidArguments(context: Context, socket: File, coreGuest: Boolean = true): List<String> {
        val root = directory(context)
        require(listOf("guest.iso", "kernel", "initrd.img").all { File(root, it).isFile }) { "Impor ISO Android-x86 9.0-r2 terlebih dahulu" }
        require(socket.path.length < 100) { "Jalur socket terlalu panjang" }
        return listOf(executable(context, "x86_64").path, "-L", prepareFirmware(context).path,
            "-machine", "pc-i440fx-10.0", "-accel", "tcg,thread=multi", "-cpu", "qemu64,+ssse3,+sse4.1,+sse4.2,+popcnt", "-smp", "2",
            "-m", "2048M", "-no-user-config", "-nic", "none", "-display", "none", "-vga", "std",
            // Supply OS-backed entropy even before user input or disk activity exists.
            "-object", "rng-builtin,id=entropy0", "-device", "virtio-rng-pci,rng=entropy0",
            "-vnc", "unix:${socket.path}", "-monitor", "none", "-serial", "stdio", "-no-reboot",
            "-usb", "-device", "usb-tablet", "-kernel", File(root, "kernel").path,
            "-initrd", VmInitrd.prepare(root, coreGuest).path,
            "-drive", "file=${File(root, "guest.iso").path},format=raw,media=cdrom,readonly=on",
            "-append", "root=/dev/ram0 androidboot.hardware=android_x86_64 SRC=/ DATA= console=tty0 console=ttyS0,115200 androidboot.console=ttyS0 nomodeset xforcevesa vga=835 HWACCEL=0" +
                if (coreGuest) " SETUPWIZARD=0" else "")
    }
    suspend fun linuxBoot(context: Context): String = withContext(Dispatchers.IO) {
        require(available(context)) { "APK ini belum membawa engine untuk ABI perangkat" }
        val image = File(context.cacheDir, "vm-linux-Image")
        context.assets.open("vm-linux-Image").use { input -> image.outputStream().use { input.copyTo(it) } }
        val nonce = UUID.randomUUID().toString()
        val process = ProcessBuilder(executable(context, "aarch64").path,
            "-machine", "virt-8.2,gic-version=3", "-accel", "tcg,thread=single", "-cpu", "cortex-a53",
            "-m", "256M", "-smp", "1", "-nodefaults", "-display", "none", "-serial", "stdio",
            "-monitor", "none", "-nic", "none", "-no-reboot", "-kernel", image.path,
            "-append", "console=ttyAMA0 rdinit=/init panic=-1 aether_nonce=$nonce").redirectErrorStream(true).start()
        val reader = async { process.inputStream.bufferedReader().use { stream ->
            val log = StringBuilder(); val buffer = CharArray(4096)
            while (true) { val count = stream.read(buffer); if (count < 0) break; if (log.length < 256 * 1024) log.append(buffer, 0, count.coerceAtMost(256 * 1024 - log.length)) }
            log.toString()
        } }
        try {
            withTimeout(90_000) {
                while (process.isAlive) delay(50)
                val log = reader.await()
                check(process.exitValue() == 0 && log.contains("AETHER_VM_LINUX_READY nonce=$nonce ") &&
                    log.contains("guest=aarch64 pid=1") && log.contains("AETHER_VM_POWEROFF") && !log.contains("Kernel panic")) { "Boot gagal (${process.exitValue()}): ${log.takeLast(6000)}" }
                log
            }
        } finally { if (process.isAlive) process.destroyForcibly(); reader.cancel(); image.delete() }
    }
}
