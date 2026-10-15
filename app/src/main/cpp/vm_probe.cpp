#include <jni.h>
#include <cerrno>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <unistd.h>

// Run only in :vm_probe. A vendor denying executable mappings must not crash the host UI.
extern "C" JNIEXPORT jstring JNICALL
Java_com_aether_sandbox_core_NativeDiagnostics_vmProbe(JNIEnv* env, jobject) {
    const long page = sysconf(_SC_PAGESIZE);
    int jit_result = -1;
    int jit_errno = 0;
    void* memory = mmap(nullptr, static_cast<size_t>(page), PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (memory == MAP_FAILED) {
        jit_errno = errno;
    } else {
#if defined(__aarch64__)
        // BTI c; mov w0,#42; ret. No page is writable and executable simultaneously.
        const uint32_t code[] = {0xd503245f, 0x52800540, 0xd65f03c0};
#elif defined(__arm__)
        const uint16_t code[] = {0x202a, 0x4770}; // Thumb: movs r0,#42; bx lr.
#elif defined(__x86_64__)
        const unsigned char code[] = {0xf3,0x0f,0x1e,0xfa,0xb8,42,0,0,0,0xc3};
#else
        const unsigned char code[] = {0};
#endif
        memcpy(memory, code, sizeof(code));
        __builtin___clear_cache(static_cast<char*>(memory), static_cast<char*>(memory) + sizeof(code));
        if (mprotect(memory, static_cast<size_t>(page), PROT_READ | PROT_EXEC) == 0) {
#if defined(__aarch64__) || defined(__arm__) || defined(__x86_64__)
            uintptr_t address = reinterpret_cast<uintptr_t>(memory);
#if defined(__arm__)
            address |= 1;
#endif
            jit_result = reinterpret_cast<int (*)()>(address)();
#else
            jit_errno = ENOTSUP;
#endif
        } else {
            jit_errno = errno;
        }
        munmap(memory, static_cast<size_t>(page));
    }
    // Opening KVM and reading the API version does NOT prove permission to create a VM.
    const int fd = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    const int open_errno = fd < 0 ? errno : 0;
    const int kvm_api = fd < 0 ? -1 : ioctl(fd, _IO(0xAE, 0), 0);
    const int ioctl_errno = fd >= 0 && kvm_api < 0 ? errno : 0;
    if (fd >= 0) close(fd);
    char result[512];
    snprintf(result, sizeof(result),
        "{\"jitReturn\":%d,\"jitErrno\":%d,\"kvmApi\":%d,\"kvmOpenErrno\":%d,"
        "\"kvmIoctlErrno\":%d,\"pageSize\":%ld,\"seccompMode\":%d}",
        jit_result, jit_errno, kvm_api, open_errno, ioctl_errno, page, prctl(PR_GET_SECCOMP));
    return env->NewStringUTF(result);
}
