#!/usr/bin/env bash
# =============================================================================
# n1neslim "peppermint" — Automated Packager / Bootstrapper
# Target : Amlogic S905W (board p281 / X96 Mini), Android TV 9 block-OTA zip
# Host   : Linux or WSL2 (Debian/Ubuntu recommended)
#
# What this script does:
#   1. Checks & installs the host tools you need (brotli, zip, unzip, openjdk).
#   2. Compiles the pure-Java builder (tools/n1neslim-peppermint/src) into a
#      single runnable jar -> bin/n1neslim.jar  (this is the "app").
#   3. Generates device_config.ini + AOSP test keys if they are missing.
#   4. Converts system.img -> system.new.dat (+ transfer.list), brotli-compresses
#      it to system.new.dat.br, assembles the META-INF tree, and writes
#      n1neslim_v1_ota.zip.
#   5. Signs the package with testkey.pk8 / testkey.x509.pem (signapk if present,
#      otherwise the built-in fallback signer baked into the jar).
#
# Usage:
#   ./build_n1neslim.sh                 # full build using ./system.img + ./boot.img
#   ./build_n1neslim.sh skeleton        # structure-only zip (no images needed yet)
#   ./build_n1neslim.sh menu            # interactive beginner menu (the "app")
#   ./build_n1neslim.sh install-tools   # only apt-get the dependencies
#   SYSTEM_IMG=/path/boot.img BOOT_IMG=/path/boot.img ./build_n1neslim.sh
# =============================================================================
set -euo pipefail

# ---- paths ------------------------------------------------------------------
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/src"
BIN="$HERE/bin"
BUILD="$HERE/build"
CLASSES="$BUILD/classes"
JAR="$BIN/n1neslim.jar"
OUT_DIR="${OUT_DIR:-$PWD}"
SYSTEM_IMG="${SYSTEM_IMG:-./system.img}"
BOOT_IMG="${BOOT_IMG:-./boot.img}"
CONFIG="${CONFIG:-device_config.ini}"
PKG_NAME="${PKG_NAME:-n1neslim_v1_ota.zip}"
KEYS_DIR="${KEYS_DIR:-keys}"

log() { printf '\033[1;36m[n1neslim]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---- dependency handling ----------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

install_tools() {
  log "Installing host tools (apt)..."
  export DEBIAN_FRONTEND=noninteractive
  sudo apt-get update -y
  sudo apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless brotli zip unzip git ca-certificates openssl
  log "Tools installed."
}

check_tools() {
  local missing=()
  have java   || missing+=("java(JDK)")
  have javac  || missing+=("javac(JDK)")
  # brotli/zip/unzip are NICE-TO-HAVE only: the jar has built-in Java fallbacks
  # (gzip payload + pure-Java zip writer), so we never hard-fail without them.
  have brotli || log "note: 'brotli' CLI not found -> payload will use gzip fallback (system.new.dat.gz)."
  have zip    || log "note: 'zip' CLI not found -> using the jar's built-in zip writer."
  have unzip  || log "note: 'unzip' CLI not found -> not needed; verify uses the built-in reader."
  if [ "${#missing[@]}" -gt 0 ]; then
    log "Missing required tools: ${missing[*]}"
    if have apt-get; then
      read -r -p "Install them now via apt? [Y/n] " ans || ans=Y
      case "${ans:-Y}" in [Yy]*) install_tools ;; *) die "Please install: ${missing[*]}" ;; esac
    else
      die "Install manually: ${missing[*]}"
    fi
  fi
}

# ---- compile the app --------------------------------------------------------
compile() {
  log "Compiling n1neslim-peppermint app -> $JAR"
  rm -rf "$CLASSES"; mkdir -p "$CLASSES" "$BIN"
  find "$SRC" -name '*.java' > "$BUILD/sources.txt"
  javac -encoding UTF-8 -d "$CLASSES" @"$BUILD/sources.txt"
  cat > "$BUILD/manifest.txt" <<EOF
Main-Class: n1neslim.Main
Implementation-Title: n1neslim-peppermint
Implementation-Version: 1.0.0
EOF
  jar cfm "$JAR" "$BUILD/manifest.txt" -C "$CLASSES" .
  chmod +x "$JAR" 2>/dev/null || true
  log "Built app jar: $JAR"
}

