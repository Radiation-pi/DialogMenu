package dev.dialogmenu;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads CraftEngine source resources without depending on its Java API or
 * copying generated pack output. YAML source URIs:
 * craftengine:item/<namespace:item-id>
 * craftengine:image/<namespace:image-id>
 * craftengine:icon/<namespace:image-id>
 * craftengine:texture/<namespace:item/path>
 */
final class CraftEngineAssets {
    private final Path resources;
    private final Map<String, Path> itemTextures = new LinkedHashMap<>();
    private final Map<String, Path> registeredImages = new LinkedHashMap<>();
    private final Map<String, String> registeredFiles = new LinkedHashMap<>();
    private final Map<String, BufferedImage> cache = new HashMap<>();
    private final Map<String, Reference> references = new HashMap<>();
    private final Set<Path> referencedTextures = new HashSet<>();

    CraftEngineAssets(Path resources) throws IOException {
        this.resources = resources == null ? null : resources.toAbsolutePath().normalize();
        if (this.resources != null && Files.isDirectory(this.resources)) discover();
    }

    BufferedImage image(String source) throws IOException {
        String key = source.toLowerCase(Locale.ROOT);
        if (key.startsWith("craftengine:item/")) {
            String item = source.substring("craftengine:item/".length());
            Path texture = itemTextures.get(item);
            if (texture == null) throw new IOException("Unknown CraftEngine item icon: " + item);
            return load(texture, source);
        }
        if (key.startsWith("craftengine:image/") || key.startsWith("craftengine:icon/")) {
            String id = source.substring(source.indexOf('/') + 1);
            Path texture = registeredImages.get(id);
            if (texture == null && !registeredImages.containsKey(id))
                throw new IOException("Unknown CraftEngine image: " + id);
            if (texture == null) return null;
            return load(texture, source);
        }
        if (key.startsWith("craftengine:texture/")) {
            String texture = source.substring("craftengine:texture/".length());
            Path path = texturePath(texture);
            if (path == null) throw new IOException("Unknown CraftEngine texture: " + texture);
            return load(path, source);
        }
        throw new IOException("Unknown CraftEngine source: " + source);
    }

    int itemCount() { return itemTextures.size(); }
    int imageCount() { return registeredImages.size(); }
    boolean hasItem(String id) { return itemTextures.containsKey(id); }
    Set<String> imageIds() { return Collections.unmodifiableSet(registeredImages.keySet()); }
    String imageFile(String id) { return registeredFiles.get(id); }
    Set<Path> dependencies() {
        Set<Path> result = new HashSet<>(registeredImages.values());
        result.addAll(itemTextures.values());
        result.addAll(referencedTextures);
        result.remove(null);
        return result;
    }

    record Reference(String texture, BufferedImage image) {}
    Reference reference(String source) throws IOException {
        Reference cached = references.get(source);
        if (cached != null) return cached;
        Path path;
        if (source.startsWith("craftengine:texture/"))
            path = texturePath(source.substring("craftengine:texture/".length()));
        else if (source.startsWith("craftengine:item/"))
            path = itemTextures.get(source.substring("craftengine:item/".length()));
        else if (source.startsWith("craftengine:image/") || source.startsWith("craftengine:icon/"))
            path = registeredImages.get(source.substring(source.indexOf('/') + 1));
        else throw new IOException("reuse-texture须使用craftengine:texture/item/image来源");
        if (path == null) throw new IOException("CE中找不到纹理：" + source);
        String relative = path.toString().replace('\\', '/').split("/resourcepack/assets/", 2)[1];
        int textures = relative.indexOf("/textures/");
        String key = relative.substring(0, textures) + ":" + relative.substring(textures + 10);
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("CE纹理不是PNG：" + source);
        referencedTextures.add(path);
        Reference result = new Reference(key, image);
        references.put(source, result);
        return result;
    }

