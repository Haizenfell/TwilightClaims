package com.twilight.claims.command.trust;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

final class TrustTargetResolver {
    private TrustTargetResolver() {}

    static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }

    static String displayName(OfflinePlayer target, String fallback) {
        return target.getName() == null ? fallback : target.getName();
    }
}
