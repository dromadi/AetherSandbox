#!/bin/sh
set -eu
apk add --no-cache abuild git ca-certificates
git clone --depth=1 --branch 3.22-stable https://github.com/alpinelinux/aports.git /tmp/aports
mkdir -p /out/recipes /out/distfiles
git -C /tmp/aports rev-parse HEAD > /out/aports-commit.txt
export SRCDEST=/out/distfiles
for name in glib gettext pcre2 pixman zlib libffi musl; do
    cp -R "/tmp/aports/main/$name" /out/recipes/
    cd "/out/recipes/$name"
    abuild -F fetch
    abuild -F verify
done
