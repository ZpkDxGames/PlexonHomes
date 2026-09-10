package com.plexon.homes.integration;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyBridge {
    public record ChargeResult(boolean success, double charged, String reason) {}
    private final JavaPlugin plugin; private Object economy; private Method has; private Method withdraw; private Method deposit;
    public EconomyBridge(JavaPlugin plugin) { this.plugin = plugin; refresh(); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public final void refresh() {
        economy = null; has = null; withdraw = null; deposit = null;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, plugin.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) economyClass); if (registration == null) return;
            economy = registration.getProvider(); has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class); deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        } catch (ReflectiveOperationException ignored) { economy = null; }
    }
    public boolean available() { return economy != null; }

    public ChargeResult charge(Player player, double amount) {
        if (amount <= 0.0D || player.hasPermission("plexonhomes.teleport.fee.bypass")) return new ChargeResult(true, 0.0D, "bypass");
        if (economy == null) return new ChargeResult(false, 0.0D, "economy-unavailable");
        try {
            if (!(boolean) has.invoke(economy, player, amount)) return new ChargeResult(false, 0.0D, "insufficient-funds");
            Object response = withdraw.invoke(economy, player, amount); if (!transactionSuccess(response)) return new ChargeResult(false, 0.0D, "withdraw-failed");
            return new ChargeResult(true, amount, "charged");
        } catch (ReflectiveOperationException exception) { plugin.getLogger().warning("Vault economy operation failed: " + exception.getMessage()); return new ChargeResult(false, 0.0D, "economy-error"); }
    }

    /** Returns true only when Vault confirms the compensating deposit. OfflinePlayer keeps compensation possible across logout races. */
    public boolean refund(OfflinePlayer player, double amount) {
        if (amount <= 0.0D) return true; if (economy == null || player == null) return false;
        try { return transactionSuccess(deposit.invoke(economy, player, amount)); }
        catch (ReflectiveOperationException exception) { plugin.getLogger().severe("Unable to refund home teleport fee: " + exception.getMessage()); return false; }
    }

    private static boolean transactionSuccess(Object response) throws ReflectiveOperationException { if (response == null) return false; Method success = response.getClass().getMethod("transactionSuccess"); return (boolean) success.invoke(response); }
}
