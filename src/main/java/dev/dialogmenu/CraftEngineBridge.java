package dev.dialogmenu;

import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.*;

/** CE owns generation/upload; Dialog supplies one prepared, immutable external ZIP. */
final class CraftEngineBridge implements Listener {
    private final DialogMenuPlugin plugin;
    CraftEngineBridge(DialogMenuPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void cache(AsyncResourcePackCacheEvent event) {
        try {
            var result = plugin.prepareResources().get();
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                plugin.publishResources(result);
                return null;
            }).get();
            event.registerExternalResourcePack(result.snapshot().pack());
            plugin.getLogger().info("CraftEngine registered Dialog pack ("
                + (result.changed() ? "rebuilt" : "cached") + ")");
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Dialog重载失败，保留上一版资源", error);
            var previous = plugin.loadedResources();
            if (previous != null) event.registerExternalResourcePack(previous.pack());
            else throw new IllegalStateException("Dialog尚无可用资源包", error);
        }
    }
}