    private void discover() throws IOException {
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml"))
                    .filter(p -> p.toString().contains(File.separator + "configuration" + File.separator))
                    .sorted().toList()) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception error) {
                    throw new IOException("Cannot read CraftEngine YAML: " + file, error);
                }
                ConfigurationSection items = yaml.getConfigurationSection("items");
                if (items != null) for (String id : items.getKeys(false)) {
                    ConfigurationSection item = items.getConfigurationSection(id);
                    if (item == null) continue;
                    String model = item.getString("model.path", "");
                    if (model.isBlank()) continue;
                    Path texture = texturePath(model, id);
                    if (texture != null) itemTextures.putIfAbsent(id, texture);
                }
                ConfigurationSection images = yaml.getConfigurationSection("images");
                if (images != null) for (String id : images.getKeys(false)) {
                    ConfigurationSection image = images.getConfigurationSection(id);
                    if (image == null) continue;
                    String fileRef = image.getString("file", "");
                    if (fileRef.isBlank()) continue;
                    Path texture = texturePath(fileRef);
                    registeredImages.putIfAbsent(id, texture);
                    registeredFiles.putIfAbsent(id, fileRef);
                }
            }
        }
    }

    private Path texturePath(String model) throws IOException {
        return texturePath(model, "");
    }

    private Path texturePath(String model, String itemId) throws IOException {
        String value = model.replace('\\', '/');
        int colon = value.indexOf(':');
        String namespace;
        String path;
        if (colon <= 0) {
            int itemColon = itemId.indexOf(':');
            if (value.isBlank()) return null;
            namespace = itemColon > 0 ? itemId.substring(0, itemColon) : "minecraft";
            path = itemColon > 0 ? "item/" + value : value;
        } else {
            if (colon == value.length() - 1)
                throw new IOException("CraftEngine path须为namespace:path: " + model);
            namespace = value.substring(0, colon);
            path = value.substring(colon + 1);
        }
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        if (!path.matches("[a-z0-9_./-]+")) return null;
        return findTexture(namespace, path);
    }

    private Path findTexture(String namespace, String path) throws IOException {
        String category = "";
        if (path.startsWith("item/") || path.startsWith("block/")) {
            int slash = path.indexOf('/');
            category = path.substring(0, slash);
            path = path.substring(slash + 1);
        }
        try (Stream<Path> packs = Files.list(resources)) {
            for (Path pack : packs.filter(Files::isDirectory).sorted().toList()) {
                List<String> categories = category.isBlank()
                    ? List.of("item", "block", "") : List.of(category);
                for (String kind : categories) {
                    Path base = pack.resolve("resourcepack/assets").resolve(namespace).resolve("textures");
                    Path candidate = (kind.isBlank() ? base : base.resolve(kind))
                        .resolve(path + ".png").normalize();
                    if (candidate.startsWith(pack.resolve("resourcepack").normalize())
                        && Files.isRegularFile(candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private BufferedImage load(Path path, String source) throws IOException {
        referencedTextures.add(path);
        BufferedImage cachedImage = cache.get(source);
        if (cachedImage != null) return cachedImage;
        BufferedImage input = ImageIO.read(path.toFile());
        if (input == null) throw new IOException("CraftEngine纹理不是PNG：" + path);
        BufferedImage image = normalize(input, source);
        cache.put(source, image);
        return image;
    }

    private static BufferedImage normalize(BufferedImage input, String source) throws IOException {
        int width = input.getWidth(), height = input.getHeight();
        if (width > 256 || height > 256 || width <= 0 || height <= 0)
            throw new IOException("CraftEngine图标超出256像素或尺寸非法：" + source + " " + width + "x" + height);
        if (height > width && height % width == 0) {
            input = input.getSubimage(0, 0, width, width);
            height = width;
        }
        if (width > height || width < 8 || height < 8)
            throw new IOException("CraftEngine图标须为方形或竖向帧：" + source + " " + width + "x" + height);
        int size = Math.max(width, 16);
        if (width != size || height != size) {
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(input, 0, 0, size, size, null);
            graphics.dispose();
            input = scaled;
        }
        if (input.getWidth() % 16 != 0 || input.getHeight() % 16 != 0)
            throw new IOException("CraftEngine图标尺寸须为16的倍数：" + source + " "
                + input.getWidth() + "x" + input.getHeight());
        return input;
    }
}
