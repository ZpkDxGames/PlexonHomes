package com.plexon.homes.command;

import com.plexon.homes.PlexonHomes;
import com.plexon.homes.api.HomeTeleportStatus;
import com.plexon.homes.gui.HomesGui;
import com.plexon.homes.migration.EssentialsMigrationService;
import com.plexon.homes.migration.EssentialsMigrationService.Report;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.TeleportService;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class HomeCommands implements CommandExecutor, TabCompleter {
    private final PlexonHomes plugin;
    private final HomeService homes;
    private final TeleportService teleports;
    private final HomesGui gui;
    private final EssentialsMigrationService migration;

    public HomeCommands(PlexonHomes plugin, HomeService homes, TeleportService teleports, HomesGui gui, EssentialsMigrationService migration) {
        this.plugin = plugin; this.homes = homes; this.teleports = teleports; this.gui = gui; this.migration = migration;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("homesadmin")) return admin(sender, args);
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        switch (name) {
            case "sethome" -> homes.ensureLoaded(player.getUniqueId()).thenRun(() -> plugin.runPrimary(() -> {
                boolean ok = homes.setHomeNow(player, args.length == 0 ? null : args[0]);
                player.sendMessage(Component.text(ok ? "Home saved." : "Could not save that home. Check name, limit, world and location."));
            }));
            case "home" -> teleports.request(player, args.length == 0 ? null : args[0]).thenAccept(status -> plugin.runPrimary(() -> explainTeleport(player, status)));
            case "homes" -> gui.open(player, 0);
            case "delhome" -> {
                if (args.length < 1) return false;
                homes.ensureLoaded(player.getUniqueId()).thenRun(() -> plugin.runPrimary(() -> player.sendMessage(Component.text(homes.deleteHomeNow(player, args[0]) ? "Home deleted." : "Home not found."))));
            }
            case "renamehome" -> {
                if (args.length < 2) return false;
                homes.ensureLoaded(player.getUniqueId()).thenRun(() -> plugin.runPrimary(() -> player.sendMessage(Component.text(homes.renameHomeNow(player, args[0], args[1]) ? "Home renamed." : "Could not rename home."))));
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexonhomes.admin")) { sender.sendMessage("No permission."); return true; }
        if (args.length == 0) { sender.sendMessage("/homesadmin reload|diagnostics|backup|migrate <scan|plan|execute|status>"); return true; }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> { plugin.reloadHomesConfig(); sender.sendMessage("PlexonHomes configuration reloaded."); }
            case "diagnostics" -> sender.sendMessage(plugin.diagnostics());
            case "backup" -> plugin.backup().thenAccept(path -> sender.sendMessage("Backup: " + path)).exceptionally(error -> { sender.sendMessage("Backup failed: " + error.getMessage()); return null; });
            case "migrate" -> {
                if (args.length < 2) { sender.sendMessage("/homesadmin migrate scan|plan|execute|status"); return true; }
                switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
                    case "scan" -> migration.scan().thenAccept(report -> sender.sendMessage(format(report)));
                    case "plan" -> sender.sendMessage(format(migration.markPlanned()));
                    case "execute" -> migration.execute(false).thenAccept(report -> sender.sendMessage(format(report)));
                    case "status" -> sender.sendMessage(format(migration.status()));
                    default -> sender.sendMessage("Unknown migration action.");
                }
            }
            default -> sender.sendMessage("Unknown admin action.");
        }
        return true;
    }

    private static String format(Report r) { return "Migration " + r.status() + ": files=" + r.files() + ", players=" + r.players() + ", homes=" + r.homes() + ", conflicts=" + r.conflicts() + ", unresolved=" + r.unresolvedWorlds() + ", imported=" + r.imported(); }
    private static void explainTeleport(Player player, HomeTeleportStatus status) {
        if (status == HomeTeleportStatus.STARTED || status == HomeTeleportStatus.TELEPORTED) return;
        player.sendMessage(Component.text(switch (status) {
            case HOME_NOT_FOUND -> "Home not found.";
            case COOLDOWN -> "Home teleport is on cooldown.";
            case WORLD_BLOCKED -> "That home world is unavailable or blocked.";
            case UNSAFE_DESTINATION -> "No safe destination was found near that home.";
            case ECONOMY_UNAVAILABLE -> "The economy provider is unavailable.";
            case INSUFFICIENT_FUNDS -> "You cannot afford the teleport fee.";
            case CANCELLED -> "Teleport cancelled.";
            default -> "Home teleport failed.";
        }));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("homesadmin")) {
            if (args.length == 1) return List.of("reload", "diagnostics", "backup", "migrate");
            if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) return List.of("scan", "plan", "execute", "status");
            return List.of();
        }
        if (!(sender instanceof Player player)) return List.of();
        if ((command.getName().equalsIgnoreCase("home") || command.getName().equalsIgnoreCase("delhome")) && args.length == 1) return homes.listCached(player.getUniqueId()).stream().map(h -> h.id()).toList();
        if (command.getName().equalsIgnoreCase("renamehome") && args.length == 1) return homes.listCached(player.getUniqueId()).stream().map(h -> h.id()).toList();
        return List.of();
    }
}
