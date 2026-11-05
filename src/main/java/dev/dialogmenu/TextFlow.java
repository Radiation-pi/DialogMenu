package dev.dialogmenu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import static dev.dialogmenu.MenuModel.*;

/** Wrap native characters by emitted glyph advances, keeping legacy styles across lines. */
final class TextFlow {
    static List<String> lines(Node node, String text, double zoom, ToIntFunction<String> measure) {
        if (node.maxLines() == 0) return List.of(text.split("\\n", -1));
        int limit = Math.max(1, (int) Math.floor(node.width() * node.scaleX() * zoom));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder(), style = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i), length = Character.charCount(cp);
            if ((cp == '&' || cp == '§') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                int end = i + 2;
                if (code == '#' && i + 8 <= text.length()
                    && text.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) end = i + 8;
                if (end > i + 2 || "0123456789abcdefklmnor".indexOf(code) >= 0) {
                    String token = text.substring(i, end).replace('§', '&');
                    if ("0123456789abcdefr#".indexOf(code) >= 0) style.setLength(0);
                    style.append(token);
                    line.append(token);
                    i = end;
                    continue;
                }
            }
            String letter = Character.toString(cp);
            if (cp < 128 && Character.isLetterOrDigit(cp)) {
                int end = i + length;
                while (end < text.length() && text.charAt(end) < 128 && Character.isLetterOrDigit(text.charAt(end))) end++;
                if (end < text.length() && text.charAt(end) == '级') end++;
                String word = text.substring(i, end);
                if (measure.applyAsInt(style + word) <= limit) { letter = word; length = end - i; }
            }
            i += length;
            int advance = cp == '\n' ? 0 : measure.applyAsInt(style + letter);
            if (cp == '\n' || used + advance > limit && used > 0) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(style);
                used = 0;
            }
            if (cp == '\n') continue;
            if (advance > limit) throw new IllegalArgumentException(node.id() + " 单字超出line-width");
            line.append(letter);
            used += advance;
        }
        lines.add(line.toString());
        if (lines.size() > node.maxLines())
            throw new IllegalArgumentException(node.id() + " 文字超过max-lines=" + node.maxLines());
        return List.copyOf(lines);
    }
}
