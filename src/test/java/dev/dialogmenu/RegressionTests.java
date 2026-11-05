package dev.dialogmenu;

import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** Offline behavioral regressions. No Bukkit server or network access is needed. */
public final class RegressionTests {
    private static int checks;
    private static final Context PREVIEW = context("A", 1);
    private static final String DYNAMIC = """
        schema-version: 1
        id: dynamic
        canvas: {width: 300, height: 180}
        frontend:
          label:
            type: text
            content: '{value}'
        """;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path temporary = Files.createTempDirectory(root.resolve("build"), "regression-");
        gestures();
        tokens();
        text();
        rendering(root, temporary);
        cache(temporary);
        reload(root, temporary);
        metadata(root);
        System.out.println("PASS " + checks + " regression checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Runnable action, String contains) {
        try { action.run(); }
        catch (IllegalArgumentException error) {
            check(error.getMessage().contains(contains), "Unexpected failure: " + error);
            return;
        }
        throw new AssertionError("Expected failure: " + contains);
    }

    private static Context context(String value, double scale) {
        return new Context() {
            public String expand(String text) { return text.replace("{value}", value).replace("{player}", "Player"); }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
            public double textScale() { return scale; }
        };
    }

    private static LayoutStore.Document parse(String id, String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return LayoutStore.parse(id, yaml);
    }

    private static void gestures() throws Exception {
        var menu = parse("dynamic", DYNAMIC).menu();
        var session = UUID.randomUUID();
        var a = new Region("a", 0, 0, 10, 10, 0, "", List.of(), List.of("tell: single"), List.of("tell: double"));
        var b = new Region("b", 50, 0, 10, 10, 0, "", List.of(), a.actions(), a.doubleActions());
        var ta = ClickGestures.Target.of(session, menu, a, PREVIEW);
        var tb = ClickGestures.Target.of(session, menu, b, PREVIEW);
        check(!ta.equals(tb), "Different controls must have different identities");
        var callbacks = new ArrayList<Runnable>();
        var fired = new ArrayList<String>();
        var clock = new long[]{0};
        var gestures = new ClickGestures(() -> clock[0], (task, millis) -> {
            callbacks.add(task);
            return () -> callbacks.remove(task);
        });
        gestures.click(session, ta, () -> fired.add("single-a"), () -> fired.add("double-a"));
        gestures.click(session, tb, () -> fired.add("single-b"), () -> fired.add("double-b"));
        check(fired.isEmpty(), "Switching controls must not fire a double action");
        clock[0] = 301;
        for (Runnable task : List.copyOf(callbacks)) task.run();
        check(fired.equals(List.of("single-b")), "Only the current target's delayed single should run");
        fired.clear();
        clock[0] = 500;
        gestures.click(session, ta, () -> fired.add("single"), () -> fired.add("double"));
        clock[0] = 600;
        gestures.click(session, ta, () -> fired.add("single"), () -> fired.add("double"));
        check(fired.equals(List.of("double")), "Same-control double click should remain functional");
        var replaced = new Menu(menu.id(), "", menu.width(), menu.height(), List.of(), List.of(b));
        check(!ta.present(session, replaced, PREVIEW), "Removing A must invalidate A even while B remains");
    }

    private static void tokens() {
        var tokens = new ActionTokens();
        var page = new ActionTokens.Page();
        page.bind(() -> {});
        UUID player = UUID.randomUUID();
        tokens.publish(player, page);
        String id = "action/" + page.nonce + "/0";
        check(tokens.take(UUID.randomUUID(), id) == null, "Tokens must be player-bound");
        check(tokens.take(player, "action/bad/0") == null, "Invalid nonce must be rejected");
        check(tokens.take(player, id) != null, "Valid click should work");
        check(tokens.take(player, id) == null, "Consumed click must not replay");
    }

