#!/usr/bin/env bash
# Builds HexadronLauncher-linux.flatpak from the Linux application image.
#
#   launcher/packaging/flatpak/build-flatpak.sh <HexadronLauncher-linux.tar.gz> <version> [out-dir]
#
# Needs flatpak and flatpak-builder. The GNOME runtime and SDK are fetched from
# Flathub the first time (about 1 GB), then reused. The same script runs in CI,
# so a local build and a release build are made the same way.
set -euo pipefail

archive=${1:?usage: build-flatpak.sh <HexadronLauncher-linux.tar.gz> <version> [out-dir]}
version=${2:?the version to write into the metadata, e.g. 0.9.8}
out=${3:-$PWD}

here=$(cd "$(dirname "$0")" && pwd)
id=io.github.san4ezdreams.HexadronLauncher
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# A copy of this folder, so filling in the metadata never edits the tracked file.
cp -a "$here/." "$work/manifest"
cp "$here/../icon.png" "$work/icon.png"
cp "$here/../../src/main/resources/ui/icon/icon-128.png" "$work/icon-128.png"
cp "$here/../../src/main/resources/ui/icon/icon-64.png" "$work/icon-64.png"
cp "$archive" "$work/manifest/HexadronLauncher-linux.tar.gz"

# The manifest names the icons relative to the repository; the copy names them
# beside itself.
sed -i \
  -e 's#path: \.\./icon\.png#path: ../icon.png#' \
  -e 's#path: \.\./\.\./src/main/resources/ui/icon/icon-128\.png#path: ../icon-128.png#' \
  -e 's#path: \.\./\.\./src/main/resources/ui/icon/icon-64\.png#path: ../icon-64.png#' \
  "$work/manifest/$id.yml"
sed -i -e "s/@VERSION@/$version/" -e "s/@DATE@/$(date -u +%F)/" \
  "$work/manifest/$id.metainfo.xml"

# Checked before the long part, so a typo fails in a second rather than after
# the runtime has downloaded.
if command -v desktop-file-validate >/dev/null; then
  desktop-file-validate "$work/manifest/$id.desktop"
fi
if command -v appstreamcli >/dev/null; then
  appstreamcli validate --no-net "$work/manifest/$id.metainfo.xml"
fi

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo

# --disable-rofiles-fuse: CI machines have no FUSE, and the build does not need it.
flatpak-builder --user --install-deps-from=flathub --disable-rofiles-fuse \
  --force-clean --repo="$work/repo" --state-dir="$work/state" \
  "$work/build" "$work/manifest/$id.yml"

mkdir -p "$out"
# --runtime-repo: installing the bundle fetches the GNOME runtime from Flathub
# by itself, so a user needs nothing but this one file.
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  "$work/repo" "$out/HexadronLauncher-linux.flatpak" "$id"
echo "built $out/HexadronLauncher-linux.flatpak"
