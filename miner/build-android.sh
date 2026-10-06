#!/usr/bin/env bash
# =============================================================================
#  Compile ccminer (minage de Verus) pour Android avec le NDK.
#  Lancé automatiquement par GitHub Actions. Utilisable aussi sur un PC Linux :
#     ANDROID_NDK_HOME=/chemin/du/ndk ABI=arm64-v8a bash miner/build-android.sh
#
#  Résultat dans miner/out/ :
#     <abi>-libccminer.so         version optimisée (instructions crypto ARM)
#     <abi>-libccminer_compat.so  version de secours (processeurs plus anciens)
# =============================================================================
set -euo pipefail

ABI="${ABI:?Indique ABI=arm64-v8a ou ABI=armeabi-v7a}"
NDK="${ANDROID_NDK_HOME:?Indique ANDROID_NDK_HOME (dossier du NDK)}"
VARIANTS="${VARIANTS:-optimized compat}"
API=26

CCMINER_REPO="${CCMINER_REPO:-https://github.com/Oink70/CCminer-ARM-optimized.git}"
CCMINER_REF="${CCMINER_REF:-main}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
CURL_VERSION="${CURL_VERSION:-8.9.1}"
JANSSON_VERSION="${JANSSON_VERSION:-2.14}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/miner/work/$ABI"
OUT="$ROOT/miner/out"
LOGS="$ROOT/miner/logs"
PREFIX="$WORK/prefix"
JOBS="$(nproc)"
mkdir -p "$WORK/src" "$PREFIX/lib" "$PREFIX/include/sys" "$OUT" "$LOGS"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

# --- Réglages propres à chaque famille de processeurs -------------------------
case "$ABI" in
  arm64-v8a)
    HOST="aarch64-linux-android"
    CLANG_TARGET="aarch64-linux-android"
    OSSL_TARGET="android-arm64"
    ARCH_OPT="-march=armv8-a+crypto -mtune=cortex-a53"
    ARCH_COMPAT="-march=armv8-a"
    ;;
  armeabi-v7a)
    HOST="arm-linux-androideabi"
    CLANG_TARGET="armv7a-linux-androideabi"
    OSSL_TARGET="android-arm"
    ARCH_OPT="-march=armv8-a+crypto -mfpu=crypto-neon-fp-armv8 -mfloat-abi=softfp"
    ARCH_COMPAT="-march=armv7-a -mfpu=neon -mfloat-abi=softfp"
    ;;
  *)
    echo "ABI inconnue : $ABI" >&2
    exit 1
    ;;
esac

export CC="$TOOLCHAIN/bin/${CLANG_TARGET}${API}-clang"
export CXX="$TOOLCHAIN/bin/${CLANG_TARGET}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"

COMMON_FLAGS="-O3 -ffast-math -funroll-loops -finline-functions -fomit-frame-pointer -fno-stack-protector -D_REENTRANT -fpic -pthread"

fetch() {  # fetch <fichier-de-sortie> <url> [url de secours...]
  local out="$1"; shift
  local url
  for url in "$@"; do
    if curl -fsSL --retry 3 -o "$out" "$url"; then return 0; fi
    echo "Échec du téléchargement : $url" >&2
  done
  return 1
}

# --- Mode « une variante » : appelé par la boucle plus bas ---------------------
build_variant() {
  local variant="$1" arch_flags out_name
  case "$variant" in
    optimized) arch_flags="$ARCH_OPT";    out_name="$ABI-libccminer.so" ;;
    compat)    arch_flags="$ARCH_COMPAT"; out_name="$ABI-libccminer_compat.so" ;;
    *) echo "Variante inconnue : $variant" >&2; exit 1 ;;
  esac

  local dir="$WORK/build-$variant"
  rm -rf "$dir"
  cp -r "$WORK/ccminer-src" "$dir"
  cd "$dir"

  # Le dépôt contient des fichiers déjà générés (dont un ancien programme) : on les enlève
  rm -rf ccminer autom4te.cache config.status config.log Makefile Makefile.in \
         configure aclocal.m4 ccminer-config.h stamp-h1
  find . -name '*.o' -delete
  find . -name '.deps' -type d -prune -exec rm -rf {} +

  chmod +x autogen.sh
  ./autogen.sh

  # Fichiers config.sub / config.guess récents (reconnaissent Android)
  local f src
  for f in config.sub config.guess; do
    src="$(ls /usr/share/automake-*/"$f" 2>/dev/null | tail -n1 || true)"
    if [ -n "$src" ]; then cp "$src" "$f"; fi
  done

  local flags="$COMMON_FLAGS $arch_flags -I$PREFIX/include"
  ./configure --host="$HOST" --with-libcurl="$PREFIX" \
    CC="$CC" CXX="$CXX" \
    CPPFLAGS="-I$PREFIX/include" \
    CFLAGS="$flags" CXXFLAGS="$flags" \
    LDFLAGS="-L$PREFIX/lib -static-libstdc++" \
    LIBS="-lssl -lcrypto -ldl -lm" \
    PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

  make -j"$JOBS"
  test -f ccminer

  "$STRIP" --strip-unneeded ccminer
  validate ccminer
  cp ccminer "$OUT/$out_name"
  echo ">>> OK : $OUT/$out_name"
}

