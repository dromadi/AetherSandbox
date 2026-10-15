#define _GNU_SOURCE
#include "android-syscall-compat.h"
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
#if defined(__x86_64__)
    int unknown = argc > 1 && strcmp(argv[1], "unknown") == 0;
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, unknown ? SYS_getpid : SYS_open, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_readlink, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getrlimit, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program = { sizeof(rules)/sizeof(rules[0]), rules };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)) return 2;
    if (unknown) { syscall(SYS_getpid); return 3; }
    int fd = (int)syscall(SYS_open, "/dev/null", O_RDONLY, 0);
    if (fd < 0) return 4;
    close(fd);
    char target[4096];
    if (syscall(SYS_readlink, "/proc/self/exe", target, sizeof(target)) <= 0) return 5;
    struct rlimit limit;
    if (syscall(SYS_getrlimit, RLIMIT_NOFILE, &limit) || limit.rlim_cur == 0) return 6;
    puts("AETHER_ABI_TRANSLATION_PASS");
#else
    (void)argc; (void)argv;
#endif
    return 0;
}
