#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/generated/realAppAssets
curl --fail --location --retry 3 --max-time 120 \
  https://github.com/SimpleMobileTools/Simple-Calculator/releases/download/5.12.0/calculator-fdroid-release.apk \
  -o app/build/generated/realAppAssets/real-calculator.apk
echo '092629deaeaaea824dbf0cd824989e80b498b1fa07ad5b4c3e744fc64ce5ab2f  app/build/generated/realAppAssets/real-calculator.apk' | sha256sum -c -
