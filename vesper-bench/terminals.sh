#!/bin/sh
# Times the reference terminals on Linux and prints the baseline table. Run it from the repository
# root inside an ubuntu:26.04 container (as CI does) after
# `./gradlew :vesper-bench:linkReleaseExecutableLinuxX64`; the first argument is where the table goes.
set -eu
out=${1:-build/baseline.md}
bench=vesper-bench/build/bin/linuxX64/releaseExecutable/vesper-bench.kexe

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
# Mesa's software renderer gives the GPU terminals OpenGL on a machine without a GPU.
apt-get install -y -qq --no-install-recommends \
  ca-certificates curl kitty alacritty foot cage xvfb xauth \
  libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libegl1 fonts-dejavu-core >/dev/null

# WezTerm and Contour are not in Ubuntu; their release packages are pinned by checksum.
install_deb() {
  curl -fsSL -o "$2" "$1"
  echo "$3  $2" | sha256sum -c --quiet -
  apt-get install -y -qq "./$2" >/dev/null
}
cd /tmp
install_deb https://github.com/wezterm/wezterm/releases/download/20240203-110809-5046fc22/wezterm-20240203-110809-5046fc22.Ubuntu22.04.deb \
  wezterm.deb 86358dab5794a4fb63f7c91dd68d4fdc3da58faad648a58fc77d2bd51c7b0686
install_deb https://github.com/contour-terminal/contour/releases/download/v0.8.0.9335/contour-0.8.0.9335-ubuntu26.04-amd64.deb \
  contour.deb aa5698f4cb88486b0beaacae525426596f21ecf9566273ae7f5db7944aad851d
cd - >/dev/null

export XDG_RUNTIME_DIR=/tmp/xdg
mkdir -m 700 "$XDG_RUNTIME_DIR"
Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp >/dev/null 2>&1 &
export DISPLAY=:99
# WAYLAND_DISPLAY stays unset: kitty, Alacritty and WezTerm pick Wayland only when it is set, so they
# draw on Xvfb, while foot, which speaks only Wayland, connects to the default wayland-0 socket. cage
# rather than weston: foot needs a seat, and weston's headless backend has none without input devices.
WLR_BACKENDS=headless WLR_RENDERER=pixman WLR_LIBINPUT_NO_DEVICES=1 cage -- sleep infinity >/dev/null 2>&1 &
for _ in $(seq 50); do
  [ -S /tmp/.X11-unix/X99 ] && [ -S "$XDG_RUNTIME_DIR/wayland-0" ] && break
  sleep 0.2
done

"$bench" "$out"
