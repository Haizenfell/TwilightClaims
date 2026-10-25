package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

public final class ClaimPresentation {
    private ClaimPresentation() {}

    public static String centerCoordinate(int min, int max) {
        return BigDecimal.valueOf((long) min + max).divide(BigDecimal.valueOf(2))
                .stripTrailingZeros().toPlainString();
    }

    public static Messages.Placeholder placeholders(Player owner, WGHook.ClaimRef ref,
                                                     WGHook wg, Messages messages) {
        ProtectedRegion region = ref.region();
        String name = wg.getDisplayName(region);
        String world = messages.getWorldName(ref.world());
        String x = centerCoordinate(region.getMinimumPoint().x(), region.getMaximumPoint().x());
        String z = centerCoordinate(region.getMinimumPoint().z(), region.getMaximumPoint().z());
        String area = String.valueOf(wg.getArea(region));
        return text -> text.replace("%id%", name).replace("%region%", name)
                .replace("%owner%", owner.getName()).replace("%world%", world)
                .replace("%x%", x).replace("%z%", z).replace("%area%", area);
    }
}
