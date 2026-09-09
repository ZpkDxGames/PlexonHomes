package com.plexon.homes.gui;

import com.plexon.homes.model.Home;
import com.plexon.homes.service.HomeService;
import com.plexon.homes.service.TeleportService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class HomesGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private final HomeService homes;
    private final TeleportService teleports;

    public HomesGui(HomeService homes, TeleportService teleports) { this.homes = homes; this.teleports = teleports; }

    public void open(Player player, int page) {
        homes.ensureLoaded(player.getUniqueId()).thenRun(() -> Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("PlexonHomes"), () -> render(player, page)));
    }

    private void render(Player player, int requestedPage) {
        List<Home> list = homes.listCached(player.getUniqueId());
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Inventory inventory = Bukkit.createInventory(new PageHolder(player.getUniqueId(), page), 54, Component.text("Homes · " + (page + 1) + "/" + pages));
        int from = page * PAGE_SIZE;
        int to = Math.min(list.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            Home home = list.get(i);
            ItemStack item = new ItemStack(home.id().equalsIgnoreCase("home") ? Material.RED_BED : Material.ENDER_PEARL);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(home.displayName()));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(home.worldName()));
            lore.add(Component.text(String.format("%.1f, %.1f, %.1f", home.x(), home.y(), home.z())));
            lore.add(Component.text("Left-click: teleport"));
            lore.add(Component.text("Right-click: manage"));
            meta.lore(lore);
            item.setItemMeta(meta);
            inventory.setItem(i - from, item);
        }
        if (page > 0) inventory.setItem(45, named(Material.ARROW, "Previous page"));
        inventory.setItem(49, named(Material.PAPER, list.size() + " homes · limit " + (homes.limit(player).unlimited() ? "∞" : homes.limit(player).limit())));
        if (page + 1 < pages) inventory.setItem(53, named(Material.ARROW, "Next page"));
        player.openInventory(inventory);
    }

    private void openManage(Player player, Home home) {
        Inventory inventory = Bukkit.createInventory(new ManageHolder(player.getUniqueId(), home.id()), 27, Component.text("Manage · " + home.displayName()));
        inventory.setItem(11, named(Material.ENDER_PEARL, "Teleport"));
        inventory.setItem(13, named(Material.COMPASS, "Overwrite with current location"));
        inventory.setItem(15, named(Material.NAME_TAG, "Rename with /renamehome"));
        inventory.setItem(22, named(Material.BARRIER, "Delete home"));
        player.openInventory(inventory);
    }

    private void openDeleteConfirm(Player player, Home home) {
        Inventory inventory = Bukkit.createInventory(new ConfirmDeleteHolder(player.getUniqueId(), home.id()), 27, Component.text("Delete " + home.displayName() + "?"));
        inventory.setItem(11, named(Material.LIME_WOOL, "Confirm delete"));
        inventory.setItem(15, named(Material.RED_WOOL, "Cancel"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof PageHolder pageHolder) {
            event.setCancelled(true);
            if (!pageHolder.playerId.equals(player.getUniqueId())) return;
            int slot = event.getRawSlot();
            if (slot == 45) { open(player, pageHolder.page - 1); return; }
            if (slot == 53) { open(player, pageHolder.page + 1); return; }
            if (slot < 0 || slot >= PAGE_SIZE) return;
            List<Home> list = homes.listCached(player.getUniqueId());
            int index = pageHolder.page * PAGE_SIZE + slot;
            if (index >= list.size()) return;
            Home home = list.get(index);
            if (event.isRightClick()) openManage(player, home); else { player.closeInventory(); teleports.request(player, home.id()); }
            return;
        }
        if (holder instanceof ManageHolder manage) {
            event.setCancelled(true);
            Home home = homes.findCached(player.getUniqueId(), manage.homeId).orElse(null);
            if (home == null) { player.closeInventory(); return; }
            switch (event.getRawSlot()) {
                case 11 -> { player.closeInventory(); teleports.request(player, home.id()); }
                case 13 -> { homes.setHomeNow(player, home.id()); open(player, 0); }
                case 15 -> { player.closeInventory(); player.sendMessage(Component.text("Use /renamehome " + home.id() + " <new-name>")); }
                case 22 -> openDeleteConfirm(player, home);
                default -> { }
            }
            return;
        }
        if (holder instanceof ConfirmDeleteHolder confirm) {
            event.setCancelled(true);
            if (event.getRawSlot() == 11) { homes.deleteHomeNow(player, confirm.homeId); open(player, 0); }
            else if (event.getRawSlot() == 15) open(player, 0);
        }
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta(); meta.displayName(Component.text(name)); item.setItemMeta(meta); return item;
    }

    private record PageHolder(UUID playerId, int page) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
    private record ManageHolder(UUID playerId, String homeId) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
    private record ConfirmDeleteHolder(UUID playerId, String homeId) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
