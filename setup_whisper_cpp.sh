#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# setup_whisper_cpp.sh
#
# Downloads (or updates) the whisper.cpp source code required for building
# the native JNI library used by WhisperCppRecognizer.
#
# Run this ONCE before your first build with the whisper.cpp engine.
# The source is cloned to  <project_root>/whisper-cpp-src/.
#
# Usage:
#   chmod +x setup_whisper_cpp.sh
#   ./setup_whisper_cpp.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHISPER_CPP_DIR="${SCRIPT_DIR}/whisper-cpp-src"
WHISPER_CPP_REPO="https://github.com/ggml-org/whisper.cpp.git"
# Pin to a known-good release tag for reproducible builds.
# Update this when you want to upgrade whisper.cpp.
WHISPER_CPP_TAG="v1.7.3"

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Talk to Whisper — whisper.cpp Native Source Setup              ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

if [ -d "${WHISPER_CPP_DIR}/.git" ]; then
    echo "→ whisper.cpp source already exists at ${WHISPER_CPP_DIR}"
    echo "  Checking out tag ${WHISPER_CPP_TAG}…"
    cd "${WHISPER_CPP_DIR}"
    git fetch --tags --quiet
    git checkout "${WHISPER_CPP_TAG}" --quiet
    echo "  ✓ Updated to ${WHISPER_CPP_TAG}"
else
    echo "→ Cloning whisper.cpp (${WHISPER_CPP_TAG}) into ${WHISPER_CPP_DIR}…"
    git clone --depth 1 --branch "${WHISPER_CPP_TAG}" "${WHISPER_CPP_REPO}" "${WHISPER_CPP_DIR}"
    echo "  ✓ Clone complete"
fi

echo ""
echo "Done! The whisper.cpp source is ready at:"
echo "  ${WHISPER_CPP_DIR}"
echo ""
echo "You can now build the Talk to Whisper app in Android Studio."
echo "The CMake build will automatically use this source directory."
