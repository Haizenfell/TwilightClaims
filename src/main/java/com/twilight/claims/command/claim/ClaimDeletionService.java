package com.twilight.claims.command.claim;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.List;
import java.util.stream.Collectors;

public final class ClaimDeletionService implements Listener, AutoCloseable {
    private static final long LIFETIME_MILLIS = 30_000;
    private static final int MAX_ACTIONS_PER_PLAYER = 64;
    private final WGHook wg;
    private final Messages messages;
    private final RegionVisualizer visualizer;
    private final LongSupplier clock;
    private final Map<UUID, LinkedHashMap<String, Request>> requests = new HashMap<>();

    public ClaimDeletionService(WGHook wg, Messages messages, RegionVisualizer visualizer) {
        this(wg, messages, visualizer, () -> System.nanoTime() / 1_000_000L);
    }

    ClaimDeletionService(WGHook wg, Messages messages, RegionVisualizer visualizer, LongSupplier clock) {
        this.wg = wg;
        this.messages = messages;
        this.visualizer = visualizer;
        this.clock = clock;
    }

    public String createRequest(Player player, WGHook.ClaimRef ref) {
        return store(player, ref, Stage.LIST);
    }

    public void requestCurrent(Player player) {
        if (!hasPermission(player)) return;
        ProtectedRegion region = wg.getClaimAt(player, player.getLocation());
        if (region == null) {
            messages.send(player, "no_region", text -> text);
            return;
        }
        if (!wg.isOwner(player, region)) {
            messages.send(player, "not_owner", text -> text);
            return;
        }
        request(player, createRequest(player, new WGHook.ClaimRef(player.getWorld(), region)));
    }

    public void request(Player player, String token) {
        if (!hasPermission(player)) return;
        Request request = consume(player, token, Stage.LIST);
        WGHook.ClaimRef ref = validate(player, request);
        if (ref == null) return;
        var pending = requests.get(player.getUniqueId());
        if (pending != null) pending.values().removeIf(value -> value.stage() == Stage.CONFIRM);
        String confirmation = store(player, ref, Stage.CONFIRM);
        var placeholders = ClaimPresentation.placeholders(player, ref, wg, messages);
        messages.sendList(player, "claim_delete_confirm", placeholders);
        Component confirm = messages.component("claim_delete_confirm_button", placeholders)
                .clickEvent(ClickEvent.runCommand("/claim confirm " + confirmation))
                .hoverEvent(HoverEvent.showText(messages.component("claim_delete_confirm_hover", placeholders)));
        Component cancel = messages.component("claim_delete_cancel_button", placeholders)
                .clickEvent(ClickEvent.runCommand("/claim cancel " + confirmation));
        player.sendMessage(confirm.append(Component.space()).append(cancel));
    }

    public void confirm(Player player, String token) {
        if (!hasPermission(player)) return;
        Request request = consume(player, token, Stage.CONFIRM);
        WGHook.ClaimRef ref = validate(player, request);
        if (ref == null) return;
        var placeholders = ClaimPresentation.placeholders(player, ref, wg, messages);
        if (!wg.removeClaim(player, ref.world().getUID(), ref.region().getId())) return;
        visualizer.clear(player);
        messages.send(player, "region_delete", placeholders);
    }

    public void cancel(Player player, String token) {
        Request request = consume(player, token, Stage.CONFIRM);
        if (request == null) {
            expired(player);
            return;
        }
        messages.send(player, "claim_delete_cancelled", text -> text);
    }

    private boolean hasPermission(Player player) {
        if (player.hasPermission("twilight.claim.command.unclaim")) return true;
        messages.send(player, "no_permission", text -> text);
        return false;
    }

    private String store(Player player, WGHook.ClaimRef ref, Stage stage) {
        long now = clock.getAsLong();
        var pending = requests.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>());
        pending.values().removeIf(value -> now >= value.expiresAt());
        while (pending.size() >= MAX_ACTIONS_PER_PLAYER) pending.remove(pending.keySet().iterator().next());
        String token = UUID.randomUUID().toString();
        pending.put(token, new Request(ref.world().getUID(), ref.region().getId(), ref.region(),
                Snapshot.of(ref.region()), now + LIFETIME_MILLIS, stage));
        return token;
    }

    private Request consume(Player player, String token, Stage stage) {
        var pending = requests.get(player.getUniqueId());
        if (pending == null) return null;
        long now = clock.getAsLong();
        pending.values().removeIf(value -> now >= value.expiresAt());
        Request request = pending.get(token);
        if (request != null && request.stage() == stage) pending.remove(token);
        if (pending.isEmpty()) requests.remove(player.getUniqueId());
        return request != null && request.stage() == stage ? request : null;
    }

    private WGHook.ClaimRef validate(Player player, Request request) {
        if (request == null) {
            expired(player);
            return null;
        }
        var world = Bukkit.getWorld(request.worldId());
        if (world == null) {
            messages.send(player, "claim_world_unavailable", text -> text);
            return null;
        }
        ProtectedRegion current = wg.getClaim(request.worldId(), request.regionId());
        if (current == null || current != request.region() || !Snapshot.of(current).equals(request.snapshot())) {
            messages.send(player, "claim_delete_changed", text -> text);
            return null;
        }
        if (!wg.isOwner(player, current)) {
            messages.send(player, "not_owner", text -> text);
            return null;
        }
        return new WGHook.ClaimRef(world, current);
    }

    private void expired(Player player) {
        messages.send(player, "claim_action_expired", text -> text);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    public void clear(Player player) {
        requests.remove(player.getUniqueId());
    }

    @Override
    public void close() {
        requests.clear();
    }

    private enum Stage { LIST, CONFIRM }
    private record Request(UUID worldId, String regionId, ProtectedRegion region,
                           Snapshot snapshot, long expiresAt, Stage stage) {}

    private record Snapshot(BlockVector3 min, BlockVector3 max,
                            Set<UUID> owners, Set<String> ownerNames, Set<String> ownerGroups,
                            Set<UUID> members, Set<String> memberNames, Set<String> memberGroups,
                            int priority, ProtectedRegion parent, Map<Flag<?>, Object> flags) {
        static Snapshot of(ProtectedRegion region) {
            return new Snapshot(region.getMinimumPoint(), region.getMaximumPoint(),
                    Set.copyOf(region.getOwners().getUniqueIds()), Set.copyOf(region.getOwners().getPlayers()),
                    Set.copyOf(region.getOwners().getGroups()), Set.copyOf(region.getMembers().getUniqueIds()),
                    Set.copyOf(region.getMembers().getPlayers()), Set.copyOf(region.getMembers().getGroups()),
                    region.getPriority(), region.getParent(), snapshotFlags(region.getFlags()));
        }

        private static Map<Flag<?>, Object> snapshotFlags(Map<Flag<?>, Object> flags) {
            Map<Flag<?>, Object> result = new HashMap<>();
            flags.forEach((flag, value) -> result.put(flag, freeze(value)));
            return Map.copyOf(result);
        }

        private static Object freeze(Object value) {
            if (value instanceof Set<?> set) return set.stream().map(Snapshot::freeze).collect(Collectors.toUnmodifiableSet());
            if (value instanceof List<?> list) return list.stream().map(Snapshot::freeze).toList();
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new HashMap<>();
                map.forEach((key, entry) -> copy.put(freeze(key), freeze(entry)));
                return Map.copyOf(copy);
            }
            return value;
        }
    }
}
