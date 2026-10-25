package com.twilight.claims.command;

import com.twilight.claims.command.claim.*;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import java.util.Locale;

public class ClaimCommand {

    private final ClaimCreateCommand createCmd;
    private final ClaimPointsCommand pointsCmd;
    private final ClaimInfoCommand infoCmd;
    private final ClaimListCommand listCmd;
    private final ClaimUnclaimCommand unclaimCmd;
    private final ClaimToolCommand toolCmd;
    private final ClaimDeletionService deletions;

    public ClaimCommand(ClaimsConfig cfg,
                        Messages msg,
                        WGHook wg,
                        ClaimManager claim,
                        RegionVisualizer visualizer,
                        ClaimDeletionService deletions) {

        this.createCmd = new ClaimCreateCommand(cfg, msg, wg, claim, visualizer);
        this.pointsCmd = new ClaimPointsCommand(msg, claim, wg);
        this.infoCmd = new ClaimInfoCommand(msg, wg, claim);
        this.deletions = deletions;
        this.listCmd = new ClaimListCommand(msg, wg, deletions);
        this.unclaimCmd = new ClaimUnclaimCommand(deletions);
        this.toolCmd = new ClaimToolCommand(cfg, msg);
    }

    public void create(Player player) {
        createCmd.execute(player);
    }

    public void handleSubCommand(Player player, String sub, String[] args) {
        switch (sub.toLowerCase(Locale.ROOT)) {
            case "points" -> pointsCmd.execute(player);
            case "info" -> infoCmd.execute(player);
            case "list" -> listCmd.execute(player, args);
            case "unclaim" -> {
                if (args.length == 1) unclaimCmd.execute(player);
                else deletions.request(player, args[1]);
            }
            case "confirm" -> deletions.confirm(player, args[1]);
            case "cancel" -> deletions.cancel(player, args[1]);
            case "tool" -> toolCmd.execute(player, args);
            default -> player.sendMessage(ChatColor.YELLOW + "/claim info | points | list | unclaim | tool");
        }
    }
}
