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

package com.hexadron.launcher.launch;

import com.hexadron.launcher.core.GameDirs;
import com.hexadron.launcher.core.Progress;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Decides which Java a launch or an install runs on, and fetches one when the
 * machine has nothing that will do.
 *
 * <p>The policy lives in one place on purpose. Two code paths need a runtime -
 * starting the game, and running a Forge/NeoForge installer's processor chain -
 * and before this class they each made their own decision, so a machine could
 * install a version it then could not start, and the "no Java found" message
 * appeared in two different shapes. One resolver means one answer and one
 * message.
 *
 * <p>The order is: the profile's explicit setting, then an installed runtime of
 * exactly the right major version, then any installed runtime new enough, then
 * a download. Downloading last matters - a user who already has a working Java
 * should never be made to wait for 45 MB they did not need.
 */
public final class JavaRuntimes {

    /** What the launcher may do when no installed runtime fits. */
    public enum DownloadPolicy {
        /** Ask the user, once. */
        ASK,
        /** Fetch it without asking. */
        ALWAYS,
        /** Never fetch; fail with instructions instead. */
        NEVER;

        public static DownloadPolicy parse(String value) {
            if (value == null) {
                return ASK;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "always", "auto", "true", "yes" -> ALWAYS;
                case "never", "off", "false", "no" -> NEVER;
                default -> ASK;
            };
        }

        public String stored() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Asked before the first download.
     *
     * <p>Given the exact build that would be fetched, so the question can name
     * the version, the size and the vendor rather than asking for a blank
     * cheque.
     */
    @FunctionalInterface
    public interface Consent {

        /** @return true to download this runtime now */
        boolean allow(JavaProvisioner.Candidate candidate);
    }

    /** Refuses everything. The default, so a headless run never blocks. */
    public static final Consent DECLINE = candidate -> false;

    private final JavaLocator locator;
    private final JavaProvisioner provisioner;
    private final Supplier<DownloadPolicy> policy;
    private final Consumer<DownloadPolicy> policyWriter;

    private volatile Consent consent = DECLINE;

    /**
     * @param policy       reads the current setting
     * @param policyWriter stores the setting after the user answers the prompt,
     *                     so they are asked once rather than once per version
     */
    public JavaRuntimes(GameDirs dirs, JavaLocator locator,
                        Supplier<DownloadPolicy> policy, Consumer<DownloadPolicy> policyWriter) {
        this.locator = locator;
        this.provisioner = new JavaProvisioner(dirs, locator);
        this.policy = policy;
        this.policyWriter = policyWriter;
    }

    /** Installs the prompt. The interface sets this; headless callers do not. */
    public void consent(Consent value) {
        this.consent = value == null ? DECLINE : value;
    }

    public JavaProvisioner provisioner() {
        return provisioner;
    }

    /**
     * Resolves a runtime for {@code requiredMajor}.
     *
     * @param explicitPath  the profile's Java setting, or null
     * @param requiredMajor the major version this Minecraft version declares
     * @param exactWanted   true when only the exact major is trustworthy. Set
     *                      for the Forge and NeoForge installer chain: those are
     *                      third-party programs, some of them a decade old, that
     *                      were built and tested against one JVM and are known
     *                      to misbehave on newer ones - see ProcessorRunner.
     */
    public JavaLocator.JavaRuntime resolve(String explicitPath, int requiredMajor,
                                           boolean exactWanted, Progress progress)
            throws IOException, InterruptedException {

        JavaLocator.JavaRuntime explicit = locator.explicit(explicitPath, requiredMajor);
        if (explicit != null) {
            return explicit;
        }

        List<JavaLocator.JavaRuntime> installed = locator.discover();
        Optional<JavaLocator.JavaRuntime> best = JavaLocator.choose(installed, requiredMajor);

        boolean exactAvailable = JavaLocator.hasExactly(installed, requiredMajor);
        if (best.isPresent() && (exactAvailable || !exactWanted)) {
            return best.get();
        }

        // Nothing installed fits, or something fits but is the wrong generation
        // for a job that needs the right one. Either way a download is the fix.
        Optional<JavaLocator.JavaRuntime> fetched = tryDownload(requiredMajor, best.orElse(null), progress);
        if (fetched.isPresent()) {
            return fetched.get();
        }

        if (best.isPresent()) {
            // Declined or unavailable, but there is something new enough. Use it
            // and say so, rather than refusing to start over a preference.
            progress.log("No Java %d is installed; falling back to %s. If the game or the "
                    + "installer misbehaves, this is the first thing to change.",
                    requiredMajor, best.get());
            return best.get();
        }

        throw new IOException(failureMessage(requiredMajor, installed));
    }

    /**
     * Fetches a runtime if that is allowed, reusing one already fetched.
     *
     * <p>Returns empty rather than throwing when the user says no: declining is
     * an answer, not a fault, and the caller has a fallback to try.
     */
    private Optional<JavaLocator.JavaRuntime> tryDownload(
            int requiredMajor, JavaLocator.JavaRuntime fallback, Progress progress)
            throws IOException, InterruptedException {

        Optional<JavaLocator.JavaRuntime> already = provisioner.installed(requiredMajor);
        if (already.isPresent()) {
            return already;
        }

        DownloadPolicy current = policy.get();
        if (current == DownloadPolicy.NEVER) {
            return Optional.empty();
        }

        Optional<JavaProvisioner.Candidate> candidate;
        try {
            candidate = provisioner.find(requiredMajor);
        } catch (IOException e) {
            // Offline, or Adoptium unreachable. If there is a fallback the caller
            // will use it; if there is not, the thrown message below has to say
            // what went wrong here, so it is not swallowed silently.
            progress.log("Could not reach Eclipse Adoptium to look for Java %d: %s",
                    requiredMajor, e.getMessage());
            if (fallback == null) {
                throw e;
            }
            return Optional.empty();
        }

        if (candidate.isEmpty()) {
            progress.log("Eclipse Adoptium publishes no Java %d build for %s/%s.",
                    requiredMajor, JavaProvisioner.adoptiumOs(), JavaProvisioner.adoptiumArch());
            return Optional.empty();
        }

        if (current == DownloadPolicy.ASK) {
            if (!consent.allow(candidate.get())) {
                return Optional.empty();
            }
            // Answered once. From here on it happens without asking again, which
            // is what the user agreed to by saying yes.
            policyWriter.accept(DownloadPolicy.ALWAYS);
        }

        return Optional.of(provisioner.install(candidate.get(), progress));
    }

    private String failureMessage(int requiredMajor, List<JavaLocator.JavaRuntime> installed) {
        StringBuilder message = new StringBuilder(
                JavaLocator.describeMissing(requiredMajor, installed));
        if (policy.get() == DownloadPolicy.NEVER) {
            message.append("\n\nAutomatic Java downloads are switched off in the launcher "
                    + "settings. Turn them on to have the launcher fetch ")
                    .append(JavaProvisioner.attribution())
                    .append(" for you.");
        } else {
            message.append("\n\nThe launcher offered to download ")
                    .append(JavaProvisioner.attribution())
                    .append(", and could not. You can install Java ").append(requiredMajor)
                    .append(" yourself from https://adoptium.net/temurin/releases/?version=")
                    .append(requiredMajor)
                    .append(" and then press Play again.");
        }
        return message.toString();
    }
}
