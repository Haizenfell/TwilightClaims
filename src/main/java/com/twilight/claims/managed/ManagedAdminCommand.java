package com.twilight.claims.managed;

import com.twilight.claims.api.managed.ManagedClaimIdentity;
import com.twilight.claims.api.managed.ManagedClaimsApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public final class ManagedAdminCommand implements CommandExecutor {
    private final ManagedClaimsApi api;
    public ManagedAdminCommand(ManagedClaimsApi api) { this.api = api; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("twilight.claim.managed.admin")) {
            sender.sendMessage("You do not have permission."); return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                api.getDiagnostics().thenAccept(d -> sender.sendMessage("Managed claims: ready=" + d.ready()
                        + ", server=" + api.getServerId() + ", active=" + d.activeClaims() + ", queued=" + d.queuedOperations()
                        + ", recovery=" + d.pendingRecovery() + ", tombstones=" + d.tombstones()
                        + ", trusted=" + d.trustedClaims() + ", controllers=" + d.controllers() + ", lastError=" + d.lastError()));
            } else if (args[0].equalsIgnoreCase("list") && args.length >= 2) {
                int offset = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
                api.listManagedClaims(args[1], offset, 20).thenAccept(page -> {
                    sender.sendMessage("Status=" + page.status() + ", nextOffset=" + page.nextOffset());
                    page.claims().forEach(c -> sender.sendMessage(c.identity().externalId() + " instance="
                            + c.identity().instanceId() + " world=" + c.identity().worldId()
                            + " revision=" + c.revision() + " state=" + c.lifecycle()));
                });
            } else if ((args[0].equalsIgnoreCase("inspect") || args[0].equalsIgnoreCase("reconcile")) && args.length == 5) {
                var identity = new ManagedClaimIdentity(args[1], UUID.fromString(args[2]), UUID.fromString(args[3]),
                        api.getServerId(), UUID.fromString(args[4]));
                var stage = args[0].equalsIgnoreCase("reconcile") ? api.reconcileManagedClaim(identity) : api.lookupManagedClaim(identity);
                stage.thenAccept(result -> sender.sendMessage(result.status() + ": " + result.message()));
            } else {
                sender.sendMessage("/claimsmanaged status | list <controller> [offset] | inspect|reconcile <controller> <externalUUID> <instanceUUID> <worldUUID>");
            }
        } catch (IllegalArgumentException error) { sender.sendMessage("Invalid arguments: " + error.getMessage()); }
        return true;
    }
}
