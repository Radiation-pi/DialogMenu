package dev.dialogmenu;

import java.util.Map;
import org.bukkit.entity.Player;

/** A plugin-owned YAML session. Handles become inert after replacement or cleanup. */
public final class DialogMenuBinding {
    private final DialogMenuApi api;
    final Player player;
    final Object identity;

    DialogMenuBinding(DialogMenuApi api, Player player, Object identity) {
        this.api = api;
        this.player = player;
        this.identity = identity;
    }

    public boolean isActive() { return api.bindingActive(this); }
    public boolean update(Map<String, String> values) { return api.bindingUpdate(this, values); }
    public boolean open(String page) { return api.bindingOpen(this, page); }
    /** Revoke canvas clicks before presenting a separate text-input dialog. */
    public boolean suspend() { return api.bindingSuspend(this, true); }
    public boolean resume() { return api.bindingSuspend(this, false); }
    public void close() { api.bindingClose(this); }
}
