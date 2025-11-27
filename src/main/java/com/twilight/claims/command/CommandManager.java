package com.twilight.claims.command;

import com.twilight.claims.TwilightWGClaims;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final TwilightWGClaims plugin;
    private final ClaimCommand claimCmd;
    private final TrustCommand trustCmd;

    private final Map<String, String> claimSubCommands = new LinkedHashMap<>();
    private final Map<String, String> mainCommands = new LinkedHashMap<>();

    public CommandManager(TwilightWGClaims plugin,
                          ClaimCommand claimCmd,
                          TrustCommand trustCmd) {

        this.plugin = plugin;
        this.claimCmd = claimCmd;
        this.trustCmd = trustCmd;

        claimSubCommands.put("info",    "twilight.claim.command.claim");
        claimSubCommands.put("list",    "twilight.claim.command.claim");
        claimSubCommands.put("unclaim", "twilight.claim.command.unclaim");
        claimSubCommands.put("points",  "twilight.claim.command.claimpoints");

        mainCommands.put("trust",     "twilight.claim.command.trust");
        mainCommands.put("untrust",   "twilight.claim.command.untrust");
        mainCommands.put("trustlist", "twilight.claim.command.trustlist");
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }

        String name = command.getName().toLowerCase();

        if (name.equals("claim")) {
            return handleClaim(player, args);
        }

        if (mainCommands.containsKey(name)) {
            return handleTrust(player, name, args);
        }

        return false;
    }

    private boolean handleClaim(Player player, String[] args) {
        if (args.length == 0) {
            if (!player.hasPermission("twilight.claim.command.claim")) {
                player.sendMessage("§c[⚐] You don't have permission");
                return true;
            }

            claimCmd.create(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (!claimSubCommands.containsKey(sub)) {
            player.sendMessage("§c[⚐] Unknown subcommand");
            return true;
        }

        String perm = claimSubCommands.get(sub);
        if (!player.hasPermission(perm)) {
            player.sendMessage("§c[⚐] You don't have permission");
            return true;
        }

        claimCmd.handleSubCommand(player, sub, args);
        return true;
    }

    private boolean handleTrust(Player player, String cmdName, String[] args) {
        String perm = mainCommands.get(cmdName);
        if (!player.hasPermission(perm)) {
            player.sendMessage("§c[⚐] You don't have permission");
            return true;
        }

        return trustCmd.handle(player, cmdName, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command command,
                                      String label,
                                      String[] args) {

        if (!(sender instanceof Player player)) return List.of();

        String name = command.getName().toLowerCase();

        if (name.equals("claim")) {
            return tabClaim(player, args);
        }

        if (name.equals("trust") || name.equals("untrust")) {
            return tabTrust(player, name, args);
        }

        if (name.equals("trustlist")) {
            if (!player.hasPermission("twilight.claim.command.trustlist")) {
                return List.of();
            }
            return List.of();
        }

        return List.of();
    }

    private List<String> tabClaim(Player player, String[] args) {
        if (args.length != 1) return List.of();

        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<>();

        for (var entry : claimSubCommands.entrySet()) {
            String sub = entry.getKey();
            String perm = entry.getValue();

            if (player.hasPermission(perm) && sub.startsWith(prefix)) {
                out.add(sub);
            }
        }
        return out;
    }

    private List<String> tabTrust(Player player, String name, String[] args) {
        if (!player.hasPermission(mainCommands.get(name))) {
            return List.of();
        }

        if (args.length == 1) {
            return null;
        }

        return List.of();
    }
}
