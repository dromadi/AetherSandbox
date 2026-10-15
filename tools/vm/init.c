// Test-only initramfs PID 1. No network, shell, host shares, or Android framework.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/reboot.h>
#include <sys/utsname.h>
#include <unistd.h>

int main(void) {
    struct utsname host;
    const char *nonce = getenv("aether_nonce");
    setvbuf(stdout, NULL, _IONBF, 0);
    if (uname(&host) != 0 || strcmp(host.machine, "aarch64") != 0 || nonce == NULL) {
        puts("AETHER_VM_INIT_FAILED");
    } else {
        printf("AETHER_VM_LINUX_READY nonce=%s kernel=%s guest=%s pid=%d\n",
               nonce, host.release, host.machine, getpid());
    }
    sync();
    puts("AETHER_VM_POWEROFF");
    reboot(RB_POWER_OFF);
    for (;;) pause();
}
