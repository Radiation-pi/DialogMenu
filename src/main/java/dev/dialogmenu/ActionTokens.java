package dev.dialogmenu;

import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.ClickEvent;

final class ActionTokens {
    static final class Page {
        final UUID nonce = UUID.randomUUID();
        private record Action(Runnable run, Object gesture) {}
        final List<Action> actions = new ArrayList<>();
        private Object pendingGesture;
        ClickEvent bind(Runnable action) {
            return bind(action, null);
        }
        ClickEvent bind(Runnable action, Object gesture) {
            int index = actions.size();
            actions.add(new Action(action, gesture));
            return ClickEvent.custom(Key.key("dialogmenu", "action/" + nonce + "/" + index),
                BinaryTagHolder.binaryTagHolder("{}"));
        }
    }
    private final Map<UUID, Page> pages = new HashMap<>();
    void publish(UUID player, Page page) { pages.put(player, page); }
    void remove(UUID player) { pages.remove(player); }
    void clear() { pages.clear(); }
    Runnable take(UUID player, String identifier) {
        String[] parts = identifier.split("/", -1);
        Page page = pages.get(player);
        if (page == null || parts.length != 3 || !parts[0].equals("action")
            || !page.nonce.toString().equals(parts[1])) return null;
        int index;
        try { index = Integer.parseInt(parts[2]); } catch (NumberFormatException ignored) { return null; }
        if (index < 0 || index >= page.actions.size()) return null;
        var action = page.actions.get(index);
        // Keep the first gesture's nonce alive for exactly one matching second click.
        if (action.gesture == null || action.gesture.equals(page.pendingGesture)) pages.remove(player);
        else page.pendingGesture = action.gesture;
        return action.run;
    }
}
