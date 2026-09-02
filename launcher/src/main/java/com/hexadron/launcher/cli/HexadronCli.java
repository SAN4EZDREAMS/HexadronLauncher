/*
 * HexadronLauncher - a Minecraft launcher, and the Hexadron Optimise mod.
 * Copyright (c) 2026 SAN4EZDREAMS. All rights reserved.
 *
 * Licensed for noncommercial use only. You may use, study, share and improve
 * this software; you may not sell it, and you may not remove, alter or obscure
 * this notice or the authorship it records. Full terms: LICENSE.md in the
 * project root. Provided without any warranty.
 *
 * SPDX-License-Identifier: LicenseRef-Hexadron-NC-1.0
 */

package com.hexadron.launcher.cli;

import com.hexadron.launcher.auth.Account;
import com.hexadron.launcher.core.LauncherService;
import com.hexadron.launcher.core.Progress;
import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.install.loader.LoaderVersion;
import com.hexadron.launcher.launch.GameLauncher;
import com.hexadron.launcher.launch.JavaLocator;
import com.hexadron.launcher.meta.VersionManifest;
import com.hexadron.launcher.mods.ModInstaller;
import com.hexadron.launcher.mods.ModPack;
import com.hexadron.launcher.mods.ModProvider;
import com.hexadron.launcher.profile.Profile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Headless entry point.
 *
 * <p>Exists for three reasons: it makes every part of the launcher usable
 * without a display (servers, CI, remote boxes), it is the fastest way to
 * reproduce a user's install problem, and it keeps the UI honest by proving no
 * launch logic has leaked into it.
 *
 * <pre>
 *   versions [--all]                        list Minecraft versions
 *   loaders &lt;loader&gt; &lt;mcVersion&gt;            list loader builds
 *   java                                    list detected Java runtimes
 *   profiles                                list profiles
 *   create &lt;name&gt; &lt;mcVersion&gt; [loader]      create a profile
 *   install &lt;profile&gt;                       download everything the profile needs
 *   mods &lt;profile&gt; [pack.json]              install a mod pack (default: Hexadron Optimise)
 *   search &lt;query&gt; &lt;mcVersion&gt; &lt;loader&gt;     search Modrinth and CurseForge
 *   offline &lt;username&gt;                      add an offline account
 *   accounts                                list accounts
 *   play &lt;profile&gt; [username]               install if needed, then launch
 * </pre>
 */
