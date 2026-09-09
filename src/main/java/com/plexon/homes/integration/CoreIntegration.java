package com.plexon.homes.integration;

import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import com.zpkdxgames.plexoncore.player.PlayerWatchService;
import java.time.Instant;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreIntegration {
    static final String MODULE_ID = "homes";
    static final String API_RANGE = ">=2.0 <3.0";
    private final JavaPlugin plugin;
    private final PlexonCoreAPI core;
    private final PlayerWatchService playerWatches;
    private boolean registered;

    public CoreIntegration(JavaPlugin plugin) {
        this.plugin = plugin;
        RegisteredServiceProvider<PlexonCoreAPI> apiRegistration = Bukkit.getServicesManager().getRegistration(PlexonCoreAPI.class);
        if (apiRegistration == null) throw new IllegalStateException("PlexonCore API service is unavailable");
        this.core = apiRegistration.getProvider();
        if (!ModuleVersionRange.parse(API_RANGE).contains(core.version())) {
            throw new IllegalStateException("PlexonCore API " + core.version().apiVersion() + " is outside " + API_RANGE);
        }
        RegisteredServiceProvider<PlayerWatchService> watchRegistration = Bukkit.getServicesManager().getRegistration(PlayerWatchService.class);
        if (watchRegistration == null) {
            throw new IllegalStateException("PlexonCore shared player watch runtime is unavailable; PlexonCore 2.0.4 is required");
        }
        this.playerWatches = watchRegistration.getProvider();
    }

    public void registerStarting() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                MODULE_ID,
                "PlexonHomes",
                plugin.getName(),
                plugin.getPluginMeta().getVersion(),
                plugin,
                ModuleVersionRange.parse(API_RANGE),
                Set.of("homes", "safe-teleport", "player-watch", "home-api", "home-events", "sqlite-persistence"),
                ModuleState.STARTING,
                "Initializing PlexonHomes",
                Instant.now());
        ModuleRegistry.RegistrationResult result = core.modules().register(descriptor);
        registered = result.descriptor() != null && result.descriptor().plugin() == plugin;
        if (!registered) throw new IllegalStateException("PlexonCore module registration failed: " + result.message());
    }

    public PlayerWatchService playerWatches() { return playerWatches; }
    public PlexonCoreAPI api() { return core; }
    public String corePluginVersion() { return core.version().pluginVersion(); }
    public String coreApiVersion() { return core.version().apiVersion(); }
    public void ready(String detail) { update(ModuleState.READY, detail); }
    public void degraded(String detail) { update(ModuleState.DEGRADED, detail); }
    public void failed(String detail) { update(ModuleState.FAILED, detail); }

    private void update(ModuleState state, String detail) {
        if (!registered) return;
        if (!core.modules().updateState(MODULE_ID, plugin, state, detail)) {
            registered = false;
            throw new IllegalStateException("PlexonCore module ownership changed before state transition to " + state);
        }
    }

    public void unregister() {
        if (!registered) return;
        core.modules().updateState(MODULE_ID, plugin, ModuleState.DISABLED, "PlexonHomes disabled cleanly");
        core.modules().unregisterOwnedBy(plugin);
        registered = false;
    }
}
