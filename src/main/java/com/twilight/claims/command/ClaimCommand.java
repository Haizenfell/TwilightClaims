package com.twilight.claims.command;

import com.twilight.claims.TwilightClaims;
import com.twilight.claims.command.claim.*;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class ClaimCommand {

    private final ClaimCreateCommand createCmd;
    private final ClaimPointsCommand pointsCmd;
    private final ClaimInfoCommand infoCmd;
    private final ClaimListCommand listCmd;
    private final ClaimUnclaimCommand unclaimCmd;

    public ClaimCommand(TwilightClaims plugin,
                        ClaimsConfig cfg,
                        Messages msg,
                        WGHook wg,
                        ClaimManager claim,
                        RegionVisualizer visualizer) {

        this.createCmd = new ClaimCreateCommand(cfg, msg, wg, claim, visualizer);
        this.pointsCmd = new ClaimPointsCommand(cfg, msg, claim, wg);
        this.infoCmd = new ClaimInfoCommand(msg, wg, claim);
        this.listCmd = new ClaimListCommand(msg, wg);
        this.unclaimCmd = new ClaimUnclaimCommand(msg, wg);
    }

    public void create(Player player) {
        createCmd.execute(player);
    }

    public void handleSubCommand(Player player, String sub, String[] args) {
        switch (sub.toLowerCase()) {
            case "points" -> pointsCmd.execute(player);
            case "info" -> infoCmd.execute(player);
            case "list" -> listCmd.execute(player);
            case "unclaim" -> unclaimCmd.execute(player);
            default -> player.sendMessage(ChatColor.YELLOW + "/claim info | points | list | unclaim");
        }
    }
}