# Contrôles : le programme ne doit dépendre que de bibliothèques présentes sur tout Android
validate() {
  local f="$1" needed bad
  needed="$("$READELF" -d "$f" | grep NEEDED || true)"
  echo "Dépendances du programme :"; echo "$needed"
  bad="$(echo "$needed" | grep -Ev '\[lib(c|m|dl|log|z)\.so\]' | grep -v '^$' || true)"
  if [ -n "$bad" ]; then
    echo "ERREUR : dépendances absentes d'Android :" >&2
    echo "$bad" >&2
    return 1
  fi
  if grep -aq "com.termux" "$f"; then
    echo "ERREUR : le programme référence Termux" >&2
    return 1
  fi
  "$READELF" -h "$f" | grep -E 'Class|Machine'
}

if [ "${1:-all}" = "variant" ]; then
  build_variant "${2:?variante manquante}"
  exit 0
fi

# --- Bibliothèques nécessaires (compilées une seule fois par processeur) -------
build_deps() {
  # Quelques programmes cherchent -lpthread, qui n'existe pas sous Android : on crée une bibliothèque vide
  "$AR" rcs "$PREFIX/lib/libpthread.a"
  # <sys/sysctl.h> n'existe pas sous Android (même astuce que Termux)
  echo '#include <linux/sysctl.h>' > "$PREFIX/include/sys/sysctl.h"

  cd "$WORK/src"

  echo "=== OpenSSL $OPENSSL_VERSION ==="
  fetch openssl.tar.gz \
    "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://www.openssl.org/source/old/3.0/openssl-${OPENSSL_VERSION}.tar.gz"
  rm -rf openssl-src && mkdir openssl-src && tar xf openssl.tar.gz -C openssl-src --strip-components=1
  (
    cd openssl-src
    unset CC CXX AR RANLIB STRIP
    export ANDROID_NDK_ROOT="$NDK"
    export PATH="$TOOLCHAIN/bin:$PATH"
    ./Configure "$OSSL_TARGET" -D__ANDROID_API__="$API" no-shared no-tests no-ui-console no-comp \
      --prefix="$PREFIX" --openssldir="$PREFIX/ssl" --libdir=lib
    make -j"$JOBS" build_libs
    make install_dev
  )

  echo "=== jansson $JANSSON_VERSION ==="
  fetch jansson.tar.bz2 \
    "https://github.com/akheron/jansson/releases/download/v${JANSSON_VERSION}/jansson-${JANSSON_VERSION}.tar.bz2"
  rm -rf jansson-src && mkdir jansson-src && tar xf jansson.tar.bz2 -C jansson-src --strip-components=1
  (
    cd jansson-src
    ./configure --host="$HOST" --prefix="$PREFIX" --enable-static --disable-shared \
      CFLAGS="-O2 -fPIC"
    make -j"$JOBS"
    make install
  )

  echo "=== curl $CURL_VERSION ==="
  fetch curl.tar.gz "https://curl.se/download/curl-${CURL_VERSION}.tar.gz"
  rm -rf curl-src && mkdir curl-src && tar xf curl.tar.gz -C curl-src --strip-components=1
  (
    cd curl-src
    ./configure --host="$HOST" --prefix="$PREFIX" --disable-shared --enable-static \
      --with-openssl="$PREFIX" --with-ca-path=/system/etc/security/cacerts \
      --without-libpsl --without-zlib --without-brotli --without-zstd --without-nghttp2 \
      --without-libidn2 --without-librtmp --without-libssh2 \
      --disable-ldap --disable-ldaps --disable-manual --disable-docs \
      CPPFLAGS="-I$PREFIX/include" CFLAGS="-O2 -fPIC" LDFLAGS="-L$PREFIX/lib" LIBS="-ldl"
    make -j"$JOBS"
    make install
  )

  touch "$PREFIX/.deps-done"
}

if [ ! -f "$PREFIX/.deps-done" ]; then
  build_deps 2>&1 | tee "$LOGS/$ABI-deps.log"
fi

# --- Code source de ccminer ----------------------------------------------------
echo "=== Code source : $CCMINER_REPO ($CCMINER_REF) ==="
rm -rf "$WORK/ccminer-src" && mkdir -p "$WORK/ccminer-src"
(
  cd "$WORK/ccminer-src"
  git init -q
  git remote add origin "$CCMINER_REPO"
  git fetch -q --depth 1 origin "$CCMINER_REF"
  git checkout -q FETCH_HEAD
  echo "$CCMINER_REPO $(git rev-parse HEAD)" > "$OUT/$ABI-source.txt"
)

# --- Compilation de chaque variante (une qui échoue n'arrête pas l'autre) ------
ok=0
for v in $VARIANTS; do
  if bash "${BASH_SOURCE[0]}" variant "$v" 2>&1 | tee "$LOGS/$ABI-$v.log"; then
    ok=1
  else
    echo "::warning::La variante '$v' pour $ABI n'a pas pu être compilée (voir le journal)."
  fi
done

if [ "$ok" -ne 1 ]; then
  echo "Aucune variante compilée pour $ABI." >&2
  exit 1
fi
ls -l "$OUT"
