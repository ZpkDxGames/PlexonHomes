package com.plexon.homes;

import com.plexon.homes.api.DefaultPlexonHomesAPI;
import com.plexon.homes.api.PlexonHomesAPI;
import com.plexon.homes.command.HomeCommands;
import com.plexon.homes.config.HomesConfig;
import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.gui.HomesGui;
import com.plexon.homes.integration.CoreIntegration;
import com.plexon.homes.integration.EconomyBridge;
import com.plexon.homes.integration.HomesPlaceholderExpansion;
import com.plexon.homes.listener.PlayerLifecycleListener;
import com.plexon.homes.migration.EssentialsMigrationService;
import com.plexon.homes.persistence.HomeRepository;
import com.plexon.homes.service.DeleteConfirmationRegistry;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.SafeTeleportService;
import com.plexon.homes.service.TeleportService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlexonHomes extends JavaPlugin {
    public record ReloadResult(boolean success, String detail) {}
    private volatile Snapshot snapshot;
    private CoreIntegration core; private HomeRepository repository; private HomeService homes; private TeleportService teleports;
    private EconomyBridge economy; private EssentialsMigrationService migration; private DeleteConfirmationRegistry confirmations;
    private HomesPlaceholderExpansion placeholderExpansion;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            migrateLegacyConfigIfNeeded(); snapshot = loadConfigCandidate();
            core = new CoreIntegration(this); core.registerStarting();
            repository = new HomeRepository(getDataFolder().toPath().resolve("homes.db"));
            SafeTeleportService safe = new SafeTeleportService(this, this::configSnapshot);
            homes = new HomeService(this, repository, safe, this::configSnapshot);
            economy = new EconomyBridge(this);
            teleports = new TeleportService(this, homes, safe, economy, core.playerWatches(), this::configSnapshot);
            migration = new EssentialsMigrationService(this, homes); confirmations = new DeleteConfirmationRegistry();
            DefaultPlexonHomesAPI api = new DefaultPlexonHomesAPI(homes, teleports);
            Bukkit.getServicesManager().register(PlexonHomesAPI.class, api, this, ServicePriority.Normal);
            HomesGui gui = new HomesGui(this, homes, teleports, confirmations, this::configSnapshot);
            Bukkit.getPluginManager().registerEvents(gui, this);
            Bukkit.getPluginManager().registerEvents(new PlayerLifecycleListener(homes, repository), this);
            HomeCommands commands = new HomeCommands(this, homes, teleports, gui, migration);
            bind("sethome", commands); bind("home", commands); bind("homes", commands); bind("delhome", commands); bind("renamehome", commands); bind("homesadmin", commands);
            registerPlaceholderApi();
            repository.ready().whenComplete((ignored, error) -> runPrimary(() -> {
                if (error == null) {
                    core.ready("PlexonHomes " + getPluginMeta().getVersion() + " ready with stable home identity and Core player-watch runtime");
                    getLogger().info("PlexonHomes ready; schema=" + repository.schemaVersion() + ", Core " + core.corePluginVersion() + " API " + core.coreApiVersion());
                } else {
                    core.failed("SQLite initialization/migration failed"); getLogger().severe("SQLite initialization/migration failed: " + rootMessage(error)); Bukkit.getPluginManager().disablePlugin(this);
                }
            }));
        } catch (Throwable throwable) {
            getLogger().severe("PlexonHomes cannot start: " + rootMessage(throwable)); if (core != null) core.failed(rootMessage(throwable)); Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (placeholderExpansion != null) { try { placeholderExpansion.unregister(); } catch (Throwable ignored) { } placeholderExpansion = null; }
        if (confirmations != null) confirmations.clearAll(); if (teleports != null) teleports.close(); if (repository != null) repository.close();
        if (core != null) core.unregister(); Bukkit.getServicesManager().unregisterAll(this);
    }

    private void bind(String commandName, HomeCommands commands) {
        PluginCommand command = Objects.requireNonNull(getCommand(commandName), commandName + " missing from plugin.yml"); command.setExecutor(commands); command.setTabCompleter(commands);
    }

    private void registerPlaceholderApi() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        HomesPlaceholderExpansion expansion = new HomesPlaceholderExpansion(homes, this::configSnapshot, getPluginMeta().getVersion());
        if (expansion.register()) { placeholderExpansion = expansion; getLogger().info("PlaceholderAPI expansion registered."); }
        else getLogger().warning("PlaceholderAPI expansion registration was refused.");
    }

    private Snapshot loadConfigCandidate() {
        File file = new File(getDataFolder(), "config.yml"); return HomesConfig.load(YamlConfiguration.loadConfiguration(file));
    }

    /** Converts only an absent/explicit numeric v1 marker; malformed/future markers fail closed without rewriting config. */
    private void migrateLegacyConfigIfNeeded() throws Exception {
        File file = new File(getDataFolder(), "config.yml"); YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int version = configVersionForMigration(yaml);
        if (version == 2) return;
        yaml.set("config-version", 2); if (!yaml.contains("gui.delete-confirmation-seconds")) yaml.set("gui.delete-confirmation-seconds", 15);
        HomesConfig.load(yaml);
        Path backupDir = getDataFolder().toPath().resolve("backups"); Files.createDirectories(backupDir);
        Path backup = backupDir.resolve("config-v1-before-phase2.yml"); if (!Files.exists(backup)) Files.copy(file.toPath(), backup, StandardCopyOption.COPY_ATTRIBUTES);
        yaml.save(file);
    }

    static int configVersionForMigration(YamlConfiguration yaml) {
        Object raw = yaml.get("config-version");
        if (raw == null) return 1;
        if (!(raw instanceof Number number)) throw new IllegalArgumentException("config-version must be an integer");
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) throw new IllegalArgumentException("config-version must be an integer");
        int version = (int) numeric;
        if (version != 1 && version != 2) throw new IllegalArgumentException("config-version must be 1 for migration or 2 for Phase 2");
        return version;
    }

    public synchronized ReloadResult reloadHomesConfigTransactional() {
        Snapshot prior = snapshot;
        try {
            Snapshot candidate = loadConfigCandidate();
            if (candidate.fee() > 0.0D && economy != null) economy.refresh();
            snapshot = candidate;
            return new ReloadResult(true, "Configuration committed atomically (config-version " + candidate.configVersion() + ").");
        } catch (Throwable error) {
            snapshot = prior; return new ReloadResult(false, "Reload rejected; prior runtime retained: " + rootMessage(error));
        }
    }

    public Snapshot configSnapshot() { return snapshot; }
    public void runPrimary(Runnable runnable) { if (Bukkit.isPrimaryThread()) runnable.run(); else Bukkit.getScheduler().runTask(this, runnable); }
    public CompletableFuture<Path> backup() { return repository.backup(getDataFolder().toPath().resolve("backups")); }

    public String diagnostics() {
        var watches = core.playerWatches().stats();
        return "PlexonHomes diagnostics: version=" + getPluginMeta().getVersion() + ", core=" + core.corePluginVersion() + "/API " + core.coreApiVersion()
                + ", schema=" + repository.schemaVersion() + ", profiles=" + homes.loadedProfiles() + ", cachedHomes=" + homes.cachedHomes() + ", mutations=" + homes.activeMutations()
                + ", pendingTeleports=" + teleports.pendingCount() + ", teleportAsync=" + teleports.inFlightCount() + ", cooldowns=" + teleports.cooldownCount()
                + ", watchedPlayers=" + watches.watchedPlayers() + ", watchRegistrations=" + watches.registrations()
                + ", sqliteWrites=" + repository.writes() + ", sqliteFailures=" + repository.failures() + ", papi=" + (placeholderExpansion != null)
                + ", migration=" + migration.status().status();
    }

    private static String rootMessage(Throwable error) { Throwable cursor = error; while (cursor.getCause() != null) cursor = cursor.getCause(); return String.valueOf(cursor.getMessage()); }
}
