package com.hexadron.launcher.auth.secret;

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * macOS Keychain, through the {@code security} command that ships with the
 * system.
 *
 * <p>The keychain is unlocked with the user's login password and is encrypted
 * at rest by the operating system, so a copy of the launcher's data folder -
 * taken from a backup, a Time Machine snapshot or another account - contains no
 * credential at all. That is the property a launcher-side encryption key cannot
 * have.
 *
 * <p>The {@code security} binary is used rather than the Security framework
 * because the launcher takes no native dependencies; the cost is one short-lived
 * process per read or write, which happens at sign-in and at launch, not in a
 * loop.
 */
public final class KeychainSecretStore extends ProcessSecretStore {

    private static final String SERVICE = "HexadronLauncher";

    @Override
    public String id() {
        return "keychain";
    }

    @Override
    public String displayName() {
        return "macOS Keychain";
    }

    @Override
    public boolean isOsProtected() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        if (!Platform.isMac()) {
            return false;
        }
        try {
            // "security help" needs no keychain access and no arguments that could
            // create anything, so this is a safe existence probe.
            return run(List.of("security", "help"), null).exitCode() != 127;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void store(String key, String value) throws IOException {
        // -U updates an existing item instead of failing with errSecDuplicateItem.
        // -w with no value makes security read the password from stdin; it may ask
        // for it twice, so the value is written twice and any surplus is ignored
        // when the helper exits after the first read.
        byte[] stdin = (value + "\n" + value + "\n").getBytes(StandardCharsets.UTF_8);
        Result result = run(List.of("security", "add-generic-password",
                "-U", "-s", SERVICE, "-a", key, "-w"), stdin);
        if (!result.ok()) {
            throw new IOException("macOS Keychain refused to store the credential: " + result.stderr());
        }
    }

    @Override
    public Optional<String> load(String key) throws IOException {
        Result result = run(List.of("security", "find-generic-password",
                "-s", SERVICE, "-a", key, "-w"), null);
        if (!result.ok()) {
            // Exit 44 is "item not found", which is an answer rather than a failure.
            if (result.exitCode() == 44 || result.stderr().contains("could not be found")) {
                return Optional.empty();
            }
            throw new IOException("macOS Keychain refused to read the credential: " + result.stderr());
        }
        String value = result.stdout().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public void delete(String key) throws IOException {
        Result result = run(List.of("security", "delete-generic-password",
                "-s", SERVICE, "-a", key), null);
        if (!result.ok() && result.exitCode() != 44
                && !result.stderr().contains("could not be found")) {
            throw new IOException("macOS Keychain refused to delete the credential: " + result.stderr());
        }
    }
}
