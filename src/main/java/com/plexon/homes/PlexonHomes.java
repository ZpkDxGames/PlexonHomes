package com.plexon.homes;

import com.plexon.homes.api.DefaultPlexonHomesAPI;
import com.plexon.homes.api.PlexonHomesAPI;
import com.plexon.homes.command.HomeCommands;
import com.plexon.homes.config.HomesConfig;
import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.gui.HomesGui;
import com.plexon.homes.integration.CoreIntegration;
import com.plexon.homes.integration.EconomyBridge;
import com.plexon.homes.listener.PlayerLifecycleListener;
import com.plexon.homes.migration.EssentialsMigrationService;
import com.plexon.homes.persistence.HomeRepository;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.SafeTeleportService;
import com.plexon.homes.service.TeleportService;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlexonHomes extends JavaPlugin {
    private volatile Snapshot snapshot;
    private CoreIntegration core;
    private HomeRepository repository;
    private HomeService homes;
    private TeleportService teleports;
    private EconomyBridge economy;
    private EssentialsMigrationService migration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadHomesConfig();
        try {
            core = new CoreIntegration(this);
            core.registerStarting();
            repository = new HomeRepository(getDataFolder().toPath().resolve("homes.db"));
            SafeTeleportService safe = new SafeTeleportService(this, this::configSnapshot);
            homes = new HomeService(this, repository, safe, this::configSnapshot);
            economy = new EconomyBridge(this);
            teleports = new TeleportService(this, homes, safe, economy, core.playerWatches(), this::configSnapshot);
            migration = new EssentialsMigrationService(this, homes);
            DefaultPlexonHomesAPI api = new DefaultPlexonHomesAPI(this, homes, teleports);
            Bukkit.getServicesManager().register(PlexonHomesAPI.class, api, this, ServicePriority.Normal);

            HomesGui gui = new HomesGui(homes, teleports);
            Bukkit.getPluginManager().registerEvents(gui, this);
            Bukkit.getPluginManager().registerEvents(new PlayerLifecycleListener(homes, repository), this);
            HomeCommands commands = new HomeCommands(this, homes, teleports, gui, migration);
            bind("sethome", commands); bind("home", commands); bind("homes", commands); bind("delhome", commands); bind("renamehome", commands); bind("homesadmin", commands);

            repository.ready().whenComplete((ignored, error) -> runPrimary(() -> {
                if (error == null) {
                    core.ready("PlexonHomes " + getPluginMeta().getVersion() + " ready with Core player-watch runtime");
                    getLogger().info("PlexonHomes ready; Core " + core.corePluginVersion() + " API " + core.coreApiVersion());
                } else {
                    core.failed("SQLite initialization failed");
                    getLogger().severe("SQLite initialization failed: " + error.getMessage());
                    Bukkit.getPluginManager().disablePlugin(this);
                }
            }));
        } catch (Throwable throwable) {
            getLogger().severe("PlexonHomes cannot start: " + throwable.getMessage());
            if (core != null) core.failed(throwable.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (teleports != null) teleports.close();
        if (repository != null) repository.close();
        if (core != null) core.unregister();
        Bukkit.getServicesManager().unregisterAll(this);
    }

    private void bind(String commandName, HomeCommands commands) {
        PluginCommand command = Objects.requireNonNull(getCommand(commandName), commandName + " missing from plugin.yml");
        command.setExecutor(commands); command.setTabCompleter(commands);
    }

    public Snapshot configSnapshot() { return snapshot; }
    public void reloadHomesConfig() { reloadConfig(); snapshot = HomesConfig.load(getConfig()); if (economy != null) economy.refresh(); }
    public void runPrimary(Runnable runnable) { if (Bukkit.isPrimaryThread()) runnable.run(); else Bukkit.getScheduler().runTask(this, runnable); }
    public CompletableFuture<Path> backup() { return repository.backup(getDataFolder().toPath().resolve("backups")); }

    public String diagnostics() {
        var watches = core.playerWatches().stats();
        return "PlexonHomes diagnostics: core=" + core.corePluginVersion() + "/API " + core.coreApiVersion()
                + ", profiles=" + homes.loadedProfiles() + ", cachedHomes=" + homes.cachedHomes()
                + ", pendingTeleports=" + teleports.pendingCount() + ", cooldowns=" + teleports.cooldownCount()
                + ", watchedPlayers=" + watches.watchedPlayers() + ", watchRegistrations=" + watches.registrations()
                + ", sqliteWrites=" + repository.writes() + ", sqliteFailures=" + repository.failures()
                + ", migration=" + migration.status().status();
    }
}
