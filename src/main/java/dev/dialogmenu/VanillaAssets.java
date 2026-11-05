package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Original vanilla character and icon sources, never whole rendered text strings. */
final class VanillaAssets {
    record Glyph(byte[] alpha, int width, int height, float advance) {}
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<String, BufferedImage> icons = new HashMap<>();
    private final Properties materials = new Properties();
    private final Path images;
    private final Map<String, BufferedImage> custom = new HashMap<>();
    final CraftEngineAssets craftEngine;
    static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA,
        0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    VanillaAssets(Path images) throws IOException {
        this(images, null);
    }

    VanillaAssets(Path images, Path craftEngineResources) throws IOException {
        this.images = images.toAbsolutePath().normalize();
        Files.createDirectories(this.images);
        craftEngine = new CraftEngineAssets(craftEngineResources);
        try (var input = new DataInputStream(new GZIPInputStream(resource("glyphs.bin.gz")))) {
            if (input.readInt() != 0x534D4631) throw new IOException("Unknown glyph source format");
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                int cp = input.readInt(), w = input.readUnsignedShort(), h = input.readUnsignedShort();
                float advance = input.readFloat();
                byte[] alpha = input.readNBytes(w * h);
                if (alpha.length != w * h) throw new EOFException("Incomplete vanilla glyph");
                glyphs.put(cp, new Glyph(alpha, w, h, advance));
            }
        }
        try (var input = new ZipInputStream(resource("vanilla-icons.zip"))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) icons.put(entry.getName().replaceFirst("\\.png$", ""), image);
            }
        }
        try (var input = new InputStreamReader(resource("icons.properties"), StandardCharsets.UTF_8)) {
            materials.load(input);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = VanillaAssets.class.getClassLoader().getResourceAsStream(name);
        if (input == null) throw new FileNotFoundException("Missing built-in asset " + name);
        return input;
    }

    BufferedImage icon(String material) {
        String key = material.toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        return icons.get(materials.getProperty(key, ""));
    }

    BufferedImage image(String source) throws IOException {
        if (source.startsWith("minecraft:")) {
            return vanillaImage(source);
        }
        if (source.startsWith("craftengine:")) {
            BufferedImage image = craftEngine.image(source);
            if (image != null) return image;
            String id = source.substring(source.indexOf('/') + 1);
            String file = craftEngine.imageFile(id);
            if (file != null && (file.startsWith("item/") || file.startsWith("block/")))
                return vanillaImage("minecraft:" + file);
            throw new IOException("Unresolved CraftEngine image: " + source);
        }
        BufferedImage cached = custom.get(source);
        if (cached != null) return cached;
        Path path = images.resolve(source.startsWith("/") ? source.substring(1) : source).normalize();
        if (!path.startsWith(images) || !Files.isRegularFile(path)
            || !path.toRealPath().startsWith(images.toRealPath())) throw new IOException("Image outside images/: " + source);
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null || image.getWidth() < 16 || image.getHeight() < 16
            || image.getWidth() > 256 || image.getHeight() > 256
            || image.getWidth() % 16 != 0 || image.getHeight() % 16 != 0)
            throw new IOException("PNG dimensions must be multiples of 16, within 16..256: " + source);
        custom.put(source, image);
        return image;
    }

    private BufferedImage vanillaImage(String source) throws IOException {
        BufferedImage image = icons.get(source.substring("minecraft:".length()).replaceFirst("\\.png$", ""));
        if (image == null) throw new IOException("Unknown vanilla texture: " + source);
        return image;
    }

    void clearImages() { custom.clear(); }
    Map<String, BufferedImage> imageSnapshot() { return new HashMap<>(custom); }
    void restoreImages(Map<String, BufferedImage> snapshot) { custom.clear(); custom.putAll(snapshot); }
    int glyphCount() { return glyphs.size(); }
    int iconCount() { return materials.size(); }
    Glyph glyph(int codepoint) { return glyphs.get(codepoint); }

    record Letter(int code, int color, boolean bold) {}
    static List<Letter> letters(String text, int initial) {
        List<Letter> result = new ArrayList<>();
        int color = initial;
        boolean bold = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if ((cp == '&' || cp == '§') && i < text.length()) {
                char c = Character.toLowerCase(text.charAt(i));
                if (c == '#' && i + 7 <= text.length()
                    && text.substring(i + 1, i + 7).matches("[0-9a-fA-F]{6}")) {
                    color = (initial & 0xFF000000) | Integer.parseInt(text.substring(i + 1, i + 7), 16);
                    i += 7; bold = false; continue;
                }
                int index = "0123456789abcdef".indexOf(c);
                if (index >= 0) { color = (initial & 0xFF000000) | COLORS[index]; bold = false; i++; continue; }
                if (c == 'l') { bold = true; i++; continue; }
                if (c == 'r') { color = initial; bold = false; i++; continue; }
            }
            result.add(new Letter(cp, color, bold));
        }
        return result;
    }

}
