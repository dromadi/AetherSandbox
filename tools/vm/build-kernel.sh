#!/usr/bin/env bash
# Minimal CI-only kernel. This image is not a supported production Android guest.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/vm-lab"
COMMIT=d390303b28dabbb91b2d32016a4f72da478733b9
mkdir -p "$OUT"
if [ ! -d "$OUT/linux/.git" ]; then
    git init "$OUT/linux"
    git -C "$OUT/linux" remote add origin https://github.com/gregkh/linux.git
fi
git -C "$OUT/linux" fetch --depth=1 origin "$COMMIT"
git -C "$OUT/linux" checkout --detach "$COMMIT"
test "$(git -C "$OUT/linux" rev-parse HEAD)" = "$COMMIT"
aarch64-linux-gnu-gcc -static -Os -Wall -Wextra -Werror "$ROOT/tools/vm/init.c" -o "$OUT/init"
cat > "$OUT/initramfs.list" <<EOF
dir /dev 0755 0 0
nod /dev/console 0600 0 0 c 5 1
file /init $OUT/init 0755 0 0
EOF
export ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu-
export KBUILD_BUILD_TIMESTAMP='2024-11-22 00:00:00 +0000'
export KBUILD_BUILD_USER=aether KBUILD_BUILD_HOST=ci
make -C "$OUT/linux" tinyconfig
"$OUT/linux/scripts/config" --file "$OUT/linux/.config" \
    -e PRINTK -e TTY -e SERIAL_AMBA_PL011 -e SERIAL_AMBA_PL011_CONSOLE \
    -e BINFMT_ELF -e BLK_DEV_INITRD -e FUTEX -e MULTIUSER \
    -e DEVTMPFS -e ARM_PSCI_FW -e POWER_RESET -e POWER_RESET_SYSCON \
    -e RD_GZIP --set-str INITRAMFS_SOURCE "$OUT/initramfs.list" \
    --set-str LOCALVERSION '-aether-ci' -d LOCALVERSION_AUTO
make -C "$OUT/linux" olddefconfig
make -C "$OUT/linux" -j2 Image
cp "$OUT/linux/arch/arm64/boot/Image" "$OUT/Image"
cp "$OUT/linux/.config" "$OUT/kernel.config"
git -C "$OUT/linux" rev-parse HEAD > "$OUT/kernel-source-commit.txt"
