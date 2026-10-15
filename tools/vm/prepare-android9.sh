#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/android9
cd build/android9
curl --fail --location --retry 3 --connect-timeout 20 --max-time 300 \
  'https://downloads.sourceforge.net/project/android-x86/Release%209.0/android-x86_64-9.0-r2.iso' -o guest.iso
echo '1cc85b5ed7c830ff71aecf8405c7281a9c995aa0  guest.iso' | sha1sum -c -
bsdtar -xOf guest.iso kernel > kernel
bsdtar -xOf guest.iso initrd.img > initrd.img
sha256sum guest.iso > sha256.txt
