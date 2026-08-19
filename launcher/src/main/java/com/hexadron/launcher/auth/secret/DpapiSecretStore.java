package com.hexadron.launcher.auth.secret;

import com.hexadron.launcher.util.FilePermissions;
import com.hexadron.launcher.util.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Windows DPAPI, scoped to the current user.
 *
 * <p>{@code CryptProtectData} with {@code CRYPTPROTECT_LOCAL_MACHINE} unset
 * derives its key from the user's logon credentials, held by the operating
 * system. The ciphertext this class writes to disk therefore decrypts only for
 * this Windows account on this machine: copying the launcher folder to another
 * PC, restoring it from a backup under a different account, or reading it as a
 * different user all yield nothing. That is precisely what a key stored beside
 * the data cannot achieve, and it is the reason this store is preferred over
 * {@link EncryptedFileSecretStore} wherever it works.
 *
 * <p><b>Why PowerShell rather than a native call.</b> The launcher takes no
 * third-party dependencies, and DPAPI is reachable through
 * {@code System.Security.Cryptography.ProtectedData}, which is present on every
 * supported Windows install. Calling it through the Foreign Function and Memory
 * API would avoid the process spawn but would tie the whole build to one JDK
 * release line for a call that happens at sign-in and at launch - not in a loop.
 *
 * <p><b>The secret never appears in an argument list.</b> The script is passed
 * as {@code -EncodedCommand} (base64 UTF-16LE, which is what that flag expects)
 * and contains no credential; the credential goes in and comes back over the
 * pipes. Additional entropy, generated once per installation, is mixed in so
 * that a blob lifted out of this folder cannot be decrypted by another
 * application running as the same user without also lifting the entropy file.
 */
public final class DpapiSecretStore extends ProcessSecretStore {

    private final Path directory;
    private final Path entropyFile;

    public DpapiSecretStore(Path secretsDirectory) {
        this.directory = secretsDirectory;
        this.entropyFile = secretsDirectory.resolve("dpapi.entropy");
    }

    @Override
    public String id() {
        return "dpapi";
    }

    @Override
    public String displayName() {
        return "Windows DPAPI (current user)";
    }

    @Override
    public boolean isOsProtected() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        if (!Platform.isWindows()) {
            return false;
        }
        try {
            // A full protect/unprotect round trip. Anything less would pass on a
            // machine where PowerShell exists but the assembly cannot be loaded.
            String probe = "hexadron-availability-probe";
            String blob = protect(probe.getBytes(StandardCharsets.UTF_8));
            byte[] back = unprotect(blob);
            return probe.equals(new String(back, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void store(String key, String value) throws IOException {
        FilePermissions.createRestrictedDirectory(directory);
        String blob = protect(value.getBytes(StandardCharsets.UTF_8));
        FilePermissions.writeRestricted(blobFile(key), blob.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public Optional<String> load(String key) throws IOException {
        Path file = blobFile(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String blob = Files.readString(file, StandardCharsets.US_ASCII).strip();
        if (blob.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new String(unprotect(blob), StandardCharsets.UTF_8));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(blobFile(key));
    }

    // ---------------------------------------------------------------- internals

    /**
     * File name is a hash of the key, not the key itself: an account UUID in a
     * directory listing is a small privacy leak with no upside.
     */
    private Path blobFile(String key) {
        return directory.resolve(sha256Hex(key) + ".dpapi");
    }

    private String protect(byte[] plaintext) throws IOException {
        String script = """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Security
                $input = [Console]::In.ReadToEnd().Trim()
                $bytes = [Convert]::FromBase64String($input)
                $entropy = [Convert]::FromBase64String('%s')
                $protected = [System.Security.Cryptography.ProtectedData]::Protect(
                    $bytes, $entropy, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
                [Console]::Out.Write([Convert]::ToBase64String($protected))
                """.formatted(entropyBase64());
        return runScript(script, Base64.getEncoder().encodeToString(plaintext));
    }

    private byte[] unprotect(String blobBase64) throws IOException {
        String script = """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Security
                $input = [Console]::In.ReadToEnd().Trim()
                $bytes = [Convert]::FromBase64String($input)
                $entropy = [Convert]::FromBase64String('%s')
                $plain = [System.Security.Cryptography.ProtectedData]::Unprotect(
                    $bytes, $entropy, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
                [Console]::Out.Write([Convert]::ToBase64String($plain))
                """.formatted(entropyBase64());
        return Base64.getDecoder().decode(runScript(script, blobBase64));
    }

    private String runScript(String script, String stdin) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Result result = run(List.of("powershell.exe",
                        "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", encoded),
                (stdin + "\n").getBytes(StandardCharsets.US_ASCII));
        if (!result.ok()) {
            throw new IOException("Windows DPAPI call failed: " + result.stderr().strip());
        }
        String out = result.stdout().strip();
        if (out.isEmpty()) {
            throw new IOException("Windows DPAPI returned nothing");
        }
        return out;
    }

    /**
     * Per-installation entropy, created on first use.
     *
     * <p>Not a secret in the cryptographic sense - it sits next to the blobs -
     * but it is what makes the blob specific to this launcher rather than
     * decryptable by any process running as the user that happens to call
     * {@code CryptUnprotectData} on the file.
     */
    private synchronized String entropyBase64() throws IOException {
        if (Files.isRegularFile(entropyFile)) {
            String existing = Files.readString(entropyFile, StandardCharsets.US_ASCII).strip();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        String encoded = Base64.getEncoder().encodeToString(entropy);
        FilePermissions.createRestrictedDirectory(directory);
        FilePermissions.writeRestricted(entropyFile, encoded.getBytes(StandardCharsets.US_ASCII));
        return encoded;
    }

    static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
