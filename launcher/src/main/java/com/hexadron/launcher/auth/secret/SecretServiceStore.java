package com.hexadron.launcher.auth.secret;

import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The freedesktop Secret Service, through {@code secret-tool} (libsecret) -
 * GNOME Keyring, KWallet and every other implementation behind the same D-Bus
 * interface.
 *
 * <p>Availability is checked by an actual lookup rather than by the presence of
 * the binary: a headless session, a machine with no D-Bus, or a locked wallet
 * all have {@code secret-tool} installed and all fail at the first call. The
 * launcher must know that before it decides where to put a refresh token, not
 * after.
 */
public final class SecretServiceStore extends ProcessSecretStore {

    private static final String SERVICE_ATTRIBUTE = "HexadronLauncher";

    @Override
    public String id() {
        return "secret-service";
    }

    @Override
    public String displayName() {
        return "Secret Service (GNOME Keyring / KWallet)";
    }

    @Override
    public boolean isOsProtected() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        if (!Platform.isLinux()) {
            return false;
        }
        try {
            // A lookup for a key that does not exist. Exit 1 means "reached the
            // keyring, found nothing", which is exactly what proves it works.
            // Exit 127 means no binary; anything else means no working D-Bus.
            Result result = run(List.of("secret-tool", "lookup",
                    "service", SERVICE_ATTRIBUTE, "account", "hexadron-availability-probe"), null);
            return result.exitCode() == 0 || result.exitCode() == 1;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void store(String key, String value) throws IOException {
        Result result = run(List.of("secret-tool", "store",
                        "--label=Hexadron Launcher - " + key,
                        "service", SERVICE_ATTRIBUTE, "account", key),
                (value + "\n").getBytes(StandardCharsets.UTF_8));
        if (!result.ok()) {
            throw new IOException("the system keyring refused to store the credential: " + result.stderr());
        }
    }

    @Override
    public Optional<String> load(String key) throws IOException {
        Result result = run(List.of("secret-tool", "lookup",
                "service", SERVICE_ATTRIBUTE, "account", key), null);
        if (result.exitCode() == 1) {
            return Optional.empty();
        }
        if (!result.ok()) {
            throw new IOException("the system keyring refused to read the credential: " + result.stderr());
        }
        String value = result.stdout().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public void delete(String key) throws IOException {
        Result result = run(List.of("secret-tool", "clear",
                "service", SERVICE_ATTRIBUTE, "account", key), null);
        if (!result.ok() && result.exitCode() != 1) {
            throw new IOException("the system keyring refused to delete the credential: " + result.stderr());
        }
    }
}
