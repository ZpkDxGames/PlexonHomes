package com.plexon.homes.command;

import com.plexon.homes.PlexonHomes;
import com.plexon.homes.api.HomeTeleportStatus;
import com.plexon.homes.gui.HomesGui;
import com.plexon.homes.migration.EssentialsMigrationService;
import com.plexon.homes.migration.EssentialsMigrationService.Report;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.TeleportService;
import com.plexon.homes.util.Text;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class HomeCommands implements CommandExecutor, TabCompleter {
    private final PlexonHomes plugin; private final HomeService homes; private final TeleportService teleports; private final HomesGui gui; private final EssentialsMigrationService migration;
    public HomeCommands(PlexonHomes plugin, HomeService homes, TeleportService teleports, HomesGui gui, EssentialsMigrationService migration) { this.plugin = plugin; this.homes = homes; this.teleports = teleports; this.gui = gui; this.migration = migration; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(java.util.Locale.ROOT); if (name.equals("homesadmin")) return admin(sender, args);
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        switch (name) {
            case "sethome" -> homes.setHome(player, args.length == 0 ? null : args[0]).thenAccept(ok -> tell(player, ok ? "<green>Home saved.</green>" : "<red>Could not save that home. Check name, limit, world, location or another mutation in progress.</red>"));
            case "home" -> teleports.request(player, args.length == 0 ? null : args[0]).thenAccept(status -> plugin.runPrimary(() -> explainTeleport(player, status)));
            case "homes" -> gui.open(player, 0);
            case "delhome" -> { if (args.length < 1) return false; homes.deleteHome(player, args[0]).thenAccept(ok -> tell(player, ok ? "<green>Home deleted.</green>" : "<red>Home not found, changed, or busy.</red>")); }
            case "renamehome" -> { if (args.length < 2) return false; homes.renameHome(player, args[0], args[1]).thenAccept(ok -> tell(player, ok ? "<green>Home renamed; stable identity preserved.</green>" : "<red>Could not rename home.</red>")); }
            default -> { return false; }
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plexonhomes.admin")) { sender.sendMessage("No permission."); return true; }
        if (args.length == 0) { sender.sendMessage("/homesadmin reload|diagnostics|backup|inspect <uuid>|migrate <scan|plan|execute|status>"); return true; }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> { var result = plugin.reloadHomesConfigTransactional(); tell(sender, result.success() ? "<green>" + Text.escape(result.detail()) + "</green>" : "<red>" + Text.escape(result.detail()) + "</red>"); }
            case "diagnostics" -> tell(sender, "<gray>" + Text.escape(plugin.diagnostics()) + "</gray>");
            case "backup" -> plugin.backup().thenAccept(path -> tell(sender, "<green>Backup created: <white>" + Text.escape(path.toString()) + "</white></green>"))
                    .exceptionally(error -> { tell(sender, "<red>Backup failed: " + Text.escape(rootMessage(error)) + "</red>"); return null; });
            case "inspect" -> {
                if (args.length < 2) { sender.sendMessage("/homesadmin inspect <player-uuid>"); return true; }
                UUID owner; try { owner = UUID.fromString(args[1]); } catch (IllegalArgumentException bad) { sender.sendMessage("Invalid UUID."); return true; }
                homes.inspectPersisted(owner).thenAccept(list -> tell(sender, "<aqua>Homes for <white>" + owner + "</white>: <white>" + list.size() + "</white> — " + Text.escape(list.stream().map(h -> h.displayName() + "@" + h.worldName() + "[r" + h.revision() + "]").toList().toString()) + "</aqua>"))
                        .exceptionally(error -> { tell(sender, "<red>Inspection failed: " + Text.escape(rootMessage(error)) + "</red>"); return null; });
            }
            case "migrate" -> {
                if (args.length < 2) { sender.sendMessage("/homesadmin migrate scan|plan|execute|status"); return true; }
                switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
                    case "scan" -> migration.scan().thenAccept(report -> tell(sender, "<gray>" + Text.escape(format(report)) + "</gray>"));
                    case "plan" -> tell(sender, "<gray>" + Text.escape(format(migration.markPlanned())) + "</gray>");
                    case "execute" -> migration.execute(false).thenAccept(report -> tell(sender, "<gray>" + Text.escape(format(report)) + "</gray>"));
                    case "status" -> tell(sender, "<gray>" + Text.escape(format(migration.status())) + "</gray>");
                    default -> sender.sendMessage("Unknown migration action.");
                }
            }
            default -> sender.sendMessage("Unknown admin action.");
        }
        return true;
    }

    private void tell(CommandSender sender, String miniMessage) { plugin.runPrimary(() -> sender.sendMessage(Text.mm(miniMessage))); }
    private static String format(Report r) { return "Migration " + r.status() + ": files=" + r.files() + ", players=" + r.players() + ", homes=" + r.homes() + ", conflicts=" + r.conflicts() + ", unresolved=" + r.unresolvedWorlds() + ", imported=" + r.imported(); }
    private static String rootMessage(Throwable error) { Throwable cursor = error; while (cursor.getCause() != null) cursor = cursor.getCause(); return String.valueOf(cursor.getMessage()); }
    private static void explainTeleport(Player player, HomeTeleportStatus status) {
        if (status == HomeTeleportStatus.STARTED || status == HomeTeleportStatus.TELEPORTED) return;
        player.sendMessage(Text.mm(switch (status) {
            case HOME_NOT_FOUND -> "<red>Home not found.</red>"; case ALREADY_PENDING -> "<gold>A home teleport is already in progress.</gold>";
            case COOLDOWN -> "<gold>Home teleport is on cooldown.</gold>"; case WORLD_BLOCKED -> "<red>That home world is unavailable or blocked.</red>";
            case UNSAFE_DESTINATION -> "<red>No safe destination was found near that home.</red>"; case ECONOMY_UNAVAILABLE -> "<red>The economy provider is unavailable.</red>";
            case INSUFFICIENT_FUNDS -> "<red>You cannot afford the teleport fee.</red>"; case CANCELLED -> "<red>Teleport cancelled.</red>"; default -> "<red>Home teleport failed.</red>";
        }));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("homesadmin")) {
            if (args.length == 1) return List.of("reload", "diagnostics", "backup", "inspect", "migrate");
            if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) return List.of("scan", "plan", "execute", "status"); return List.of();
        }
        if (!(sender instanceof Player player)) return List.of();
        if ((command.getName().equalsIgnoreCase("home") || command.getName().equalsIgnoreCase("delhome") || command.getName().equalsIgnoreCase("renamehome")) && args.length == 1)
            return homes.listCached(player.getUniqueId()).stream().map(h -> h.nameKey()).toList();
        return List.of();
    }
}
