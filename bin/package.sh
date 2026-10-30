#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
python3 scripts/assemble.py --check
mvn -B -ntp verify
mkdir -p dist
for module in oversight warehouse clustermgr;do cp "$module/target/$module-0.1.0.jar" dist/;done
(cd dist && sha256sum ./*.jar > SHA256SUMS)
