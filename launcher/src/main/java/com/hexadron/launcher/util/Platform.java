/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 OLEKSII RADCHUK (SAN4EZDREAMS). All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.util;

import java.util.List;

import java.util.Locale;

/**
 * Host platform identification, in the vocabulary Mojang's version JSON uses.
 *
 * <p>Rule blocks in a version JSON are written against {@code os.name} values of
 * {@code windows}, {@code osx} and {@code linux}, and {@code os.arch} values of
 * {@code x86}, {@code x64}, {@code arm64} and {@code arm32}. Native library
 * classifiers additionally interpolate {@code ${arch}} as the bit width (32/64).
 */
public final class Platform {

    public enum OsFamily {
        WINDOWS("windows"),
        OSX("osx"),
        LINUX("linux");

        private final String mojangName;

        OsFamily(String mojangName) {
            this.mojangName = mojangName;
        }

        /** The value Mojang rule blocks match against. */
        public String mojangName() {
            return mojangName;
        }
    }

    private static final OsFamily OS = detectOs();
    private static final String FLATPAK_ID = detectFlatpak(
            java.nio.file.Path.of("/.flatpak-info"), System.getenv("FLATPAK_ID"));
    private static final String ARCH = detectArch();
    private static final String OS_VERSION = System.getProperty("os.version", "");

    private Platform() {
    }

    public static OsFamily os() {
        return OS;
    }

    public static String osName() {
        return OS.mojangName();
    }

    public static String osVersion() {
        return OS_VERSION;
    }

    /** One of {@code x86}, {@code x64}, {@code arm64}, {@code arm32}. */
    public static String arch() {
        return ARCH;
    }

    /** The {@code ${arch}} substitution used in native classifiers: "32" or "64". */
    public static String archBits() {
        return (ARCH.equals("x86") || ARCH.equals("arm32")) ? "32" : "64";
    }

    public static boolean isWindows() {
        return OS == OsFamily.WINDOWS;
    }

    public static boolean isMac() {
        return OS == OsFamily.OSX;
    }

    public static boolean isLinux() {
        return OS == OsFamily.LINUX;
    }

    /**
     * True when the launcher runs inside a Flatpak sandbox.
     *
     * <p>What changes there: {@code /app} is read-only, so the launcher cannot
     * replace itself and updates come from Flatpak; the data folder is the
     * sandbox's own under {@code ~/.var/app}; and only the folders the sandbox
     * was granted are visible - the downloads folder, not the whole home.
     */
    public static boolean isFlatpak() {
        return FLATPAK_ID != null;
    }

    /** The Flatpak application id, or null outside a sandbox. */
    public static String flatpakId() {
        return FLATPAK_ID;
    }

    /**
     * Whether this is a Flatpak sandbox, from the two things Flatpak itself
     * provides: the {@code /.flatpak-info} file every sandbox has at its root,
     * and the {@code FLATPAK_ID} variable. Either is enough - the file is the
     * one that cannot be missing, the variable is the one that names the app.
     *
     * <p>Package-visible arguments so the self-check can put both to it without
     * being inside a sandbox.
     *
     * @return the application id, "flatpak" when only the file says so, or
     *         null when this is not a sandbox
     */
    public static String detectFlatpak(java.nio.file.Path infoFile, String envId) {
        if (envId != null && !envId.isBlank()) {
            return envId.trim();
        }
        try {
            if (infoFile != null && java.nio.file.Files.isRegularFile(infoFile)) {
                for (String line : java.nio.file.Files.readAllLines(infoFile)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("name=")) {
                        return trimmed.substring(5).trim();
                    }
                }
                return "flatpak";
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // A file that exists but cannot be read is still a sandbox.
            return "flatpak";
        }
        return null;
    }

    /** Path separator for the {@code -cp} argument: ';' on Windows, ':' elsewhere. */
    /**
     * A system program by full path, found where the operating system keeps
     * it rather than through PATH or the working directory.
     *
     * <p>Windows looks for a bare program name in the current directory before
     * the system folders, and PATH on every system can hold a folder the user
     * can write to. For helpers the launcher starts - the file manager, the
     * browser opener, the registry reader - a file of the same name planted
     * there would run instead. Falls back to the bare name only when none of
     * the usual places has it.
     *
     * @param name the program, e.g. {@code "explorer.exe"}, {@code "xdg-open"}
     */
    public static String systemTool(String name) {
        List<java.nio.file.Path> places = new java.util.ArrayList<>();
        if (isWindows()) {
            String root = System.getenv("SystemRoot");
            if (root == null || root.isBlank()) {
                root = "C:\\Windows";
            }
            places.add(java.nio.file.Path.of(root, "System32", name));
            places.add(java.nio.file.Path.of(root, name));
        } else {
            for (String folder : new String[]{"/usr/bin", "/bin", "/app/bin", "/usr/local/bin"}) {
                places.add(java.nio.file.Path.of(folder, name));
            }
        }
        for (java.nio.file.Path place : places) {
            if (java.nio.file.Files.isRegularFile(place)) {
                return place.toString();
            }
        }
        return name;
    }

    public static String classpathSeparator() {
        return isWindows() ? ";" : ":";
    }

    /** Name of the java executable to look for inside a JRE's bin directory. */
    public static String javaExecutableName() {
        return isWindows() ? "javaw.exe" : "java";
    }

    /** Console-attached java executable - used when we want the child's stdio. */
    public static String javaConsoleExecutableName() {
        return isWindows() ? "java.exe" : "java";
    }

    /**
     * The component name Mojang's java runtime manifest uses for this host,
     * e.g. {@code windows-x64}, {@code mac-os-arm64}, {@code linux}.
     */
    public static String javaRuntimePlatform() {
        return switch (OS) {
            case WINDOWS -> switch (ARCH) {
                case "x86" -> "windows-x86";
                case "arm64" -> "windows-arm64";
                default -> "windows-x64";
            };
            case OSX -> ARCH.equals("arm64") ? "mac-os-arm64" : "mac-os";
            case LINUX -> ARCH.equals("x86") ? "linux-i386" : "linux";
        };
    }

    private static OsFamily detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return OsFamily.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin") || name.contains("osx")) {
            return OsFamily.OSX;
        }
        return OsFamily.LINUX;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.startsWith("arm")) {
            return "arm32";
        }
        // "amd64", "x86_64" are 64-bit; "x86", "i386".."i686" are 32-bit.
        if (arch.contains("64")) {
            return "x64";
        }
        return "x86";
    }
}
