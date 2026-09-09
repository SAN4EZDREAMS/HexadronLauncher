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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** What the user answered when asked about a download. */
    public enum Answer {
        /** Fetch this one. Ask again next time. */
        ONCE,
        /** Fetch this one, and stop asking. */
        ALWAYS,
        /** Do not fetch it. */
        NO
    }

    /**
     * Asked before a download.
     *
     * <p>Given the exact build that would be fetched, so the question can name
     * the version, the size and the vendor rather than asking for a blank
     * cheque.
     *
     * <p>Three answers rather than two, because "yes" used to mean "yes, and
     * never ask me again" - one click silently rewrote {@code
     * javaDownloadPolicy} to {@code always}. Saying yes to a 45 MB download is
     * not the same as handing over the decision for good, so the two are
     * separate answers now and the dialog asks for the second one explicitly.
     */
    @FunctionalInterface
    public interface Consent {

        Answer ask(JavaProvisioner.Candidate candidate);
    }

    /** Refuses everything. The default, so a headless run never blocks. */
    public static final Consent DECLINE = candidate -> Answer.NO;

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
     *                      for launching, and for the Forge and NeoForge
     *                      installer chain: the game and the loaders are built
     *                      and tested against the one JVM generation Mojang
     *                      names, and "new enough" is a different property from
     *                      "the one this was checked against" - see
     *                      ProcessorRunner, and the note on this method's
     *                      fallback below.
     */
    public JavaLocator.JavaRuntime resolve(String explicitPath, int requiredMajor,
                                           boolean exactWanted, Progress progress)
            throws IOException, InterruptedException {

        JavaLocator.JavaRuntime explicit = null;
        try {
            explicit = locator.explicit(explicitPath, requiredMajor);
        } catch (IOException e) {
            // A wrong explicit path used to end the launch here, on the grounds
            // that quietly downloading a runtime would hide the real fault. It
            // hid a worse one: a profile whose Java field was set a year ago,
            // against a JDK since uninstalled or upgraded, could not be started
            // at all and the fix was in a dialog the message did not name. It is
            // reported and then set aside, loudly enough to be the first thing
            // in the log.
            progress.log("Ignoring the Java path set for this profile - %s. Clear or correct "
                    + "it in the profile settings; the launcher will choose a runtime itself "
                    + "in the meantime.", e.getMessage());
        }
        if (explicit != null) {
            if (explicit.majorVersion() != requiredMajor) {
                progress.log("This profile is pinned to %s, and this version asks for Java %d. "
                        + "That pin is being honoured. If the game will not start, clearing the "
                        + "Java path in the profile settings is the first thing to try.",
                        explicit, requiredMajor);
            }
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
            progress.log("No Java %d is installed, and one was not fetched; falling back to %s. "
                    + "This is the mismatch that breaks old modpacks - a 1.12.2 pack on a "
                    + "modern JVM crashes on startup - so if it does not start, installing "
                    + "Java %d is the fix.",
                    requiredMajor, best.get(), requiredMajor);
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
            Answer answer = consent.ask(candidate.get());
            if (answer == Answer.NO) {
                return Optional.empty();
            }
            if (answer == Answer.ALWAYS) {
                // Stored only when that is what was actually asked for.
                policyWriter.accept(DownloadPolicy.ALWAYS);
            }
        }

        return Optional.of(provisioner.install(candidate.get(), progress));
    }

    /**
     * Fetches {@code requiredMajor} if it is not already there, asking first.
     *
     * <p>For the point at which a modpack is about to be installed. The runtime
     * is not needed to unpack anything - it is needed minutes later, when the
     * player presses Play - and the whole reason this exists is that finding out
     * then is finding out too late. It reports rather than throws for the same
     * reason: a pack whose install is otherwise fine should not be abandoned
     * because a download was declined.
     *
     * @return the runtime, or empty when there is none and none was fetched
     */
    public Optional<JavaLocator.JavaRuntime> ensure(int requiredMajor, Progress progress)
            throws InterruptedException {

        try {
            List<JavaLocator.JavaRuntime> installed = locator.discover();
            if (JavaLocator.hasExactly(installed, requiredMajor)) {
                return JavaLocator.choose(installed, requiredMajor);
            }
            Optional<JavaLocator.JavaRuntime> already = provisioner.installed(requiredMajor);
            if (already.isPresent()) {
                return already;
            }
            return tryDownload(requiredMajor, JavaLocator.choose(installed, requiredMajor).orElse(null),
                    progress);
        } catch (IOException e) {
            progress.log("Could not settle Java %d ahead of time: %s. The launcher will try "
                    + "again when the game is started.", requiredMajor, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes the runtimes this launcher downloaded that nothing asks for any
     * more.
     *
     * <p>Called after a profile is removed. A runtime is shared - one Java 21
     * serves every profile that wants Java 21 - so the question is never "which
     * runtime belonged to that profile" but "is anything still asking for this
     * one". {@code keep} is that answer, and it is the caller's job to make it
     * complete: an incomplete set here deletes a runtime a profile still needs.
     *
     * <p>Only ever touches folders carrying the launcher's own marker. A Java
     * the user installed themselves is not the launcher's to remove, whatever
     * the profile list says.
     *
     * @param keep major versions still in use
     * @return the major versions that were deleted
     */
    public List<Integer> prune(Set<Integer> keep, Progress progress) {
        List<Integer> deleted = new ArrayList<>();
        for (int major : provisioner.installedMajors()) {
            if (keep.contains(major)) {
                continue;
            }
            try {
                List<Path> undeleted = provisioner.uninstall(major);
                if (undeleted.isEmpty()) {
                    deleted.add(major);
                    progress.log("Removed the Java %d runtime the launcher had downloaded: "
                            + "no profile asks for it any more.", major);
                } else {
                    // Almost always a game still running on it. Left alone and
                    // named; the next removal will find it again.
                    progress.log("Java %d is no longer needed, and %d file(s) under %s could "
                            + "not be deleted - something is still using it.",
                            major, undeleted.size(), provisioner.home(major));
                }
            } catch (IOException e) {
                progress.log("Left the Java %d runtime in place: %s", major, e.getMessage());
            }
        }
        return List.copyOf(deleted);
    }

    /** The major versions the launcher has downloaded and could still delete. */
    public List<Integer> managedMajors() {
        return provisioner.installedMajors();
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
