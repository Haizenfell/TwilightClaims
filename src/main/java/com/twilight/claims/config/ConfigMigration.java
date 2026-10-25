package com.twilight.claims.config;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ConfigMigration {
    private static final Map<String, Object> LEGACY_DEFAULTS = Map.ofEntries(
            Map.entry("messages.claim_list_header", "&9[⚐] &7Your claims:"),
            Map.entry("messages.claim_list_entry", "&7- &a%id% &7(&e%area% &7blocks)"),
            Map.entry("messages.region_create", "&9[⚐] &7Claim &e%id% &acreated with size &e%area% &ablocks"),
            Map.entry("messages.region_delete", "&9[⚐] &7Claim deleted. Freed &e%area% &7claim points"),
            Map.entry("messages.claim_points_entry", "&7Claim &e%index%&7: (&a%id%&7): &e%area% &7points"),
            Map.entry("messages.trust_list_header_region", "&9[⚐] &7Trusted players for claim &a%region%&7:"),
            Map.entry("messages.trust_add", "&9[⚐] &7Player &e%player% &awas added to trusted"),
            Map.entry("messages.trust_remove", "&9[⚐] &7Player &e%player% &awas removed from trusted"),
            Map.entry("messages.region_create_conflict",
                    "&c[⚐] Cannot create a claim — it overlaps with %owner%&c's claim!"),
            Map.entry("stick-tool.messages.nearest-format", "&eNearest region: &f%owner% &7(%distance%m) %arrow%"),
            Map.entry("messages.region_info", List.of(
                    "&9[⚐] &7Owner: &e%owner%",
                    "&9[⚐] &7Area: &a%area%",
                    "&9[⚐] &7Claim points: &e%used%&6/&e%total% &7(free: %free%)"))
    );

    private ConfigMigration() {}

    public static boolean migrate(FileConfiguration config) {
        Configuration defaults = config.getDefaults();
        if (defaults == null) return false;
        boolean changed = false;
        for (var entry : LEGACY_DEFAULTS.entrySet()) {
            String path = entry.getKey();
            Object current = config.get(path);
            Object replacement = defaults.get(path);
            if (config.isSet(path) && Objects.equals(current, entry.getValue())
                    && replacement != null && !Objects.equals(current, replacement)) {
                config.set(path, replacement instanceof List<?> list ? List.copyOf(list) : replacement);
                changed = true;
            }
        }
        return changed;
    }
}
