package dev.dialogmenu;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static dev.dialogmenu.MenuModel.*;

/** Each graphic is a separate sprite; the combined image is only an offline preview. */
final class MenuRenderer {
    final VanillaAssets assets;
    MenuRenderer(VanillaAssets assets) { this.assets = assets; }

    Rendered draw(Menu menu, Context context, int width, int maxPixels) throws IOException {
        int height = Math.max(1, (int) Math.round(menu.height() * width / (double) menu.width()));
        if (width < 64 || width > 512 || height > 320 || (long) width * height > maxPixels)
            throw new IllegalArgumentException("当前画布比例/尺寸超出正文范围，请减小尺寸；不自动重排布局");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D root = image.createGraphics();
        double zoom = width / (double) menu.width();
        List<Sprite> sprites = new ArrayList<>();
        List<Node> textNodes = new ArrayList<>();
        for (Node n : menu.nodes()) {
            if (n.type().equals("text")) {
                if (n.rotation() != 0) throw new IllegalArgumentException(n.id() + " 原生文字不支持旋转");
                textNodes.add(n);
                continue;
            }
            double h = n.type().equals("line") ? n.thickness() : n.height();
            AffineTransform transform = new AffineTransform();
            transform.scale(zoom, zoom);
            transform.translate(menu.width() / 2.0 + n.x(), menu.height() / 2.0 - n.y());
            transform.rotate(-Math.toRadians(n.rotation()));
            transform.scale(n.scaleX(), n.scaleY());
            Rectangle2D box = transform.createTransformedShape(
                new Rectangle2D.Double(-n.width() / 2, -h / 2, n.width(), h)).getBounds2D();
            int left = (int) Math.floor(box.getMinX() + 1e-7), top = (int) Math.floor(box.getMinY() + 1e-7);
            Rectangle bounds = new Rectangle(left, top,
                (int) Math.ceil(box.getMaxX() - 1e-7) - left, (int) Math.ceil(box.getMaxY() - 1e-7) - top);
            Rectangle unclipped = new Rectangle(bounds);
            bounds = bounds.intersection(new Rectangle(0, 0, width, height));
            if (bounds.isEmpty()) continue;
            if (n.reuseTexture()) {
                var reference = assets.craftEngine.reference(n.source());
                if (n.rotation() != 0 || n.argb() >>> 24 != 255 || !bounds.equals(unclipped)
                    || Math.abs(bounds.width - reference.image().getWidth()
                        * bounds.height / (double) reference.image().getHeight()) > .51)
                    throw new IOException(n.id() + " CE直引须完整显示、等比缩放、不旋转且opacity=255");
                // The glyph references the CE texture; this image is only the offline preview.
                root.drawImage(reference.image(), bounds.x, bounds.y, bounds.width, bounds.height, null);
                // Keep the displayed bounds without copying the source PNG.
                sprites.add(new Sprite(n.id(), bounds.x, bounds.y,
                    new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB),
                    reference.texture(), reference.image()));
                continue;
            }
            BufferedImage layer = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = layer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.translate(-bounds.x, -bounds.y);
            g.transform(transform);
            g.setColor(new Color(n.argb(), true));
            double x = -n.width() / 2, y = -h / 2;
            switch (n.type()) {
                case "rectangle", "line" -> g.fill(new Rectangle2D.Double(x, y, n.width(), h));
                case "frame" -> {
                    double t = n.thickness();
                    g.fill(new Rectangle2D.Double(x, y, n.width(), t));
                    g.fill(new Rectangle2D.Double(x, y + h - t, n.width(), t));
                    g.fill(new Rectangle2D.Double(x, y, t, h));
                    g.fill(new Rectangle2D.Double(x + n.width() - t, y, t, h));
                }
                case "image" -> {
                    BufferedImage texture = assets.image(n.source());
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (n.argb() >>> 24) / 255f));
                    g.drawImage(texture, (int) Math.round(x), (int) Math.round(y),
                        (int) Math.round(n.width()), (int) Math.round(h), null);
                }
                default -> throw new IllegalArgumentException("元素尚不支持：" + n.type());
            }
            g.dispose();
            sprites.add(new Sprite(n.id(), bounds.x, bounds.y, layer));
            root.drawImage(layer, bounds.x, bounds.y, null);
        }
        root.dispose();
        return new Rendered(image, List.copyOf(sprites), List.copyOf(textNodes));
    }

    static Sprite hitSprite(Menu menu, Rendered frame, Region region) {
        double zoom = frame.image().getWidth() / (double) menu.width();
        int left = Math.max(0, (int) Math.round((menu.width() / 2.0 + region.x() - region.width() / 2) * zoom));
        int top = Math.max(0, (int) Math.round((menu.height() / 2.0 - region.y() - region.height() / 2) * zoom));
        int right = Math.min(frame.image().getWidth(),
            (int) Math.round((menu.width() / 2.0 + region.x() + region.width() / 2) * zoom));
        int bottom = Math.min(frame.image().getHeight(),
            (int) Math.round((menu.height() / 2.0 - region.y() + region.height() / 2) * zoom));
        if (right <= left || bottom <= top) return null;
        BufferedImage hit = new BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hit.createGraphics();
        g.setColor(new Color(0x01FFFFFF, true));
        g.fillRect(0, 0, hit.getWidth(), hit.getHeight());
        g.dispose();
        return new Sprite("hit:" + region.id(), left, top, hit);
    }
}