public final class HexadronCli {

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            int code = new HexadronCli().run(args);
            System.exit(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("interrupted");
            System.exit(130);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            if (System.getenv("HEXADRON_DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private int run(String[] args) throws Exception {
        String command = args[0];
        LauncherService service = LauncherService.createDefault();
        Progress progress = Progress.console();

        switch (command) {
            case "versions" -> {
                boolean all = args.length > 1 && args[1].equals("--all");
                VersionManifest manifest = service.minecraftVersions();
                System.out.println("latest release:  " + manifest.latestRelease());
                System.out.println("latest snapshot: " + manifest.latestSnapshot());
                List<VersionManifest.Entry> entries = all ? manifest.versions() : manifest.releases();
                entries.stream().limit(all ? Long.MAX_VALUE : 40)
                        .forEach(entry -> System.out.println("  " + entry.id()
                                + "  [" + entry.channel().id() + "]"));
            }
            case "loaders" -> {
                requireArgs(args, 3, "loaders <loader> <mcVersion>");
                LoaderType loader = LoaderType.fromId(args[1]);
                List<LoaderVersion> versions = service.loaderVersions(loader, args[2]);
                versions.stream().limit(30).forEach(version ->
                        System.out.println("  " + version.version()
                                + (version.stable() ? "  (stable)" : "")));
            }
            case "java" -> {
                // "java 17" fetches one; "java" alone only reports. Downloading
                // has to be asked for explicitly here, because a headless run
                // has nobody to answer the prompt the interface would show.
                if (args.length >= 2) {
                    int major = Integer.parseInt(args[1]);
                    var provisioner = service.javaRuntimes().provisioner();
                    var existing = provisioner.installed(major);
                    if (existing.isPresent()) {
                        System.out.println("already installed: " + existing.get());
                    } else {
                        var candidate = provisioner.find(major).orElseThrow(() -> new IOException(
                                "Eclipse Adoptium publishes no Java " + major + " build for this platform"));
                        System.out.println("downloading " + candidate);
                        System.out.println("  " + candidate.url());
                        System.out.println(provisioner.install(candidate, Progress.console()));
                    }
                    return 0;
                }
                List<JavaLocator.JavaRuntime> runtimes = service.javaLocator().discover();
                if (runtimes.isEmpty()) {
                    System.out.println("no Java runtimes detected");
                }
                runtimes.forEach(runtime -> System.out.println("  " + runtime));
                System.out.println("automatic downloads: "
                        + service.settings().javaDownloadPolicy().stored());
            }
            case "profiles" -> {
                if (service.profiles().isEmpty()) {
                    System.out.println("no profiles yet - create one with: create <name> <mcVersion> [loader]");
                }
                service.profiles().byRecency().forEach(profile ->
                        System.out.println("  " + profile.id() + "  " + profile));
            }
            case "create" -> {
                requireArgs(args, 3, "create <name> <mcVersion> [loader]");
                LoaderType loader = args.length > 3 ? LoaderType.fromId(args[3]) : LoaderType.VANILLA;
                Profile profile = service.profiles().add(Profile.create(args[1], args[2], loader));
                service.profiles().save();
                System.out.println("created " + profile.id() + "  " + profile);
            }
            case "install" -> {
                requireArgs(args, 2, "install <profile>");
                Profile profile = requireProfile(service, args[1]);
                service.installProfile(profile, progress);
                System.out.println("installed " + profile.effectiveVersionId());
            }
            case "mods" -> {
                requireArgs(args, 2, "mods <profile> [pack.json]");
                Profile profile = requireProfile(service, args[1]);
                ModPack pack = args.length > 2
                        ? ModPack.fromFile(java.nio.file.Paths.get(args[2]))
                        : ModPack.hexadronOptimise();
                ModInstaller.Result result = service.installPack(profile, pack, progress);
                System.out.println("installed " + result.installed().size() + " mod(s)");
                result.skipped().forEach(note -> System.out.println("  skipped: " + note));
                result.manualDownloads().forEach(note -> System.out.println("  manual: " + note));
            }
            case "addjar" -> {
                requireArgs(args, 3, "addjar <profile> <path-to-jar>");
                Profile profile = requireProfile(service, args[1]);
                java.nio.file.Path installed =
                        service.installLocalMod(profile, java.nio.file.Paths.get(args[2]));
                System.out.println("copied to " + installed);
            }
            case "search" -> {
                requireArgs(args, 4, "search <query> <mcVersion> <loader>");
                LoaderType loader = LoaderType.fromId(args[3]);
                for (ModProvider provider : service.modProviders()) {
                    System.out.println("== " + provider.source().displayName()
                            + (provider.isAvailable() ? "" : " (not configured - skipping)"));
                    if (!provider.isAvailable()) {
                        continue;
                    }
                    provider.search(args[1], args[2], loader, 10).forEach(hit ->
                            System.out.printf("  %-24s %-36s %s%n",
                                    hit.projectId(), hit.title(), hit.author()));
                }
            }
            case "offline" -> {
                requireArgs(args, 2, "offline <username>");
                Account account = offlineAccount(args[1]);
                service.accounts().add(account);
                service.accounts().save();
                System.out.println("added " + account + "  uuid=" + account.uuid());
            }
            case "accounts" -> {
                if (service.accounts().isEmpty()) {
                    System.out.println("no accounts - add one with: offline <username>");
                }
                service.accounts().all().forEach(account ->
                        System.out.println("  " + account + "  uuid=" + account.uuid()));
            }
            case "play" -> {
                requireArgs(args, 2, "play <profile> [username]");
                Profile profile = requireProfile(service, args[1]);
                Account account = args.length > 2
                        ? offlineAccount(args[2])
                        : service.accounts().selected().orElse(Account.offline("Player"));

                CountDownLatch finished = new CountDownLatch(1);
                int[] exitCode = {0};
                GameLauncher.GameSession session = service.launch(profile, account, progress,
                        System.out::println,
                        code -> {
                            exitCode[0] = code;
                            finished.countDown();
                        });
                Runtime.getRuntime().addShutdownHook(new Thread(session::terminate));
                finished.await();
                System.out.println(GameLauncher.describeExit(
                        exitCode[0], profile.wrapperCommand()));
                return exitCode[0] == 0 ? 0 : 1;
            }
            default -> {
                System.err.println("unknown command: " + command);
                usage();
                return 2;
            }
        }
        return 0;
    }

    /**
     * Builds an offline account, turning a rejected name into a message that
     * says what is wrong. Minecraft itself reports such a name only after the
     * world has loaded, as "Invalid characters in username".
     */
    private static Account offlineAccount(String username) throws IOException {
        if (!Account.isValidUsername(username)) {
            throw new IOException("Minecraft will not accept the player name \"" + username
                    + "\". A name is 3 to 16 characters and uses only Latin letters, digits "
                    + "and underscore.");
        }
        return Account.offline(username);
    }

    private static Profile requireProfile(LauncherService service, String idOrName) throws IOException {
        Optional<Profile> byId = service.profiles().byId(idOrName);
        if (byId.isPresent()) {
            return byId.get();
        }
        Map<String, Profile> byName = new LinkedHashMap<>();
        service.profiles().all().forEach(profile -> byName.putIfAbsent(profile.name(), profile));
        Profile match = byName.get(idOrName);
        if (match == null) {
            throw new IOException("no profile '" + idOrName + "'. Known: "
                    + service.profiles().all().stream().map(Profile::id).toList());
        }
        return match;
    }

    private static void requireArgs(String[] args, int required, String usage) throws IOException {
        if (args.length < required) {
            throw new IOException("usage: " + usage);
        }
    }

    private static void usage() {
        System.out.println("""
                HexadronLauncher CLI

                  versions [--all]                     list Minecraft versions
                  loaders <loader> <mcVersion>         list loader builds
                  java                                 list detected Java runtimes
                  java <major>                         download a Temurin JRE of that version
                  profiles                             list profiles
                  create <name> <mcVersion> [loader]   create a profile
                  install <profile>                    download everything the profile needs
                  mods <profile> [pack.json]           install a mod pack (default: Hexadron Optimise)
                  addjar <profile> <jar>               copy a locally built mod jar into the profile
                  search <query> <mcVersion> <loader>  search Modrinth and CurseForge
                  offline <username>                   add an offline account
                  accounts                             list accounts
                  play <profile> [username]            install if needed, then launch

                loaders: vanilla, fabric, quilt, forge, neoforge
                """);
    }
}
