package com.twilight.claims.command.claim;

import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

import java.util.List;

public class ClaimListCommand {
    private static final int PAGE_SIZE = 10;
    private final Messages msg;
    private final WGHook wg;
    private final ClaimDeletionService deletions;

    public ClaimListCommand(Messages msg, WGHook wg, ClaimDeletionService deletions) {
        this.msg = msg;
        this.wg = wg;
        this.deletions = deletions;
    }

    public void execute(Player player) {
        execute(player, 1);
    }

    public void execute(Player player, String[] args) {
        if (args.length == 1) {
            execute(player);
            return;
        }
        try {
            if (args.length != 2 || !args[1].matches("[0-9]+")) throw new NumberFormatException();
            execute(player, Integer.parseInt(args[1]));
        } catch (NumberFormatException ex) {
            msg.send(player, "claim_list_usage", text -> text);
        }
    }

    public void execute(Player player, int page) {
        if (page <= 0) {
            msg.send(player, "claim_list_usage", text -> text);
            return;
        }
        List<WGHook.ClaimRef> list = wg.getOwnedClaims(player);
        if (list.isEmpty()) {
            msg.send(player, "claim_list_empty", text -> text);
            return;
        }
        int pages = (list.size() - 1) / PAGE_SIZE + 1;
        if (page > pages) {
            msg.send(player, "claim_list_page_missing", text -> text.replace("%pages%", String.valueOf(pages)));
            return;
        }
        var pagination = (Messages.Placeholder) text -> text.replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(pages)).replace("%owner%", player.getName());
        player.sendMessage(msg.component("claim_list_header", pagination));
        int start = (page - 1) * PAGE_SIZE;
        for (WGHook.ClaimRef ref : list.subList(start, Math.min(list.size(), start + PAGE_SIZE))) {
            var placeholders = ClaimPresentation.placeholders(player, ref, wg, msg);
            Component line = msg.component("claim_list_entry", placeholders);
            if (player.hasPermission("twilight.claim.command.unclaim")) {
                String token = deletions.createRequest(player, ref);
                Component remove = msg.component("claim_list_remove", placeholders)
                        .clickEvent(ClickEvent.runCommand("/claim unclaim " + token))
                        .hoverEvent(HoverEvent.showText(msg.component("claim_list_remove_hover", placeholders)));
                line = line.append(Component.space()).append(remove);
            }
            player.sendMessage(line);
        }
        Component navigation = Component.empty();
        if (page > 1) {
            navigation = msg.component("claim_list_previous", pagination)
                    .clickEvent(ClickEvent.runCommand("/claim list " + (page - 1)));
        }
        if (page < pages) {
            if (page > 1) navigation = navigation.append(Component.space());
            navigation = navigation.append(msg.component("claim_list_next", pagination)
                    .clickEvent(ClickEvent.runCommand("/claim list " + (page + 1))));
        }
        if (pages > 1) player.sendMessage(navigation);
    }
}
