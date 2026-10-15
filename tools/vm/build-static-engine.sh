#!/bin/sh
# Run inside Alpine on the target CPU. Produces ELF executables, not JNI libraries.
set -eu
OUT=/out
apk add --no-cache build-base python3 py3-pip meson ninja git pkgconf \
    glib-dev glib-static pcre2-dev pcre2-static libffi-dev \
    pixman-dev pixman-static zlib-dev zlib-static linux-headers
mkdir -p /work "$OUT/firmware" "$OUT/source"
cd /work
git init qemu
cd qemu
git remote add origin https://github.com/qemu/qemu.git
git fetch --depth=1 origin 4fc49f46dc95d4a27de2509e7fceb2931e91faeb
git checkout --detach FETCH_HEAD
test "$(git rev-parse HEAD)" = 4fc49f46dc95d4a27de2509e7fceb2931e91faeb
export LDFLAGS='-Wl,-z,max-page-size=16384'
./configure --target-list=aarch64-softmmu,x86_64-softmmu --static \
    --without-default-features --enable-tcg --enable-vnc --enable-pixman \
    --enable-fdt=internal --disable-rust --disable-werror --disable-docs \
    --disable-tools --disable-guest-agent
ninja -C build -j2 qemu-system-aarch64 qemu-system-x86_64
for arch in aarch64 x86_64; do
    cp "build/qemu-system-$arch" "$OUT/libaether_qemu_$arch.so"
    strip "$OUT/libaether_qemu_$arch.so"
    # A native executable must not depend on a Linux ELF loader or host .so files.
    if readelf -l "$OUT/libaether_qemu_$arch.so" | grep -q INTERP; then
        echo 'Unexpected dynamic interpreter' >&2
        exit 1
    fi
    "$OUT/libaether_qemu_$arch.so" --version > "$OUT/version-$arch.txt"
done
cp pc-bios/bios-256k.bin pc-bios/vgabios-stdvga.bin pc-bios/kvmvapic.bin "$OUT/firmware/"
cp COPYING LICENSE "$OUT/" 2>/dev/null || cp COPYING "$OUT/"
git rev-parse HEAD > "$OUT/source-commit.txt"
apk info -vv > "$OUT/alpine-packages.txt"
# Keep the exact configured source and linkable objects with the engine distribution.
tar -czf "$OUT/source/qemu-configured-source-and-objects.tar.gz" --exclude='.git' .
sha256sum "$OUT"/*.so > "$OUT/sha256.txt"
