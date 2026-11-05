package dev.dialogmenu;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** One character per graphic, plus independent positioned Unicode text. */
final class SpriteCanvas {
    record Output(Component component, int bodyWidth, int rows, int sprites) {}
    static final Key SPACE = Key.key("dialogmenu", "space");
    static String move(int pixels) {
        if (pixels < -1024 || pixels > 1024) throw new IllegalArgumentException("Canvas movement out of range");
        return Character.toString(0xF0000 + pixels + 1024);
    }
    private static Component space(int pixels) { return Component.text(move(pixels)).font(SPACE); }
    private static Component positioned(Component ink, int x, int advance) {
        return Component.empty().append(space(x)).append(ink).append(space(-x - advance));
    }
    static int textTop(Menu menu, Node node, int width, int line) {
        return textTop(menu, node, width, line, node.size());
    }
    static int textSize(Menu menu, Node node, int width, double scale) {
        int size = (int) Math.round(node.size() * scale * (menu.scaleTextWithCanvas() ? width / (double) menu.width() : 1));
        if (size < 1 || size > 128) throw new IllegalArgumentException(node.id() + " 实际字号须为1..128");
        return size;
    }
    static int textTop(Menu menu, Node node, int width, int line, int size) {
        return (int) Math.round((menu.height() / 2.0 - node.y()) * width / menu.width() - size / 2.0 + line * (size + 1));
    }
    Output encode(Menu menu, Rendered frame, Context context, FontPack fonts, Function<Region, ClickEvent> bind) {
        int width = frame.image().getWidth(), height = frame.image().getHeight(), rows = (height + 8) / 9;
        List<Component> parts = new ArrayList<>();
        Map<Region, ClickEvent> clicks = new HashMap<>();
        for (Region region : menu.regions())
            if (region.hasActions()) clicks.put(region, bind.apply(region));
        for (Sprite sprite : frame.sprites()) {
            Node node = menu.nodes().stream().filter(n -> n.id().equals(sprite.id())).findFirst().orElse(null);
            Region region = node == null || node.interaction() == null ? null : node.interaction().region(node);
            addSprite(parts, sprite, fonts, region == null || !region.hasActions() ? null : bind.apply(region),
                region == null ? null : hover(region, context));
        }
        // Explicit legacy hit areas retain priority when mixed with inline controls.
        for (int i = menu.regions().size() - 1; i >= 0; i--) {
            Region region = menu.regions().get(i);
            Sprite hit = MenuRenderer.hitSprite(menu, frame, region);
            if (hit != null) addSprite(parts, hit, fonts, clicks.get(region), hover(region, context));
        }
        double zoom = width / (double) menu.width();
        for (Node node : frame.textNodes()) {
            int size = textSize(menu, node, width, context.textScale());
            List<String> lines = TextFlow.lines(node, context.expand(node.text()), zoom, text -> fonts.textWidth(text, size));
            Region region = node.interaction() == null ? menu.hit(node.x(), node.y()) : node.interaction().region(node);
            if (region != null && !clicks.containsKey(region) && region.hasActions())
                clicks.put(region, bind.apply(region));
            for (int line = 0; line < lines.size(); line++) {
                String text = lines.get(line);
                int advance = fonts.textWidth(text, size);
                double anchor = (menu.width() / 2.0 + node.x()) * zoom;
                int x = (int) Math.round(anchor - switch (node.alignment()) {
                    case "left" -> 0;
                    case "right" -> advance;
                    default -> advance / 2.0;
                });
                int top = textTop(menu, node, width, line, size);
                if (top < 0 || top + size > height || x < 0 || x + advance > width)
                    throw new IllegalArgumentException(node.id() + " 原生文字超出画布");
                Component label = legacy(text).font(Key.key("dialogmenu", FontPack.fontName(top, size)));
                if (!text.matches(".*[&§][0-9a-fA-F].*"))
                    label = label.color(TextColor.color(node.argb()));
                if (region != null) label = label.clickEvent(clicks.get(region)).hoverEvent(hover(region, context));
                parts.add(positioned(label, x, advance));
            }
        }
        Component body = Component.empty();
        for (Component part : parts) body = body.append(part);
        for (int row = 0; row < rows; row++) {
            body = body.append(space(width + 2));
            if (row + 1 < rows) body = body.append(Component.newline());
        }
        return new Output(body.decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none()), width + 10, rows, frame.sprites().size());
    }
    private static void addSprite(List<Component> parts, Sprite sprite, FontPack fonts,
                                  ClickEvent click, HoverEvent<Component> hover) {
        FontPack.Glyph glyph = fonts.sprite(sprite);
        Component ink = Component.text(Character.toString(glyph.code()), TextColor.color(0xFFFFFF))
            .font(Key.key("dialogmenu", "sprites")).clickEvent(click).hoverEvent(hover);
        parts.add(positioned(ink, sprite.x(), glyph.advance()));
    }
    private static HoverEvent<Component> hover(Region region, Context context) {
        return region.tooltip().isEmpty() ? null
            : HoverEvent.showText(legacy(context.expand(String.join("\n", region.tooltip()))));
    }
    static Component legacy(String text) { return LegacyComponentSerializer.legacyAmpersand().deserialize(text); }
}
