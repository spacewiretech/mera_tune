#!/usr/bin/env bash
# Builds the mixer image and runs the golden test inside it.
# Usage: bash services/ringtone-mixer/test/golden.sh
#   MIXER_IMAGE=<tag>  override the local image tag (default meratune/ringtone-mixer:golden)
set -euo pipefail

SERVICE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${MIXER_IMAGE:-meratune/ringtone-mixer:golden}"

echo "==> docker build ${IMAGE} (${SERVICE_DIR})"
docker build -t "${IMAGE}" "${SERVICE_DIR}"

echo "==> running golden test in ${IMAGE}"
docker run --rm "${IMAGE}" node dist/test/golden.js
