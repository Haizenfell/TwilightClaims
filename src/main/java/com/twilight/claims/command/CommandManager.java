package com.twilight.claims.command;

import com.twilight.claims.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final ClaimCommand claimCmd;
    private final TrustCommand trustCmd;
    private final Messages messages;

    private final Map<String, String> claimSubCommands = new LinkedHashMap<>();
    private final Map<String, String> mainCommands = new LinkedHashMap<>();

    public CommandManager(ClaimCommand claimCmd,
                          TrustCommand trustCmd) {
        this(claimCmd, trustCmd, null);
    }

    public CommandManager(ClaimCommand claimCmd, TrustCommand trustCmd, Messages messages) {

        this.claimCmd = claimCmd;
        this.trustCmd = trustCmd;
        this.messages = messages;

        claimSubCommands.put("info",    "twilight.claim.command.claim");
        claimSubCommands.put("list",    "twilight.claim.command.claim");
        claimSubCommands.put("unclaim", "twilight.claim.command.unclaim");
        claimSubCommands.put("points",  "twilight.claim.command.claimpoints");
        claimSubCommands.put("tool",    "twilight.claim.command.claimtool");
        claimSubCommands.put("confirm", "twilight.claim.command.unclaim");
        claimSubCommands.put("cancel",  "twilight.claim.command.unclaim");

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
            sender.sendMessage(message("only_players", "Player only."));
            return true;
        }

        String name = command.getName().toLowerCase(Locale.ROOT);

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
                player.sendMessage(message("no_permission", "§c[⚐] You don't have permission"));
                return true;
            }

            claimCmd.create(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!claimSubCommands.containsKey(sub)) {
            player.sendMessage(message("unknown_subcommand", "§c[⚐] Unknown subcommand"));
            return true;
        }

        String perm = claimSubCommands.get(sub);
        if (!player.hasPermission(perm)) {
            player.sendMessage(message("no_permission", "§c[⚐] You don't have permission"));
            return true;
        }

        boolean validArguments = switch (sub) {
            case "tool", "confirm", "cancel" -> args.length == 2;
            case "list", "unclaim" -> args.length == 1 || args.length == 2;
            default -> args.length == 1;
        };
        if (!validArguments) {
            String suffix = switch (sub) {
                case "tool" -> " <shovel|stick>";
                case "list" -> " [page]";
                case "confirm", "cancel" -> " <token>";
                case "unclaim" -> " [token]";
                default -> "";
            };
            player.sendMessage("§c[⚐] /claim " + sub + suffix);
            return true;
        }

        claimCmd.handleSubCommand(player, sub, args);
        return true;
    }

    private boolean handleTrust(Player player, String cmdName, String[] args) {
        String perm = mainCommands.get(cmdName);
        if (!player.hasPermission(perm)) {
            player.sendMessage(message("no_permission", "§c[⚐] You don't have permission"));
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

        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("claim")) {
            return tabClaim(player, args);
        }

        if (mainCommands.containsKey(name)) {
            return tabTrust(player, name, args);
        }

        return List.of();
    }

    private List<String> tabClaim(Player player, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("tool")) {
            if (!player.hasPermission("twilight.claim.command.claimtool")) {
                return List.of();
            }

            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("shovel", "stick").stream()
                    .filter(type -> type.startsWith(prefix))
                    .toList();
        }

        if (args.length != 1) return List.of();

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();

        for (var entry : claimSubCommands.entrySet()) {
            String sub = entry.getKey();
            if (sub.equals("confirm") || sub.equals("cancel")) continue;
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
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().filter(player::canSee)
                    .map(Player::getName)
                    .filter(nameValue -> nameValue.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }

        return List.of();
    }

    private String message(String key, String fallback) {
        return messages == null ? fallback : messages.msg(key);
    }
}