    private static void text() throws Exception {
        check(plain(MenuText.parse("§aOK")).equals("OK"), "Section-sign colors must parse");
        check(plain(MenuText.parse("§z § test")).equals("§z § test"), "Literal section signs must stay literal");
        check(plain(MenuText.parse("§x§1§2§a§b§3§4OK")).equals("OK"), "Repeated section-sign RGB must parse");
        check(plain(MenuText.parse("&oOK")).equals("OK"), "Italic markers must not be visible");
        check(plain(MenuText.parse("&nOK")).equals("OK"), "Underline markers must not be visible");
        check(plain(MenuText.parse("&#12ab34OK")).equals("OK"), "Hex colors must parse");
        check(MenuText.glyphs(MenuText.parse("&aA§lB")).getLast().bold(), "Mixed prefixes must preserve styles");
        check(!MenuText.glyphs(MenuText.parse("&lA&rB")).getLast().bold(), "Reset must clear bold");
        var yaml = new YamlConfiguration();
        yaml.loadFromString(DYNAMIC);
        yaml.set("frontend.label.line-width", 14);
        yaml.set("frontend.label.max-lines", 5);
        var node = LayoutStore.parse("dynamic", yaml).menu().nodes().getFirst();
        var lines = TextFlow.lines(node, MenuText.parse("&lABCD"), 1, glyph -> glyph.bold() ? 7 : 6);
        check(lines.size() == 2 && plain(lines.get(0)).equals("AB"), "Wrap must measure resolved styles");
        check(MenuText.glyphs(lines.getLast()).stream().allMatch(MenuText.Glyph::bold), "Wrapping must preserve bold");
        lines = TextFlow.lines(node, MenuText.parse("&oA\nB"), 1, glyph -> 6);
        check(MenuText.glyphs(lines.getLast()).getFirst().style().hasDecoration(TextDecoration.ITALIC),
            "Explicit newline must preserve italic");
    }

