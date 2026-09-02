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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Restricts a file to the account that owns it, on every platform the launcher
 * runs on.
 *
 * <p><b>Why not just {@code setPosixFilePermissions}.</b> That call throws
 * {@link UnsupportedOperationException} on Windows, which is where most
 * Minecraft players are. A credential file left at the directory's inherited
 * ACL there is readable by every other account on the machine and by anything
 * running as those accounts. This class sets an explicit, non-inherited ACL
 * granting the owner alone, which is the Windows equivalent of mode 600.
 *
 * <p>This is a boundary, not a guarantee: it stops another user and stops a
 * backup or sync agent running as another account. It does not stop code
 * running as the user themselves - nothing on a desktop does.
 */
public final class FilePermissions {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private FilePermissions() {
    }

    /**
     * Makes {@code path} readable and writable by its owner only.
     *
     * <p>Never throws: a filesystem that cannot express this (FAT32 on a USB
     * stick, an SMB share) must not stop the launcher from starting. The caller
     * decides whether that is acceptable for what it is about to write; for
     * secrets, {@link #isRestricted(Path)} answers that question.
     */
    public static void restrictToOwner(Path path) {
        boolean directory = Files.isDirectory(path);
        try {
            Files.setPosixFilePermissions(path, directory ? OWNER_ONLY_DIRECTORY : OWNER_ONLY);
            return;
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows, or a filesystem without POSIX bits. Fall through to ACLs.
        }
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            UserPrincipal owner = Files.getOwner(path);
            AclEntry ownerEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            // Replacing the ACL outright is what drops the inherited entries that
            // would otherwise let other accounts read the file.
            view.setAcl(List.of(ownerEntry));
        } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            // Reported by isRestricted, not thrown here.
        }
    }

    /**
     * True when nobody but the owner can read {@code path}.
     *
     * <p>Used to decide whether to warn the user that their credential file is
     * on a filesystem that cannot protect it.
     */
    public static boolean isRestricted(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            return permissions.stream().noneMatch(p ->
                    p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Fall through to ACLs.
        }
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return false;
            }
            UserPrincipal owner = Files.getOwner(path);
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** Creates a directory whose contents only the owner can list. */
    public static Path createRestrictedDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        restrictToOwner(directory);
        return directory;
    }

    /**
     * Writes {@code content} so that the file is never readable by anyone else,
     * not even for the instant between creation and the permission change.
     *
     * <p>The temporary file is created in the destination directory, restricted
     * while it is still empty, filled, and only then moved into place. A reader
     * therefore sees either the old file or the new one, never a half-written
     * one, and never an unprotected one.
     */
    public static void writeRestricted(Path file, byte[] content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            createRestrictedDirectory(parent);
        }
        Path temporary = Files.createTempFile(parent, ".hexadron-", ".tmp");
        try {
            restrictToOwner(temporary);
            Files.write(temporary, content);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
