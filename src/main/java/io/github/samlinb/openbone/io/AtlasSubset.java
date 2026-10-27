package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;

/**
 * CPU-only atlas metadata inspection. This subset admits one full untrimmed PNG
 * per region.
 */
final class AtlasSubset {
    private AtlasSubset() {
    }

    record Region(Path path, io.github.samlinb.openbone.core.model.AtlasRegion metadata) {
    }

    static Map<String, Region> read(Path json) throws IOException {
        return read(json, false, new ArrayList<>());
    }

    static Map<String, Region> read(Path json, boolean legacy, List<String> warnings) throws IOException {
        Path atlas = json.resolveSibling(Spine40Binary.stem(json) + ".atlas");
        if (!Files.exists(atlas)) atlas = json.resolveSibling(Spine40Binary.stem(json) + ".atlas.txt");
        if (!Files.exists(atlas))
            return null;
        String atlasText = new String(ResourcePaths.bytes(atlas, ProjectCodec.MAX_BYTES),
                java.nio.charset.StandardCharsets.UTF_8);
        // An exact zero-byte atlas is the product's explicit no-raster package.
        // Decode still requires every textured attachment to find a region in this
        // empty map. Whitespace-only and malformed nonempty atlases stay strict.
        if (atlasText.isEmpty())
            return Map.of();
        if (legacy) {
            String adjusted = atlasText.replaceAll("(?m)^(\\s*filter:\\s*)Linear\\s*,\\s*Linear\\s*$", "$1Nearest,Nearest");
            if (!adjusted.equals(atlasText)) warnings.add(io.github.samlinb.openbone.i18n.M.m("spineImport.nearestFilter"));
            atlasText = adjusted;
        }
        validateSyntax(atlasText, atlas.getFileName().toString());
        final String validatedText = atlasText;
        try {
            // Parse the exact validated text, not a second disk read that may have changed.
            var validated = new FileHandle(atlas.toFile()) {
                @Override
                public BufferedReader reader(int bufferSize) {
                    return new BufferedReader(new StringReader(validatedText), bufferSize);
                }
            };
            var data = new TextureAtlasData(validated, new FileHandle(atlas.getParent().toFile()), false);
            Map<String, Region> result = new LinkedHashMap<>();
            Set<Object> used = new HashSet<>();
            for (var page : data.getPages()) {
                Path path = page.textureFile.file().toPath().toAbsolutePath().normalize();
                ResourcePaths.contained(atlas.getParent(),
                        atlas.getParent().toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/'));
                if (page.format != com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888
                        || page.minFilter != TextureFilter.Nearest || page.magFilter != TextureFilter.Nearest
                        || page.uWrap != TextureWrap.ClampToEdge || page.vWrap != TextureWrap.ClampToEdge)
                    throw new IOException(
                            io.github.samlinb.openbone.i18n.M.m("atlas.unsupportedPageSettings"));
                if (legacy && !Files.exists(path)) {
                    warnings.add("atlas: missing page; region metadata retained: " + path);
                    continue;
                }
                var pixels = io.github.samlinb.openbone.viewport.ImageAssets.decodePng(
                        legacy ? RuntimePng.readCompatible(path) : RuntimePng.read(path));
                if (page.width != pixels.getWidth() || page.height != pixels.getHeight()) {
                    if (!legacy) throw new IOException("Atlas page dimensions mismatch");
                    // UVs are normalized in the declared atlas coordinate space. Platform
                    // texture downscaling changes texel dimensions, not those UV ratios.
                    warnings.add(io.github.samlinb.openbone.i18n.M.m("atlas.platformDimensions", path,
                            (int)page.width, (int)page.height, pixels.getWidth(), pixels.getHeight()));
                    if (pixels.getWidth()==1 && pixels.getHeight()==1)
                        warnings.add(io.github.samlinb.openbone.i18n.M.m("atlas.placeholderPage", path));
                }
            }
            for (var r : data.getRegions()) {
                if (r.degrees != 0 && r.degrees != 90 && r.degrees != 180 && r.degrees != 270 || r.index != -1 || r.flip || r.names != null)
                    throw new IOException("Unsupported atlas rotation/index/custom fields: " + r.name);
                io.github.samlinb.openbone.core.model.AtlasRegion metadata;
                try {
                    metadata = new io.github.samlinb.openbone.core.model.AtlasRegion((int) r.page.width,
                            (int) r.page.height, r.left, r.top, r.width, r.height, r.originalWidth, r.originalHeight,
                            (int) r.offsetX, (int) r.offsetY, r.degrees == 90 || r.degrees == 270, r.page.pma, r.degrees);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid atlas region: " + r.name, invalid);
                }
                boolean full = !metadata.premultiplied() && metadata.degrees() == 0 && metadata.x() == 0 && metadata.y() == 0 && metadata.offsetX() == 0
                        && metadata.offsetY() == 0 && metadata.width() == metadata.pageWidth()
                        && metadata.height() == metadata.pageHeight() && metadata.width() == metadata.originalWidth()
                        && metadata.height() == metadata.originalHeight();
                used.add(r.page);
                if (result.putIfAbsent(r.name,
                        new Region(r.page.textureFile.file().toPath().toAbsolutePath().normalize(),
                                full ? null : metadata)) != null)
                    throw new IOException("Duplicate atlas region");
            }
            if (used.size() != data.getPages().size)
                throw new IOException("Atlas contains unused pages");
            return result;
        } catch (com.badlogic.gdx.utils.GdxRuntimeException e) {
            throw new IOException("Invalid atlas: " + e.getMessage(), e);
        }
    }

    private static final Map<String, Integer> PAGE = Map.of("size", 2, "format", 1, "filter", 2, "repeat", 1, "pma", 1, "scale", 1);
    private static final Map<String, Integer> REGION = Map.of("rotate", 1, "xy", 2, "size", 2, "orig", 2, "offset", 2,
            "index", 1, "bounds", 4, "offsets", 4);

    private record Entry(String key, String value, int line) {
    }

    private static final class Scope {
        final String path;
        final boolean page;
        final Map<String, Entry> fields = new LinkedHashMap<>();

        Scope(String path, boolean page) {
            this.path = path;
            this.page = page;
        }
    }

    /**
     * GCe/GDX syntax: blank lines start pages, name lines start regions;
     * indentation is optional.
     * GDX intentionally ignores unknown page fields and overwrites duplicates.
     * Those policies
     * are unsuitable for editable strict import, so scopes are checked before GDX
     * sees them.
     */
    static void validateSyntax(String text, String filename) throws IOException {
        String page = null;
        Scope scope = null;
        boolean hasRegion = false;
        int lineNumber = 0;
        Set<String> pages = new HashSet<>();
        // BufferedReader uses exactly GDX's CR/LF line boundaries, rather than Java's
        // broader \\R.
        try (var reader = new BufferedReader(new StringReader(text))) {
            for (String raw; (raw = reader.readLine()) != null;) {
                lineNumber++;
                String line = raw.trim();
                if (line.isEmpty()) {
                    validateFields(scope, filename);
                    if (page != null && !hasRegion)
                        throw bad(filename, lineNumber, scope.path, "Page has no region");
                    page = null;
                    scope = null;
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon >= 0) {
                    String key = line.substring(0, colon).trim();
                    if (scope == null)
                        throw bad(filename, lineNumber, "$atlas.header." + key, "Field before page name");
                    Map<String, Integer> allowed = scope.page ? PAGE : REGION;
                    if (!allowed.containsKey(key))
                        throw bad(filename, lineNumber, scope.path + "." + key,
                                "Unsupported atlas field in " + (scope.page ? "page" : "region"));
                    if (scope.fields.putIfAbsent(key, new Entry(key, line.substring(colon + 1), lineNumber)) != null)
                        throw bad(filename, lineNumber, scope.path + "." + key, "Duplicate atlas field");
                    // These distinct spellings write the same GDX components. Reject even
                    // equal values, so neither field order nor last-write wins loses data.
                    if (!scope.page) {
                        for (String[] pair : new String[][] { { "bounds", "xy" }, { "bounds", "size" },
                                { "offsets", "offset" }, { "offsets", "orig" } })
                            if (scope.fields.containsKey(pair[0]) && scope.fields.containsKey(pair[1]))
                                throw bad(filename, lineNumber, scope.path + "." + key,
                                        "Conflicting atlas fields " + pair[0] + " / " + pair[1]);
                    }
                    continue;
                }
                validateFields(scope, filename);
                if (page == null) {
                    page = raw;
                    hasRegion = false;
                    if (!pages.add(page))
                        throw bad(filename, lineNumber, "$atlas.page[" + page + "]", "Duplicate atlas page");
                    scope = new Scope("$atlas.page[" + page + "]", true);
                } else {
                    hasRegion = true;
                    scope = new Scope("$atlas.page[" + page + "].region[" + line + "]", false);
                }
            }
        }
        validateFields(scope, filename);
        if (page != null && !hasRegion)
            throw bad(filename, lineNumber, scope.path, "Page has no region");
        if (pages.isEmpty())
            throw bad(filename, lineNumber, "$atlas", "Atlas has no pages");
    }

    private static void validateFields(Scope scope, String filename) throws IOException {
        if (scope == null)
            return;
        for (Entry entry : scope.fields.values()) {
            String path = scope.path + "." + entry.key();
            String[] values = entry.value().split(",", -1);
            int count = (scope.page ? PAGE : REGION).get(entry.key());
            if (values.length != count)
                throw bad(filename, entry.line(), path, "Expected " + count + " values; got " + values.length);
            for (int i = 0; i < values.length; i++) {
                values[i] = values[i].trim();
                if (values[i].isEmpty())
                    throw bad(filename, entry.line(), path, "Empty atlas value");
            }
            String key = entry.key();
            if (scope.page && key.equals("scale")) {
                // Spine's atlas reader consumes but does not apply this packer hint.
                // Packed region coordinates already include the texture packer's scale.
                try {
                    double scale = Double.parseDouble(values[0]);
                    if (!Double.isFinite(scale) || scale <= 0) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    throw bad(filename, entry.line(), path, io.github.samlinb.openbone.i18n.M.m("atlas.scaleFinitePositive"));
                }
            } else if (scope.page && key.equals("pma")) {
                if (!values[0].equals("true") && !values[0].equals("false"))
                    throw bad(filename, entry.line(), path, io.github.samlinb.openbone.i18n.M.m("atlas.pmaBoolean"));
            } else if (scope.page && Set.of("format", "filter", "repeat").contains(key)) {
                String required = switch (key) {
                    case "format" -> "RGBA8888";
                    case "filter" -> "Nearest";
                    case "repeat" -> "none";
                    default -> "false";
                };
                for (String value : values)
                    if (!value.equals(required))
                        throw bad(filename, entry.line(), path,
                                "Unsupported atlas value: " + value + "; requires " + required);
            } else {
                for (String value : values) {
                    if (key.equals("rotate") && (value.equals("false") || value.equals("true")))
                        continue;
                    try {
                        int parsed = Integer.parseInt(value);
                        if (key.equals("rotate") && parsed != 0 && parsed != 90 && parsed != 180 && parsed != 270 || key.equals("index") && parsed != -1)
                            throw bad(filename, entry.line(), path, "Unsupported atlas value: " + value);
                    } catch (NumberFormatException e) {
                        throw bad(filename, entry.line(), path, "Invalid atlas integer: " + value);
                    }
                }
            }
        }
    }

    private static IOException bad(String filename, int line, String path, String message) {
        return new IOException(filename + ":" + line + " " + path + ": " + message);
    }
}
