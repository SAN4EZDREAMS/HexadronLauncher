#!/bin/sh
# Starts Hexadron Launcher inside its Flatpak sandbox.
#
# The launcher finds out it is sandboxed by itself (/.flatpak-info), keeps its
# data under ~/.var/app/io.github.san4ezdreams.HexadronLauncher/data, and leaves
# updating to Flatpak.

# JavaFX and GLFW draw through X11. Said explicitly so a desktop that sets
# GDK_BACKEND=wayland in the session does not make GTK look for a socket the
# sandbox was not given.
export GDK_BACKEND=x11

exec /app/lib/HexadronLauncher/bin/HexadronLauncher "$@"