    private static void rendering(Path root, Path temporary) throws Exception {
        var assets = new VanillaAssets(temporary.resolve("images"));
        var renderer = new MenuRenderer(assets);
        var dynamic = parse("dynamic", DYNAMIC);
        var fonts = FontPack.create(List.of(dynamic), renderer, PREVIEW, List.of(300), 131072);
        check(fonts.textWidth("&aOK", 8) == fonts.textWidth("§aOK", 8), "Both color prefixes must have equal width");
        check(fonts.textWidth("&oOK", 8) == fonts.textWidth("OK", 8), "Italic must not add marker width");
        check(fonts.textWidth("&nOK", 8) == fonts.textWidth("OK", 8), "Underline must not add marker width");
        check(fonts.textWidth("&lOK", 8) == fonts.textWidth("OK", 8) + 2, "Bold advance must be accounted for");
        Context live = context("A\nB", 1);
        var output = new SpriteCanvas().encode(dynamic.menu(), renderer.draw(dynamic.menu(), live, 300, 131072), live, fonts, r -> null);
        int second = SpriteCanvas.textTop(dynamic.menu(), dynamic.menu().nodes().getFirst(), 300, 1, 8);
        Path exported = temporary.resolve("font-check");
        fonts.write(exported);
        check(Files.isRegularFile(exported.resolve("assets/dialogmenu/font/" + FontPack.fontName(second, 8) + ".json")),
            "Dynamic second line must have an exported font");
        check(containsFont(output.component(), fonts.textFont(second, 8)), "Second line must use the registered font");
        fails(() -> fonts.textFont(10000, 8), "文字位置已变化");
        var layered = parse("layered", """
            schema-version: 1
            id: layered
            canvas: {width: 300, height: 180}
            frontend:
              text:
                type: text
                content: A
                offset: {z: -1}
              cover:
                type: rectangle
                width: 20
                height: 20
                offset: {z: 1}
            """);
        var layeredFonts = FontPack.create(List.of(layered), renderer, PREVIEW, List.of(300), 131072);
        var layeredOut = new SpriteCanvas().encode(layered.menu(), renderer.draw(layered.menu(), PREVIEW, 300, 131072),
            PREVIEW, layeredFonts, r -> null);
        List<Key> paintOrder = new ArrayList<>();
        collectFonts(layeredOut.component(), null, paintOrder);
        int textAt = indexFont(paintOrder, "text_"), spriteAt = indexFont(paintOrder, "sprites");
        check(textAt >= 0 && spriteAt > textAt, "Low-z text must be emitted before its high-z cover");
        var docs = new LayoutStore(root.resolve("src/main/resources/layouts")).load();
        var demoFonts = FontPack.create(docs.values(), renderer, PREVIEW, List.of(300, 360, 420), 131072,
            "", List.of(1.0, 1.5, 2.0));
        for (var doc : docs.values()) for (int width : List.of(300, 360, 420)) for (double scale : List.of(1.0, 1.5, 2.0)) {
            Context context = context("A", scale);
            new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), context, width, 131072), context, demoFonts, r -> null);
            check(true, doc.menu().id() + " width=" + width + " scale=" + scale);
        }
    }

    private static void cache(Path temporary) throws Exception {
        Path directory = temporary.resolve("cache");
        Files.createDirectories(directory);
        var cache = new PackCache(directory);
        Path held = directory.resolve("dialog-" + UUID.randomUUID() + ".zip");
        Path orphan = directory.resolve("dialog-" + UUID.randomUUID() + ".zip");
        Path unrelated = directory.resolve("other.zip");
        Files.writeString(held, "held");
        Files.writeString(orphan, "orphan");
        Files.writeString(unrelated, "unrelated");
        Object consumer = new Object();
        cache.retain(consumer, held);
        cache.prune();
        check(Files.exists(held), "A live consumer must retain its ZIP");
        check(!Files.exists(orphan), "Unreferenced generated ZIP must be pruned");
        check(Files.exists(unrelated), "Unrelated ZIP must be left alone");
        Reference.reachabilityFence(consumer);
    }

    private static void reload(Path root, Path temporary) throws Exception {
        Path data = temporary.resolve("reload");
        Files.createDirectories(data.resolve("layouts"));
        Files.copy(root.resolve("src/main/resources/config.yml"), data.resolve("config.yml"));
        for (String file : List.of("layout-demo.yml", "layout-detail.yml"))
            Files.copy(root.resolve("src/main/resources/layouts/" + file), data.resolve("layouts/" + file));
        try (var engine = new ReloadEngine(data, PREVIEW)) {
            var first = engine.prepare().get(30, TimeUnit.SECONDS);
            var unchanged = engine.prepare().get(30, TimeUnit.SECONDS);
            check(first.changed() && !unchanged.changed(), "Unchanged inputs must reuse the snapshot");
            check(first.snapshot() == unchanged.snapshot(), "Cache hit must preserve snapshot identity");
            Files.writeString(data.resolve("config.yml"), Files.readString(data.resolve("config.yml")) + "\n# changed\n");
            var rebuilt = engine.prepare().get(30, TimeUnit.SECONDS);
            check(rebuilt.changed() && rebuilt.snapshot() != first.snapshot(), "Changed input must rebuild");
            check(rebuilt.snapshot().generation() > first.snapshot().generation(), "Rebuilds must advance publication generation");
            check(Files.exists(first.snapshot().pack()), "A snapshot held by the caller must retain its old ZIP");
            check(Files.exists(rebuilt.snapshot().pack()), "A rebuilt snapshot must have a ZIP");
        }
    }

    private static void metadata(Path root) throws Exception {
        String plugin = Files.readString(root.resolve("src/main/resources/plugin.yml"));
        String properties = Files.readString(root.resolve("gradle.properties"));
        check(plugin.contains("version: ${version}") && plugin.contains("api-version: '26.2'")
            && properties.contains("version=0.0.8"), "Version/API metadata must match target");
    }

    private static String plain(Component component) {
        var result = new StringBuilder();
        for (var glyph : MenuText.glyphs(component)) result.appendCodePoint(glyph.code());
        return result.toString();
    }

    private static boolean containsFont(Component component, Key font) {
        return font.equals(component.font()) || component.children().stream().anyMatch(child -> containsFont(child, font));
    }

    private static void collectFonts(Component component, Key inherited, List<Key> fonts) {
        Key font = component.font() == null ? inherited : component.font();
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty() && font != null)
            fonts.add(font);
        for (Component child : component.children()) collectFonts(child, font, fonts);
    }

    private static int indexFont(List<Key> fonts, String prefix) {
        for (int i = 0; i < fonts.size(); i++) if (fonts.get(i).value().startsWith(prefix)) return i;
        return -1;
    }
}
