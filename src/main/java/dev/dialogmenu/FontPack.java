package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import static dev.dialogmenu.MenuModel.*;

/** Reusable whole-sprite glyphs and baseline-shifted, original Unicode character glyphs. */
final class FontPack {
    record Glyph(int code, int top, int height, int advance, String texture, BufferedImage image) {}
    private record TextAtlas(int sourceHeight, List<String> chars, String texture, BufferedImage image) {}
    private final Map<String, Glyph> sprites = new LinkedHashMap<>();
    private final Map<Integer, Integer> advances = new TreeMap<>();
    private final Map<Integer, int[]> metrics = new HashMap<>();
    private final List<TextAtlas> text = new ArrayList<>();
    private record TextFont(int top, int size) {}
    private final Map<String, TextFont> textFonts = new TreeMap<>();
    private final Map<BufferedImage, String> imageKeys = new WeakHashMap<>();
    private final Gson json = new Gson();

    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, "");
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, List.of(1.0));
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales) throws IOException {
        FontPack pack = new FontPack();
        Set<Integer> characters = new TreeSet<>();
        for (int cp = 33; cp < 127; cp++) characters.add(cp);
        extraCharacters.codePoints().filter(cp -> cp != ' ' && cp != '\n').forEach(characters::add);
        for (var doc : docs.stream().sorted(Comparator.comparing(d -> d.menu().id())).toList()) {
            Context active = MenuTemplate.preview(doc, context);
            for (Node node : doc.menu().nodes()) if (node.type().equals("text"))
                for (var letter : MenuText.glyphs(MenuText.parse(node.text() + active.expand(node.text()))))
                    if (letter.code() != ' ' && letter.code() != '\n') characters.add(letter.code());
            for (int width : new TreeSet<>(widths)) {
                Rendered frame = renderer.draw(doc.menu(), active, width, maxPixels);
                for (Sprite sprite : frame.sprites()) {
                    try { pack.register(sprite); }
                    catch (IOException error) { throw new IOException(doc.menu().id()+"/"+width+": "+error.getMessage(), error); }
                }
                for (Region region : doc.menu().regions()) {
                    Sprite hit = MenuRenderer.hitSprite(doc.menu(), frame, region);
                    if (hit != null) pack.register(hit);
                }
                for (Node node : frame.textNodes()) {
                    for (double scale : textScales) {
                        int size = SpriteCanvas.textSize(doc.menu(), node, width, scale);
                        // Unwrapped dynamic text may add explicit newlines up to the canvas edge.
                        int count = node.maxLines() > 0 ? node.maxLines() : frame.image().getHeight() / (size + 1) + 1;
                        for (int line = 0; line < count; line++) {
                            int top = SpriteCanvas.textTop(doc.menu(), node, width, line, size);
                            if (top >= 0 && top + size <= frame.image().getHeight())
                                pack.textFonts.put(fontName(top, size), new TextFont(top, size));
                        }
                    }
                }
            }
        }
        pack.characters(characters, renderer.assets);
        return pack;
    }
    private String key(BufferedImage image) {
        return imageKeys.computeIfAbsent(image, FontPack::hash);
    }
    private static String hash(BufferedImage image) {
        try {
            ByteBuffer bytes = ByteBuffer.allocate(8 + image.getWidth() * image.getHeight() * 4);
            bytes.putInt(image.getWidth()).putInt(image.getHeight());
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                bytes.putInt(image.getRGB(x, y));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void register(Sprite sprite) throws IOException {
        if (sprite.texture() != null) {
            String key = spriteKey(sprite);
            if (!sprites.containsKey(key)) {
                int height = sprite.image().getHeight();
                BufferedImage image = sprite.original();
                int advance = bitmapAdvance(image, 0, 0, image.getWidth(), image.getHeight(), height);
                sprites.put(key, new Glyph(0xE000 + sprites.size(), sprite.y(), height,
                    advance, sprite.texture(), null));
            }
            return;
        }
        BufferedImage source = sprite.image();
        String hash = key(source), key = hash + ":" + sprite.y();
        if (sprites.containsKey(key)) return;
        if (source.getHeight() < 8) {
            BufferedImage padded = new BufferedImage(source.getWidth(), 8, BufferedImage.TYPE_INT_ARGB);
            Graphics2D padding = padded.createGraphics();
            padding.drawImage(source, 0, 0, null);
            padding.dispose();
            source = padded;
        }
        int divisor = 1;
        while (source.getWidth() / divisor > 256 || source.getHeight() / divisor > 256
            || source.getWidth() % divisor != 0 || source.getHeight() % divisor != 0) {
            divisor++;
            if (divisor > Math.min(source.getWidth(), source.getHeight()))
                throw new IOException(sprite.id() + " 单图无法在256内保持原比例，请调整该元素尺寸");
        }
        BufferedImage image = new BufferedImage(source.getWidth() / divisor, source.getHeight() / divisor,
            BufferedImage.TYPE_INT_ARGB);
        // Preserve thin lines when a complete, oversized graphic is reduced to one <=256 texture.
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int py = 0; py < divisor; py++) for (int px = 0; px < divisor; px++) {
                int color = source.getRGB(x * divisor + px, y * divisor + py), alpha = color >>> 24;
                a += alpha;
                r += (color >> 16 & 255) * alpha;
                g += (color >> 8 & 255) * alpha;
                b += (color & 255) * alpha;
            }
            if (a > 0) image.setRGB(x, y, (int) ((a + divisor * divisor / 2) / (divisor * divisor)) << 24
                | (int) ((r + a / 2) / a) << 16 | (int) ((g + a / 2) / a) << 8 | (int) ((b + a / 2) / a));
        }
        int advance = bitmapAdvance(image, 0, 0, image.getWidth(), image.getHeight(), source.getHeight());
        sprites.put(key, new Glyph(0xE000 + sprites.size(), sprite.y(), source.getHeight(), advance,
            "generated/sprite_" + hash + ".png", image));
    }
    Glyph sprite(Sprite sprite) {
        Glyph glyph = sprites.get(spriteKey(sprite));
        if (glyph == null) throw new IllegalArgumentException("图形已变化，请重新生成并接收字体资源包");
        return glyph;
    }
    private String spriteKey(Sprite sprite) {
        return (sprite.texture() == null ? key(sprite.image())
            : sprite.texture() + ":" + sprite.image().getHeight()) + ":" + sprite.y();
    }
    private void characters(Set<Integer> characters, VanillaAssets assets) throws IOException {
        Map<Integer, List<Integer>> byHeight = new TreeMap<>();
        for (int cp : characters) {
            var glyph = assets.glyph(cp);
            if (glyph == null) throw new IOException("原版字体缺少字符 U+" + Integer.toHexString(cp));
            int used = 0;
            for (int y = 0; y < glyph.height(); y++) for (int x = 0; x < glyph.width(); x++)
                if (glyph.alpha()[y * glyph.width() + x] != 0) used = Math.max(used, x + 1);
            metrics.put(cp, new int[] {used, glyph.height()});
            byHeight.computeIfAbsent(glyph.height(), h -> new ArrayList<>()).add(cp);
        }
        advances.put(32, 4);
        for (var group : byHeight.entrySet()) {
            int h = group.getKey(), cw = group.getValue().stream().mapToInt(cp -> assets.glyph(cp).width()).max().orElseThrow();
            int columns = 256 / cw, capacity = columns * (256 / h);
            for (int start = 0; start < group.getValue().size(); start += capacity) {
                List<Integer> chunk = group.getValue().subList(start, Math.min(start + capacity, group.getValue().size()));
                int rowCount = (chunk.size() + columns - 1) / columns;
                BufferedImage image = new BufferedImage(columns * cw, rowCount * h, BufferedImage.TYPE_INT_ARGB);
                List<String> rows = new ArrayList<>();
                for (int y = 0; y < rowCount; y++) {
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < columns; x++) {
                        int index = y * columns + x;
                        if (index >= chunk.size()) { row.append('\0'); continue; }
                        int cp = chunk.get(index);
                        var glyph = assets.glyph(cp);
                        for (int py = 0; py < h; py++) for (int px = 0; px < glyph.width(); px++)
                            image.setRGB(x * cw + px, y * h + py,
                                Byte.toUnsignedInt(glyph.alpha()[py * glyph.width() + px]) << 24 | 0xFFFFFF);
                        row.appendCodePoint(cp);
                        advances.put(cp, bitmapAdvance(image, x * cw, y * h, cw, h, 8));
                    }
                    rows.add(row.toString());
                }
                text.add(new TextAtlas(h, List.copyOf(rows), "generated/text_" + h + "_" + start + ".png", image));
            }
        }
    }
    private static int bitmapAdvance(BufferedImage image, int ox, int oy, int width, int height, int displayHeight) {
        int used = 0;
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
            if (image.getRGB(ox + x, oy + y) >>> 24 != 0) used = Math.max(used, x + 1);
        return (int) (.5 + used * (displayHeight / (float) height)) + 1;
    }
    int textWidth(String value) { return textWidth(MenuText.parse(value), 8); }
    int textWidth(String value, int size) { return textWidth(MenuText.parse(value), size); }
    int textWidth(net.kyori.adventure.text.Component value, int size) {
        return MenuText.glyphs(value).stream().mapToInt(glyph -> glyphWidth(glyph, size)).sum();
    }
    int glyphWidth(MenuText.Glyph glyph, int size) {
        int[] metric = metrics.get(glyph.code());
        if (metric == null && glyph.code() != 32)
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(glyph.code()));
        int advance = glyph.code() == 32 ? (int) Math.round(size / 2.0)
            : (int) (.5 + metric[0] * (size / (float) metric[1])) + 1;
        return advance + (glyph.bold() ? 1 : 0);
    }
    net.kyori.adventure.key.Key textFont(int top, int size) {
        String name = fontName(top, size);
        if (!textFonts.containsKey(name))
            throw new IllegalArgumentException("文字位置已变化，请重新生成字体资源：" + name);
        return net.kyori.adventure.key.Key.key("dialogmenu", name);
    }
    void write(Path target) throws IOException {
        Path base = target.resolve("assets/dialogmenu");
        if (Files.exists(base)) try (var paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.createDirectories(base.resolve("font"));
        Files.createDirectories(base.resolve("textures/generated"));
        for (Glyph glyph : sprites.values()) if (glyph.image() != null)
            ImageIO.write(glyph.image(), "png", base.resolve("textures/" + glyph.texture()).toFile());
        for (TextAtlas atlas : text) ImageIO.write(atlas.image(), "png", base.resolve("textures/" + atlas.texture()).toFile());
        Map<String, Integer> spaces = new LinkedHashMap<>();
        for (int x = -1024; x <= 1024; x++) spaces.put(SpriteCanvas.move(x), x);
        writeJson(base.resolve("font/space.json"), Map.of("providers", List.of(Map.of("type", "space", "advances", spaces))));
        List<Object> graphics = new ArrayList<>();
        for (Glyph glyph : sprites.values()) graphics.add(bitmap(glyph.texture(), glyph.height(), 7 - glyph.top(),
            List.of(Character.toString(glyph.code()))));
        writeJson(base.resolve("font/sprites.json"), Map.of("providers", graphics));
        for (var entry : textFonts.entrySet()) {
            TextFont font = entry.getValue();
            List<Object> characters = new ArrayList<>();
            for (TextAtlas atlas : text) characters.add(bitmap(atlas.texture(), font.size(), 7 - font.top(), atlas.chars()));
            characters.add(Map.of("type", "space", "advances", Map.of(" ", (int) Math.round(font.size() / 2.0))));
            writeJson(base.resolve("font/" + entry.getKey() + ".json"), Map.of("providers", characters));
        }
        writeJson(target.resolve("pack.mcmeta"), Map.of("pack", Map.of("description", "DialogMenu whole sprites",
            "min_format", List.of(88, 0), "max_format", List.of(88, 0))));
    }
    private static Object bitmap(String texture, int height, int ascent, List<String> chars) {
        return Map.of("type", "bitmap", "file", texture.contains(":") ? texture : "dialogmenu:" + texture,
            "height", height, "ascent", ascent, "chars", chars);
    }
    static String fontName(int top, int size) { return "text_" + top + (size == 8 ? "" : "_" + size); }
    private void writeJson(Path path, Object value) throws IOException { Files.writeString(path, json.toJson(value) + "\n"); }
    int spriteCount() { return sprites.size(); }
}
