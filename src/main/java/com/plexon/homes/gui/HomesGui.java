package com.plexon.homes.gui;

import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.model.Home;
import com.plexon.homes.service.DeleteConfirmationRegistry;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.TeleportService;
import com.plexon.homes.util.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomesGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private final JavaPlugin plugin; private final HomeService homes; private final TeleportService teleports; private final DeleteConfirmationRegistry confirmations; private final Supplier<Snapshot> config;
    private final AtomicLong generations = new AtomicLong(); private final ConcurrentHashMap<UUID, Long> activeSessions = new ConcurrentHashMap<>();
    public HomesGui(JavaPlugin plugin, HomeService homes, TeleportService teleports, DeleteConfirmationRegistry confirmations, Supplier<Snapshot> config) { this.plugin = plugin; this.homes = homes; this.teleports = teleports; this.confirmations = confirmations; this.config = config; }

    public void open(Player player, int page) {
        homes.ensureLoaded(player.getUniqueId()).thenRun(() -> plugin.getServer().getScheduler().runTask(plugin, () -> render(player, page)));
    }

    private void render(Player player, int requestedPage) {
        List<Home> list = homes.listCached(player.getUniqueId()); int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE); int page = Math.max(0, Math.min(requestedPage, pages - 1));
        long generation = generations.incrementAndGet(); activeSessions.put(player.getUniqueId(), generation);
        Map<Integer, HomeRef> refs = new LinkedHashMap<>();
        Inventory inventory = plugin.getServer().createInventory(new PageHolder(player.getUniqueId(), generation, page, refs), 54, Text.mm("<gray>Homes</gray> <dark_gray>·</dark_gray> <white>" + (page + 1) + "/" + pages + "</white>"));
        int from = page * PAGE_SIZE, to = Math.min(list.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Home home = list.get(i); int slot = i - from; refs.put(slot, new HomeRef(home.homeId(), home.revision()));
            ItemStack item = new ItemStack(home.nameKey().equalsIgnoreCase(config.get().defaultName()) ? Material.RED_BED : Material.ENDER_PEARL); ItemMeta meta = item.getItemMeta();
            meta.displayName(Text.mm("<white>" + Text.escape(home.displayName()) + "</white>")); List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Text.mm("<gray>World:</gray> <aqua>" + Text.escape(home.worldName()) + "</aqua>"));
            if (config.get().fee() > 0.0D) lore.add(Text.mm("<gray>Fee:</gray> <gold>" + config.get().fee() + "</gold>"));
            lore.add(Text.mm("<green>Left-click</green> <gray>teleport</gray>")); lore.add(Text.mm("<aqua>Right-click</aqua> <gray>manage</gray>")); meta.lore(lore); item.setItemMeta(meta); inventory.setItem(slot, item);
        }
        if (page > 0) inventory.setItem(45, named(Material.ARROW, "<white>Previous page</white>"));
        var limit = homes.limit(player); inventory.setItem(49, named(Material.PAPER, "<gray>Homes:</gray> <white>" + list.size() + "</white> <dark_gray>·</dark_gray> <gray>Limit:</gray> <white>" + (limit.unlimited() ? "∞" : limit.limit()) + "</white>"));
        if (page + 1 < pages) inventory.setItem(53, named(Material.ARROW, "<white>Next page</white>")); player.openInventory(inventory);
    }

    private void openManage(Player player, Home home) {
        long generation = generations.incrementAndGet(); activeSessions.put(player.getUniqueId(), generation);
        Inventory inventory = plugin.getServer().createInventory(new ManageHolder(player.getUniqueId(), generation, home.homeId(), home.revision()), 27, Text.mm("<gray>Manage</gray> <dark_gray>·</dark_gray> <white>" + Text.escape(home.displayName()) + "</white>"));
        inventory.setItem(11, named(Material.ENDER_PEARL, "<green>Teleport</green>")); inventory.setItem(13, named(Material.COMPASS, "<aqua>Update location</aqua>"));
        inventory.setItem(15, named(Material.NAME_TAG, "<white>Rename with /renamehome</white>")); inventory.setItem(22, named(Material.BARRIER, "<red>Delete home</red>")); player.openInventory(inventory);
    }

    private void openDeleteConfirm(Player player, Home home) {
        var ticket = confirmations.stage(player.getUniqueId(), home, Duration.ofSeconds(config.get().deleteConfirmationSeconds()));
        long generation = generations.incrementAndGet(); activeSessions.put(player.getUniqueId(), generation);
        Inventory inventory = plugin.getServer().createInventory(new ConfirmDeleteHolder(player.getUniqueId(), generation, home.homeId(), home.revision(), ticket.token()), 27, Text.mm("<red>Delete</red> <white>" + Text.escape(home.displayName()) + "</white><gray>?</gray>"));
        inventory.setItem(11, named(Material.LIME_WOOL, "<green>Confirm delete</green>")); inventory.setItem(15, named(Material.RED_WOOL, "<red>Cancel</red>")); player.openInventory(inventory);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return; InventoryHolder holder = event.getInventory().getHolder(false); if (!owned(holder)) return; event.setCancelled(true);
        if (!sessionValid(player, holder)) { player.closeInventory(); return; } int slot = event.getRawSlot(); if (slot < 0 || slot >= event.getInventory().getSize()) return;
        if (holder instanceof PageHolder page) {
            if (slot == 45 && page.page > 0) { open(player, page.page - 1); return; } if (slot == 53) { open(player, page.page + 1); return; }
            HomeRef ref = page.refs.get(slot); if (ref == null) return; Home home = resolve(player, ref); if (home == null) { stale(player); return; }
            if (event.isRightClick()) openManage(player, home); else if (player.hasPermission("plexonhomes.use")) { player.closeInventory(); teleports.request(player, home.nameKey()); }
            return;
        }
        if (holder instanceof ManageHolder manage) {
            Home home = resolve(player, new HomeRef(manage.homeId, manage.revision)); if (home == null) { stale(player); return; }
            switch (slot) {
                case 11 -> { if (player.hasPermission("plexonhomes.use")) { player.closeInventory(); teleports.request(player, home.nameKey()); } }
                case 13 -> { if (player.hasPermission("plexonhomes.sethome")) homes.updateHomeLocation(player, home.homeId(), home.revision()).thenAccept(ok -> plugin.getServer().getScheduler().runTask(plugin, () -> { if (!ok) player.sendMessage(Text.mm("<red>Home changed or could not be updated.</red>")); open(player, 0); })); }
                case 15 -> { if (player.hasPermission("plexonhomes.rename")) { player.closeInventory(); player.sendMessage(Text.mm("<gray>Use</gray> <white>/renamehome " + Text.escape(home.nameKey()) + " &lt;new-name&gt;</white>")); } }
                case 22 -> { if (player.hasPermission("plexonhomes.delete")) openDeleteConfirm(player, home); }
                default -> { }
            }
            return;
        }
        if (holder instanceof ConfirmDeleteHolder confirm) {
            Home home = homes.findByIdCached(player.getUniqueId(), confirm.homeId).orElse(null);
            if (slot == 11) {
                if (home == null || !confirmations.consume(player.getUniqueId(), confirm.token, home) || home.revision() != confirm.revision) { player.sendMessage(Text.mm("<red>Delete confirmation expired or the home changed.</red>")); open(player, 0); return; }
                homes.deleteHome(player, home.homeId(), home.revision()).thenAccept(ok -> plugin.getServer().getScheduler().runTask(plugin, () -> { player.sendMessage(Text.mm(ok ? "<green>Home deleted.</green>" : "<red>Home changed or could not be deleted.</red>")); open(player, 0); }));
            } else if (slot == 15) { confirmations.clear(player.getUniqueId()); open(player, 0); }
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) { if (owned(event.getInventory().getHolder(false))) event.setCancelled(true); }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false); Long generation = generation(holder); if (generation != null) activeSessions.remove(event.getPlayer().getUniqueId(), generation);
        if (holder instanceof ConfirmDeleteHolder) confirmations.clear(event.getPlayer().getUniqueId());
    }

    private boolean sessionValid(Player player, InventoryHolder holder) { Long generation = generation(holder); return generation != null && activeSessions.getOrDefault(player.getUniqueId(), -1L).equals(generation); }
    private static Long generation(InventoryHolder holder) { if (holder instanceof PageHolder h) return h.generation; if (holder instanceof ManageHolder h) return h.generation; if (holder instanceof ConfirmDeleteHolder h) return h.generation; return null; }
    private static boolean owned(InventoryHolder holder) { return holder instanceof PageHolder || holder instanceof ManageHolder || holder instanceof ConfirmDeleteHolder; }
    private Home resolve(Player player, HomeRef ref) { Home current = homes.findByIdCached(player.getUniqueId(), ref.homeId).orElse(null); return current != null && current.revision() == ref.revision ? current : null; }
    private void stale(Player player) { player.sendMessage(Text.mm("<gold>This homes view is stale; refreshed safely.</gold>")); open(player, 0); }
    private static ItemStack named(Material material, String miniName) { ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.displayName(Text.mm(miniName)); item.setItemMeta(meta); return item; }

    private record HomeRef(UUID homeId, long revision) {}
    private record PageHolder(UUID playerId, long generation, int page, Map<Integer, HomeRef> refs) implements InventoryHolder { PageHolder { refs = Map.copyOf(refs); } @Override public Inventory getInventory() { return null; } }
    private record ManageHolder(UUID playerId, long generation, UUID homeId, long revision) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
    private record ConfirmDeleteHolder(UUID playerId, long generation, UUID homeId, long revision, UUID token) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
