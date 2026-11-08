#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (speech recognition, voice activity, speaker turns)
# from the project's official GitHub release and refuses it unless its SHA-256 matches.
# The AAR is not committed because it is ~50 MB; CI and local builds run this first.
set -euo pipefail
VERSION="2.0.1"
SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/sherpa-onnx-${VERSION}.aar"
mkdir -p "$(dirname "$DEST")"
if [ -f "$DEST" ] && echo "${SHA256}  ${DEST}" | sha256sum -c --status; then
  echo "sherpa-onnx ${VERSION} already present"; exit 0
fi
curl -fsSL --retry 3 -o "$DEST.tmp" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
echo "${SHA256}  ${DEST}.tmp" | sha256sum -c -
mv "$DEST.tmp" "$DEST"
echo "sherpa-onnx ${VERSION} verified"
