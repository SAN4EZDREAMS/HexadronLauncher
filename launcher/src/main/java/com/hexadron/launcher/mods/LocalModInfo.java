package com.hexadron.launcher.mods;

import com.hexadron.launcher.install.loader.LoaderType;
import com.hexadron.launcher.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a mod jar says about itself.
 *
 * <h2>Why the jar is asked at all</h2>
 *
 * <p>A jar a player copied into {@code mods} by hand is not in the lock file
 * and never will be, so the launcher knows exactly one thing about it: the file
 * name. {@code sodium-fabric-0.6.13+mc1.21.1.jar} is readable; {@code YSNS-2.jar}
 * and {@code modmenu.jar} are not, and a list of those is a list the user cannot
 * act on.
 *
 * <p>Every mod loader in use requires its mods to carry a metadata file, because
 * the loader itself has to read one to load the mod at all. So the same answer
 * the game gets is available here, offline, from the file already on disk - the
 * real name, the mod's own version, a sentence of description, the authors, and
 * often a link to where it came from.
 *
 * <h2>What is read, in order</h2>
 *
 * <ol>
 *   <li>{@code fabric.mod.json} - Fabric, and most Quilt mods;</li>
 *   <li>{@code quilt.mod.json} - Quilt-only mods;</li>
 *   <li>{@code META-INF/neoforge.mods.toml} - NeoForge from 1.20.5 on;</li>
 *   <li>{@code META-INF/mods.toml} - Forge 1.13+ and early NeoForge;</li>
 *   <li>{@code mcmod.info} - Forge 1.12 and older;</li>
 *   <li>{@code META-INF/MANIFEST.MF} - last resort, for the version only.</li>
 * </ol>
 *
 * <p>A jar can hold more than one of these: a mod published for both Fabric and
 * NeoForge ships both descriptors, and reading the first that parses is the same
 * choice the loaders make. Nothing here decides whether the mod will actually
 * load - that is the loader's job at launch, and guessing at it in a list would
 * be a claim this class cannot support.
 *
 * <h2>What it never does</h2>
 *
 * <p>It does not execute anything, does not follow paths out of the archive, and
 * caps how much it will read from any one entry. A mod jar is untrusted input
 * that arrived from wherever the user found it; this class treats it as such and
 * fails to {@link Optional#empty()} rather than throwing at a caller who is
 * drawing a list.
 *
 * @param modId       the loader's identifier for the mod, when it publishes one
 * @param name        the display name, or null when the descriptor omits it
 * @param version     the mod's own version
 * @param description one paragraph at most, already collapsed to a single line
 * @param authors     names as published, in order, without duplicates
 * @param homepage    a page about the mod: its own site, its source repository
 *                    or its issue tracker, whichever it publishes first
 * @param iconPath    path inside the jar to the mod's own icon, or null
 * @param loader      which loader's descriptor this came from, or
 *                    {@link LoaderType#VANILLA} when only the manifest answered
 */
public record LocalModInfo(String modId, String name, String version, String description,
                           List<String> authors, String homepage, String iconPath,
                           LoaderType loader) {

    /**
     * Largest descriptor this will read, in bytes.
     *
     * <p>A mod descriptor is a few kilobytes. A megabyte of it is either a mod
     * doing something very unusual or an archive built to make a reader allocate
     * until it dies, and the launcher has no reason to tell those apart: neither
     * one gets read.
     */
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

    /** Largest icon this will pull out of a jar. Well past any real mod logo. */
    public static final int MAX_ICON_BYTES = 4 * 1024 * 1024;

    /** Longest description kept. Beyond this it is a readme, not a summary. */
    private static final int MAX_DESCRIPTION = 400;

    public LocalModInfo {
        authors = List.copyOf(authors);
    }

    /** True when nothing but the file name would be shown for this mod. */
    public boolean isEmpty() {
        return blank(name) && blank(version) && blank(description) && authors.isEmpty();
    }

    /** The best name available, falling back to the id and then to the jar's own name. */
    public String displayName(String fileName) {
        if (!blank(name)) {
            return name;
        }
        if (!blank(modId)) {
            return modId;
        }
        return ModInstaller.readableNameFrom(fileName);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads whatever the jar publishes about itself.
     *
     * @return empty when the file is not a readable archive or carries no
     *         descriptor at all - which is not an error, and is exactly what a
     *         library jar or a stray zip in the folder looks like
     */
    public static Optional<LocalModInfo> read(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Optional<LocalModInfo> found = readFabric(zip);
            if (found.isPresent()) {
                return found;
            }
            found = readQuilt(zip);
            if (found.isPresent()) {
                return found;
            }
            found = readModsToml(zip, "META-INF/neoforge.mods.toml", LoaderType.NEOFORGE);
            if (found.isPresent()) {
                return found;
            }
            found = readModsToml(zip, "META-INF/mods.toml", LoaderType.FORGE);
            if (found.isPresent()) {
                return found;
            }
            found = readMcmodInfo(zip);
            if (found.isPresent()) {
                return found;
            }
            return readManifest(zip);
        } catch (IOException | RuntimeException e) {
            // A corrupt or non-zip file in the mods folder is the user's to deal
            // with. It is still listed, by file name, because hiding it would
            // hide the thing that is stopping the game from starting.
            return Optional.empty();
        }
    }

    /**
     * The mod's own icon, as bytes.
     *
     * <p>Read on demand rather than with the metadata: a folder of eighty mods
     * is eighty descriptors worth reading up front and eighty images worth
     * decoding only for the rows that are actually on screen.
     */
    public static Optional<byte[]> readIcon(Path jar, String iconPath) {
        if (jar == null || blank(iconPath) || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        String cleaned = iconPath.startsWith("/") ? iconPath.substring(1) : iconPath;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(cleaned);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_ICON_BYTES);
                return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- fabric

    /**
     * {@code fabric.mod.json}, the Fabric Loader v1 descriptor.
     *
     * <p>{@code authors} is a mixed array on purpose in the specification: a
     * plain string for a name, or an object with a {@code name} and contact
     * details. Both shapes appear in mods published today, so both are read.
     */
    private static Optional<LocalModInfo> readFabric(ZipFile zip) {
        Optional<Json> parsed = readJson(zip, "fabric.mod.json");
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Json root = parsed.get();
        String modId = root.get("id").asString(null);
        if (modId == null) {
            return Optional.empty();
        }
        Json contact = root.get("contact");
        return Optional.of(new LocalModInfo(
                modId,
                root.get("name").asString(null),
                root.get("version").asString(null),
                trim(root.get("description").asString(null)),
                namesOf(root.get("authors"), root.get("contributors")),
                firstUrl(contact.get("homepage").asString(null),
                        contact.get("sources").asString(null),
                        contact.get("issues").asString(null)),
                iconPathOf(root.get("icon")),
                LoaderType.FABRIC));
    }

    /**
     * {@code quilt.mod.json}, whose fields live one level down under
     * {@code quilt_loader}, with the human-readable ones under
     * {@code metadata} below that.
     */
    private static Optional<LocalModInfo> readQuilt(ZipFile zip) {
        Optional<Json> parsed = readJson(zip, "quilt.mod.json");
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Json loaderNode = parsed.get().get("quilt_loader");
        String modId = loaderNode.get("id").asString(null);
        if (modId == null) {
            return Optional.empty();
        }
        Json metadata = loaderNode.get("metadata");
        Json contact = metadata.get("contact");
        return Optional.of(new LocalModInfo(
                modId,
                metadata.get("name").asString(null),
                loaderNode.get("version").asString(null),
                trim(metadata.get("description").asString(null)),
                namesOf(metadata.get("contributors"), Json.MISSING),
                firstUrl(contact.get("homepage").asString(null),
                        contact.get("sources").asString(null),
                        contact.get("issues").asString(null)),
                iconPathOf(metadata.get("icon")),
                LoaderType.QUILT));
    }

    // ---------------------------------------------------------------- forge

    /**
     * {@code mods.toml} and its NeoForge twin.
     *
     * <p>The file is TOML, and the launcher has no TOML parser and does not need
     * one: what is wanted is the first {@code [[mods]]} table, and every key in
     * it is a bare {@code key = "value"} or {@code key = '''block'''}. See
     * {@link Toml} for what is and is not understood.
     *
     * <p>The name comes from the mod table and the links from the file level
     * above it, which is where Forge puts {@code issueTrackerURL} - a detail
     * worth stating, because looking for it inside the table finds nothing and
     * looks exactly like a mod that published no link at all.
     */
    private static Optional<LocalModInfo> readModsToml(ZipFile zip, String path, LoaderType loader) {
        Optional<String> text = readText(zip, path);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Toml toml = Toml.parse(text.get());
        Map<String, String> mod = toml.firstOf("mods");
        String modId = mod.get("modId");
        if (blank(modId)) {
            return Optional.empty();
        }
        String version = mod.get("version");
        // Forge writes ${file.jarVersion} here and fills it in from the manifest
        // at load time. Left alone it would be shown to the user literally.
        if (version != null && version.contains("${")) {
            version = firstNonBlank(manifest(zip).get("implementation-version"),
                    manifest(zip).get("specification-version"));
        }
        String credits = mod.get("authors") == null ? toml.root().get("authors") : mod.get("authors");
        List<String> authors = new ArrayList<>();
        if (!blank(credits)) {
            for (String author : credits.split("[,;]")) {
                if (!author.isBlank()) {
                    authors.add(author.trim());
                }
            }
        }
        return Optional.of(new LocalModInfo(
                modId,
                mod.get("displayName"),
                version,
                trim(mod.get("description")),
                authors,
                firstUrl(mod.get("displayURL"), toml.root().get("displayURL"),
                        toml.root().get("issueTrackerURL")),
                mod.get("logoFile") == null ? toml.root().get("logoFile") : mod.get("logoFile"),
                loader));
    }

    /** {@code mcmod.info} - a JSON array of mods, for Forge 1.12 and older. */
    private static Optional<LocalModInfo> readMcmodInfo(ZipFile zip) {
        Optional<Json> parsed = readJson(zip, "mcmod.info");
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Json root = parsed.get();
        // Two shapes exist: a bare array, and an object with a "modList" array.
        Json first = (root.isArray() ? root : root.get("modList")).get(0);
        String modId = first.get("modid").asString(null);
        if (modId == null) {
            return Optional.empty();
        }
        return Optional.of(new LocalModInfo(
                modId,
                first.get("name").asString(null),
                first.get("version").asString(null),
                trim(first.get("description").asString(null)),
                namesOf(first.get("authorList"), first.get("authors")),
                firstUrl(first.get("url").asString(null), first.get("updateUrl").asString(null)),
                first.get("logoFile").asString(null),
                LoaderType.FORGE));
    }

    /**
     * The jar manifest, for a jar with no descriptor at all.
     *
     * <p>Worth doing because a shaded library or a coremod still names itself in
     * {@code Implementation-Title} and {@code Implementation-Version}, and a row
     * reading "Fabric API 0.100.1" beats a row reading the file name.
     */
    private static Optional<LocalModInfo> readManifest(ZipFile zip) {
        Map<String, String> manifest = manifest(zip);
        String name = firstNonBlank(manifest.get("implementation-title"),
                manifest.get("specification-title"), manifest.get("bundle-name"));
        String version = firstNonBlank(manifest.get("implementation-version"),
                manifest.get("specification-version"), manifest.get("bundle-version"));
        if (name == null && version == null) {
            return Optional.empty();
        }
        List<String> authors = new ArrayList<>();
        String vendor = firstNonBlank(manifest.get("implementation-vendor"),
                manifest.get("specification-vendor"));
        if (vendor != null) {
            authors.add(vendor);
        }
        return Optional.of(new LocalModInfo(null, name, version, null, authors,
                null, null, LoaderType.VANILLA));
    }

    /** The manifest's main section, keys lower-cased. */
    private static Map<String, String> manifest(ZipFile zip) {
        Map<String, String> values = new LinkedHashMap<>();
        Optional<String> text = readText(zip, "META-INF/MANIFEST.MF");
        if (text.isEmpty()) {
            return values;
        }
        for (String line : text.get().split("\r\n|\n|\r")) {
            // A blank line ends the main section; everything after it describes
            // individual entries and is not about the jar as a whole.
            if (line.isBlank()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            values.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        return values;
    }

    // ---------------------------------------------------------------- helpers

    private static Optional<Json> readJson(ZipFile zip, String path) {
        return readText(zip, path).flatMap(text -> {
            try {
                return Optional.of(Json.parse(text));
            } catch (RuntimeException e) {
                // Some published descriptors contain comments or trailing commas.
                // They are the mod's problem, not a reason to show nothing.
                return Optional.empty();
            }
        });
    }

    private static Optional<String> readText(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_DESCRIPTOR_BYTES) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return Optional.of(new String(in.readNBytes(MAX_DESCRIPTOR_BYTES), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Author names out of one or two collections of names.
     *
     * <p>Three shapes, all of them published today: an array of strings, an
     * array of objects with a {@code name}, and - Quilt's {@code contributors} -
     * an object whose keys are the names and whose values are their roles.
     */
    private static List<String> namesOf(Json first, Json second) {
        Set<String> names = new LinkedHashSet<>();
        for (Json source : List.of(first, second)) {
            for (Json entry : source.elements()) {
                String name = entry.isString() ? entry.asString(null) : entry.get("name").asString(null);
                if (!blank(name)) {
                    names.add(name.trim());
                }
            }
            source.fields().forEach((key, value) -> {
                if (!key.isBlank()) {
                    names.add(key.trim());
                }
            });
        }
        return List.copyOf(names);
    }

    /**
     * The icon path, from either shape Fabric allows.
     *
     * <p>A string is a path. An object maps a pixel size to a path, and the
     * largest is taken: the list draws at 40 logical pixels, which is 80 on a
     * scaled display, so downscaling the biggest is what looks right.
     */
    private static String iconPathOf(Json icon) {
        if (icon.isString()) {
            return icon.asString(null);
        }
        String best = null;
        int bestSize = -1;
        for (Map.Entry<String, Json> entry : icon.fields().entrySet()) {
            int size;
            try {
                size = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            if (size > bestSize) {
                bestSize = size;
                best = entry.getValue().asString(null);
            }
        }
        return best;
    }

    /**
     * The first of these that is a web page.
     *
     * <p>Filtered rather than taken as given: these strings come out of an
     * untrusted archive and end up behind a link the user is invited to click,
     * so anything that is not plain {@code http(s)} is discarded here rather
     * than handed to the system browser - which would happily open a
     * {@code file:} one, and on some desktops worse.
     */
    private static String firstUrl(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String url = candidate.trim();
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return url;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /** One line, and not an essay: descriptions are shown in a list row. */
    private static String trim(String description) {
        if (description == null) {
            return null;
        }
        String single = description.replaceAll("\\s+", " ").trim();
        if (single.isEmpty()) {
            return null;
        }
        return single.length() <= MAX_DESCRIPTION
                ? single
                : single.substring(0, MAX_DESCRIPTION - 1).trim() + "…";
    }

    /**
     * As much TOML as a {@code mods.toml} file needs, and no more.
     *
     * <p>Understood: {@code key = "value"}, {@code key = 'value'},
     * {@code key = """block"""} and {@code '''block'''}, {@code [table]},
     * {@code [[array of tables]]}, and {@code #} comments outside strings.
     *
     * <p>Not understood, and deliberately: numbers, booleans, dates, inline
     * tables, arrays and dotted keys. Every one of them would be returned as the
     * text it was written as, which for the four string fields this reader wants
     * is either correct or unused. A general TOML parser is several hundred
     * lines to reach the same four fields.
     */
    static final class Toml {

        private final Map<String, String> root;
        private final Map<String, List<Map<String, String>>> tableArrays;

        private Toml(Map<String, String> root, Map<String, List<Map<String, String>>> tableArrays) {
            this.root = root;
            this.tableArrays = tableArrays;
        }

        Map<String, String> root() {
            return root;
        }

        /** The first table of an array of tables, or an empty map. */
        Map<String, String> firstOf(String name) {
            List<Map<String, String>> tables = tableArrays.get(name);
            return tables == null || tables.isEmpty() ? Map.of() : tables.get(0);
        }

        static Toml parse(String text) {
            Map<String, String> root = new LinkedHashMap<>();
            Map<String, List<Map<String, String>>> arrays = new LinkedHashMap<>();
            Map<String, String> current = root;

            String[] lines = text.split("\r\n|\n|\r");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[[") && line.endsWith("]]")) {
                    Map<String, String> table = new LinkedHashMap<>();
                    arrays.computeIfAbsent(line.substring(2, line.length() - 2).trim(),
                            key -> new ArrayList<>()).add(table);
                    current = table;
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    // A plain table - [dependencies.sodium] and the like. Its
                    // keys are not what this reader is after, so they go
                    // somewhere they cannot be mistaken for the file-level ones.
                    current = new LinkedHashMap<>();
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String raw = line.substring(equals + 1).trim();

                String block = blockDelimiter(raw);
                if (block == null) {
                    current.put(key, unquote(raw));
                    continue;
                }
                StringBuilder value = new StringBuilder(raw.substring(block.length()));
                int end = value.indexOf(block);
                while (end < 0 && i + 1 < lines.length) {
                    value.append('\n').append(lines[++i]);
                    end = value.indexOf(block);
                }
                current.put(key, (end < 0 ? value.toString() : value.substring(0, end)).trim());
            }
            return new Toml(root, arrays);
        }

        /** The opening delimiter of a multi-line string, or null. */
        private static String blockDelimiter(String raw) {
            if (raw.startsWith("'''")) {
                return "'''";
            }
            return raw.startsWith("\"\"\"") ? "\"\"\"" : null;
        }

        private static String unquote(String raw) {
            if (raw.isEmpty()) {
                return raw;
            }
            char quote = raw.charAt(0);
            if (quote != '"' && quote != '\'') {
                // A comment after an unquoted value. Only safe to cut here,
                // where there are no quotes for a # to be hiding inside.
                int hash = raw.indexOf('#');
                return (hash < 0 ? raw : raw.substring(0, hash)).trim();
            }
            int close = raw.indexOf(quote, 1);
            if (close < 0) {
                return raw.substring(1);
            }
            String inner = raw.substring(1, close);
            return quote == '"' ? inner.replace("\\\"", "\"").replace("\\\\", "\\") : inner;
        }
    }
}