run_app() { java -jar "$JAR" "$@"; }

# ---- config + test keys -----------------------------------------------------
ensure_config() {
  if [ ! -f "$CONFIG" ]; then
    log "Writing default $CONFIG for p281/S905W"
    cat > "$CONFIG" <<'EOF'
# n1neslim peppermint device configuration (Amlogic S905W / p281 / X96 Mini)
ota_name=n1neslim_peppermint
codename=peppermint
device=p281
soc=amlogic-s905w
android_version=9.0-tv
# Partition block devices used by the recovery updater-script.
partition_by_name=/dev/block/by-name
system_partition=/dev/block/by-name/system
boot_partition=/dev/block/by-name/boot
cache_partition=/dev/block/by-name/cache
recovery_partition=/dev/block/by-name/recovery
# Payload naming / compression
system_dat=system.new.dat
brotli=true
EOF
  fi
}

ensure_test_keys() {
  mkdir -p "$KEYS_DIR"
  if [ ! -f "$KEYS_DIR/testkey.pk8" ] || [ ! -f "$KEYS_DIR/testkey.x509.pem" ]; then
    log "Generating AOSP-equivalent test keys in $KEYS_DIR/"
    if have openssl; then
      openssl genrsa -traditional -out "$KEYS_DIR/testkey_private.pem" 2048 2>/dev/null \
        || openssl genrsa -out "$KEYS_DIR/testkey_private.pem" 2048
      openssl rsa -in "$KEYS_DIR/testkey_private.pem" -outform DER -out "$KEYS_DIR/testkey.pk8" 2>/dev/null
      openssl req -x509 -sha256 -nodes -days 10000 -key "$KEYS_DIR/testkey_private.pem" \
        -subj "/CN=n1neslim-test" -out "$KEYS_DIR/testkey.x509.pem" 2>/dev/null
      log "testkey.pk8 + testkey.x509.pem created."
    else
      log "openssl not found; the jar will use an ephemeral built-in key instead."
    fi
  fi
}

# ---- main flows -------------------------------------------------------------
do_skeleton() {
  ensure_config
  run_app skeleton --out "$OUT_DIR/$PKG_NAME"
  log "Skeleton package ready: $OUT_DIR/$PKG_NAME"
}

do_full_build() {
  check_tools
  compile
  ensure_config
  ensure_test_keys
  [ -e "$SYSTEM_IMG" ] || die "No system image at '$SYSTEM_IMG'. Put system.img there or set SYSTEM_IMG=/path/to/img"
  local boot_arg=()
  [ -f "$BOOT_IMG" ] && boot_arg=(--boot "$BOOT_IMG") || log "WARNING: no boot.img at '$BOOT_IMG' (package will skip boot flash)."
  log "Building OTA package..."
  run_app build --config "$CONFIG" --system "$SYSTEM_IMG" "${boot_arg[@]}" \
                --pk8 "$KEYS_DIR/testkey.pk8" --pem "$KEYS_DIR/testkey.x509.pem" \
                --out "$OUT_DIR/$PKG_NAME"
  log "Verifying..."
  run_app verify --zip "$OUT_DIR/$PKG_NAME"
  log "SUCCESS -> $OUT_DIR/$PKG_NAME"
  ls -lh "$OUT_DIR/$PKG_NAME"
}

case "${1:-build}" in
  install-tools) install_tools ;;
  compile)       check_tools; compile ;;
  skeleton)      compile; do_skeleton ;;
  menu)          compile; run_app menu ;;
  build|"")      do_full_build ;;
  help|*)        sed -n '2,26p' "$0" ;;
esac
