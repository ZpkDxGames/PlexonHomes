package com.plexon.homes.integration;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyBridge {
    public record ChargeResult(boolean success, double charged, String reason) {}

    private final JavaPlugin plugin;
    private Object economy;
    private Method has;
    private Method withdraw;
    private Method deposit;

    public EconomyBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        refresh();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public final void refresh() {
        economy = null; has = null; withdraw = null; deposit = null;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, plugin.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) economyClass);
            if (registration == null) return;
            economy = registration.getProvider();
            has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        } catch (ReflectiveOperationException ignored) {
            economy = null;
        }
    }

    public boolean available() { return economy != null; }

    public ChargeResult charge(Player player, double amount) {
        if (amount <= 0.0D || player.hasPermission("plexonhomes.teleport.fee.bypass")) return new ChargeResult(true, 0.0D, "bypass");
        if (economy == null) return new ChargeResult(false, 0.0D, "economy-unavailable");
        try {
            boolean enough = (boolean) has.invoke(economy, player, amount);
            if (!enough) return new ChargeResult(false, 0.0D, "insufficient-funds");
            Object response = withdraw.invoke(economy, player, amount);
            Method success = response.getClass().getMethod("transactionSuccess");
            if (!(boolean) success.invoke(response)) return new ChargeResult(false, 0.0D, "withdraw-failed");
            return new ChargeResult(true, amount, "charged");
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Vault economy operation failed: " + exception.getMessage());
            return new ChargeResult(false, 0.0D, "economy-error");
        }
    }

    public void refund(Player player, double amount) {
        if (amount <= 0.0D || economy == null) return;
        try { deposit.invoke(economy, player, amount); }
        catch (ReflectiveOperationException exception) { plugin.getLogger().severe("Unable to refund home teleport fee: " + exception.getMessage()); }
    }
}
