package com.plexon.homes.integration;

import com.plexon.homes.api.HomeLimitView;
import com.plexon.homes.config.HomesConfig.Snapshot;
import com.plexon.homes.service.HomeService;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Cache-only PlaceholderAPI expansion. Never initiates storage I/O. */
public final class HomesPlaceholderExpansion extends PlaceholderExpansion {
    private final HomeService homes; private final Supplier<Snapshot> config; private final String version;
    public HomesPlaceholderExpansion(HomeService homes, Supplier<Snapshot> config, String version) { this.homes = homes; this.config = config; this.version = version; }
    @Override public String getIdentifier() { return "plexonhomes"; }
    @Override public String getAuthor() { return "Tonim (ZpkDxGames)"; }
    @Override public String getVersion() { return version; }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer offline, String raw) {
        if (offline == null || raw == null) return "";
        UUID playerId = offline.getUniqueId(); String key = raw.toLowerCase(Locale.ROOT);
        if (!homes.isLoaded(playerId)) return unloaded(key);
        if (key.equals("count")) return Integer.toString(homes.listCached(playerId).size());
        if (key.equals("default")) return homes.findCached(playerId, config.get().defaultName()).map(home -> home.displayName()).orElse("");
        if (key.equals("default_available")) return Boolean.toString(homes.findCached(playerId, config.get().defaultName()).isPresent());
        if (key.startsWith("has_")) return Boolean.toString(homes.findCached(playerId, raw.substring(4)).isPresent());
        Player player = offline.getPlayer();
        if (key.equals("limit")) { if (player == null) return ""; HomeLimitView limit = homes.limit(player); return limit.unlimited() ? "unlimited" : Integer.toString(limit.limit()); }
        if (key.equals("remaining")) {
            if (player == null) return ""; HomeLimitView limit = homes.limit(player); if (limit.unlimited()) return "unlimited";
            return Integer.toString(Math.max(0, limit.limit() - homes.listCached(playerId).size()));
        }
        return null;
    }

    private static String unloaded(String key) {
        if (key.equals("count")) return "0"; if (key.equals("default_available") || key.startsWith("has_")) return "false"; return "";
    }
}
