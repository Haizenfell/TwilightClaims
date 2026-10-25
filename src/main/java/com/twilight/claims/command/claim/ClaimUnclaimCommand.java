package com.twilight.claims.command.claim;

import org.bukkit.entity.Player;

public class ClaimUnclaimCommand {

    private final ClaimDeletionService deletions;

    public ClaimUnclaimCommand(ClaimDeletionService deletions) {
        this.deletions = deletions;
    }

    public void execute(Player player) {
        deletions.requestCurrent(player);
    }
}
