package com.plexon.homes.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.plexon.homes.service.HomeService;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class HomesPlaceholderExpansionTest {
    @Test void unloadedPlaceholdersAreCacheOnlyAndNeverRequireStorage() {
        HomeService storageLess = new HomeService(null, null, null, () -> null);
        HomesPlaceholderExpansion expansion = new HomesPlaceholderExpansion(storageLess, () -> null, "2.0.0-rc.1");
        OfflinePlayer player = offline(UUID.randomUUID());

        assertEquals("0", expansion.onRequest(player, "count"));
        assertEquals("false", expansion.onRequest(player, "has_home"));
        assertEquals("false", expansion.onRequest(player, "default_available"));
        assertEquals("", expansion.onRequest(player, "default"));
    }

    private static OfflinePlayer offline(UUID uuid) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "PlaceholderTest";
                case "isOnline" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PlaceholderTestPlayer";
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }
}
