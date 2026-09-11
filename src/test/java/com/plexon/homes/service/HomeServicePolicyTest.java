package com.plexon.homes.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.plexon.homes.api.HomeLimitView;
import com.plexon.homes.config.HomesConfig;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.Test;

class HomeServicePolicyTest {
    @Test void permissionDeniedIsRejectedBelowCommandsAndGui() {
        HomeService service = new HomeService(null, null, null, HomeServicePolicyTest::snapshot);
        Player player = player(Set.of());
        assertFalse(service.setHome(player, "home").join());
        assertFalse(service.deleteHome(player, "home").join());
        assertFalse(service.deleteHome(player, UUID.randomUUID(), 1L).join());
        assertFalse(service.renameHome(player, "home", "base").join());
        assertFalse(service.updateHomeLocation(player, UUID.randomUUID(), 1L).join());
    }

    @Test void deterministicHighestApplicableLimitWins() {
        HomeService service = new HomeService(null, null, null, HomeServicePolicyTest::snapshot);
        Player player = player(Set.of("plexonhomes.limit.2", "plexonhomes.limit.10", "plexonhomes.limit.5"));
        HomeLimitView limit = service.limit(player);
        assertFalse(limit.unlimited());
        assertEquals(10, limit.limit());
    }

    @Test void defaultAndUnlimitedLimitsAreAuthoritative() {
        HomeService service = new HomeService(null, null, null, HomeServicePolicyTest::snapshot);
        assertEquals(3, service.limit(player(Set.of())).limit());
        HomeLimitView unlimited = service.limit(player(Set.of("plexonhomes.limit.unlimited")));
        assertTrue(unlimited.unlimited());
    }

    @Test void exactLimitBlocksOnlyNewCreationAndNeverDeletesExistingHomes() {
        HomeLimitView two = new HomeLimitView(2, false, "test");
        assertTrue(HomeService.canCreateAtLimit(1, two));
        assertFalse(HomeService.canCreateAtLimit(2, two));
        assertFalse(HomeService.canCreateAtLimit(5, two));
        assertTrue(HomeService.canCreateAtLimit(5000, HomeLimitView.unlimited("test")));
    }

    private static HomesConfig.Snapshot snapshot() {
        var allWorlds = new HomesConfig.WorldPolicy(HomesConfig.PolicyMode.BLACKLIST, Set.of());
        return new HomesConfig.Snapshot(2, "home", 24, false, 3,
                "plexonhomes.limit.", "plexonhomes.limit.unlimited", allWorlds, allWorlds,
                3, 10, true, 0.01D, true, 0.0D, true, true, 3, 4, 15, "PRIMARY");
    }

    private static Player player(Set<String> permissions) {
        Player[] holder = new Player[1];
        UUID uuid = UUID.randomUUID();
        holder[0] = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hasPermission" -> args != null && args.length > 0 && args[0] instanceof String permission && permissions.contains(permission);
                case "getEffectivePermissions" -> permissions.stream()
                        .filter(permission -> !permission.equals("plexonhomes.limit.unlimited"))
                        .map(permission -> new PermissionAttachmentInfo(holder[0], permission, null, true))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                case "getUniqueId" -> uuid;
                case "getName" -> "PolicyTest";
                case "isOnline" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "PolicyTestPlayer";
                default -> defaultValue(method.getReturnType());
            };
        });
        return holder[0];
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
