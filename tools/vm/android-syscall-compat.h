/* Linux-musl executable compatibility with Android's modern-syscall application ABI.
 * No filter is changed: legacy x86_64 filesystem calls use the corresponding *at API,
 * and unknown denied calls remain fatal with an async-signal-safe diagnostic.
 */
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>

static void aether_denied(int number) {
    char message[80] = "AETHER_SIGSYS unsupported host syscall=";
    unsigned int length = sizeof("AETHER_SIGSYS unsupported host syscall=") - 1;
    char digits[16]; unsigned int count = 0, value = (unsigned int)number;
    do { digits[count++] = (char)('0' + value % 10); value /= 10; } while (value && count < sizeof(digits));
    while (count) message[length++] = digits[--count];
    message[length++] = '\n';
    (void)write(2, message, length);
    _exit(128 + SIGSYS);
}

static void aether_sigsys(int signal, siginfo_t *info, void *opaque) {
    (void)signal;
#if defined(__x86_64__)
    ucontext_t *context = (ucontext_t *)opaque;
    greg_t *g = context->uc_mcontext.gregs;
    long a = g[REG_RDI], b = g[REG_RSI], c = g[REG_RDX], d = g[REG_R10];
    int saved_errno = errno;
    long result;
    switch (info->si_syscall) {
    case SYS_open: result = syscall(SYS_openat, AT_FDCWD, a, b, c); break;
    case SYS_stat: result = syscall(SYS_newfstatat, AT_FDCWD, a, b, 0); break;
    case SYS_lstat: result = syscall(SYS_newfstatat, AT_FDCWD, a, b, AT_SYMLINK_NOFOLLOW); break;
    case SYS_fstat: result = syscall(SYS_newfstatat, a, "", b, AT_EMPTY_PATH); break;
    case SYS_readlink: result = syscall(SYS_readlinkat, AT_FDCWD, a, b, c); break;
    case SYS_access: result = syscall(SYS_faccessat, AT_FDCWD, a, b); break;
    case SYS_pipe: result = syscall(SYS_pipe2, a, 0); break;
    case SYS_dup2:
        result = a == b ? (syscall(SYS_fcntl, a, F_GETFD) < 0 ? -1 : a) : syscall(SYS_dup3, a, b, 0);
        break;
    case SYS_getrlimit: result = syscall(SYS_prlimit64, 0, a, 0, b); break;
    case SYS_setrlimit: result = syscall(SYS_prlimit64, 0, a, b, 0); break;
    case SYS_mkdir: result = syscall(SYS_mkdirat, AT_FDCWD, a, b); break;
    case SYS_unlink: result = syscall(SYS_unlinkat, AT_FDCWD, a, 0); break;
    case SYS_rmdir: result = syscall(SYS_unlinkat, AT_FDCWD, a, AT_REMOVEDIR); break;
    case SYS_rename: result = syscall(SYS_renameat, AT_FDCWD, a, AT_FDCWD, b); break;
    case SYS_link: result = syscall(SYS_linkat, AT_FDCWD, a, AT_FDCWD, b, 0); break;
    case SYS_symlink: result = syscall(SYS_symlinkat, a, AT_FDCWD, b); break;
    case SYS_chmod: result = syscall(SYS_fchmodat, AT_FDCWD, a, b); break;
    case SYS_chown: result = syscall(SYS_fchownat, AT_FDCWD, a, b, c, 0); break;
    case SYS_lchown: result = syscall(SYS_fchownat, AT_FDCWD, a, b, c, AT_SYMLINK_NOFOLLOW); break;
    case SYS_epoll_create:
        if (a <= 0) { errno = EINVAL; result = -1; } else result = syscall(SYS_epoll_create1, 0);
        break;
    case SYS_epoll_wait: result = syscall(SYS_epoll_pwait, a, b, c, d, 0, 8); break;
    case SYS_eventfd: result = syscall(SYS_eventfd2, a, 0); break;
    case SYS_inotify_init: result = syscall(SYS_inotify_init1, 0); break;
    case SYS_signalfd: result = syscall(SYS_signalfd4, a, b, c, 0); break;
    case SYS_getpgrp: result = syscall(SYS_getpgid, 0); break;
    case SYS_poll: {
        struct timespec timeout = { .tv_sec = (int)c / 1000, .tv_nsec = ((int)c % 1000) * 1000000L };
        result = syscall(SYS_ppoll, a, b, (int)c < 0 ? 0 : &timeout, 0, 8);
        break;
    }
    case SYS_time: {
        struct timespec now;
        result = syscall(SYS_clock_gettime, CLOCK_REALTIME, &now);
        if (result == 0) { if (a) *(long *)a = now.tv_sec; result = now.tv_sec; }
        break;
    }
    default: aether_denied(info->si_syscall);
    }
    g[REG_RAX] = result == -1 ? -errno : result;
    errno = saved_errno;
#else
    (void)opaque;
    aether_denied(info->si_syscall);
#endif
}

__attribute__((constructor(101))) static void aether_android_abi_init(void) {
    struct sigaction action = {0};
    action.sa_sigaction = aether_sigsys;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSYS, &action, 0);
}
