package com.twilight.claims.managed;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.FlagContext;
import com.sk89q.worldguard.protection.flags.InvalidFlagFormat;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.api.ClaimBounds;
import com.twilight.claims.api.ClaimOperationException;
import com.twilight.claims.api.managed.ManagedClaimIdentity;
import com.twilight.claims.api.managed.ManagedClaimState;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.visual.BoundaryStyles;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.twilight.claims.api.ClaimOperationException.Reason.*;

public final class ManagedWorldGuard {
    private static final int COORDINATE_LIMIT = 29_999_984;
    private final WGHook wg;
    private final ClaimsConfig config;
    private final Map<ManagedClaimIdentity, ProtectedRegion> projections = new HashMap<>();
    private final Map<ManagedClaimIdentity, ProtectedRegion> reservations = new HashMap<>();

    public ManagedWorldGuard(WGHook wg, ClaimsConfig config) {
        this.wg = Objects.requireNonNull(wg, "wg");
        this.config = Objects.requireNonNull(config, "config");
    }

    public static String regionId(ManagedClaimIdentity identity) {
        return "tcmanaged_" + identity.instanceId().toString().replace("-", "");
    }

    public boolean isAvailable(UUID worldId) {
        checkThread();
        World world = Bukkit.getWorld(Objects.requireNonNull(worldId, "worldId"));
        return world != null && wg.getManager(world) != null;
    }

    public ProtectedRegion lookup(ManagedClaimIdentity identity) {
        checkThread();
        return manager(identity).getRegion(regionId(identity));
    }

    public boolean ownsProjection(ManagedClaimIdentity identity, ProtectedRegion region) {
        checkThread();
        return projections.get(identity) == region;
    }

    public void lockForShutdown() {
        checkThread();
        for (ProtectedRegion region : projections.values()) {
            region.setOwners(new DefaultDomain());
            region.setMembers(new DefaultDomain());
            region.setFlags(Map.of());
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.INTERACT,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.USE,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.PVP,
                    com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
        }
    }

    public void validate(ManagedClaimIdentity identity, ManagedClaimState state) {
        checkThread();
        Objects.requireNonNull(state, "state");
        RegionManager manager = manager(identity);
        World world = Bukkit.getWorld(identity.worldId());
        ClaimBounds bounds = state.bounds();
        if (state.areaBudget() < 1 || bounds.area() > state.areaBudget()) {
            throw failure(NOT_ENOUGH_POINTS, "Managed claim area exceeds the controller-authorized budget");
        }
        validateBounds(world, bounds);
        ProtectedRegion existing = manager.getRegion(regionId(identity));
        ProtectedRegion expected = projections.get(identity);
        if (existing != null && existing != expected) {
            throw failure(INVALID_STATE, "The WorldGuard region ID is occupied by an unrecognized object");
        }
        if (existing != null && manager.getRegions().values().stream()
                .anyMatch(region -> existing.equals(region.getParent()))) {
            throw failure(HAS_CHILDREN, "Managed claims cannot have children");
        }
        ProtectedCuboidRegion candidate = build(identity, state);
        var conflicts = candidate.getIntersectingRegions(manager.getRegions().values().stream()
                .filter(ProtectedRegion::isPhysicalArea)
                .filter(region -> region != existing && region != reservations.get(identity)).toList());
        if (!conflicts.isEmpty()) {
            throw failure(CONFLICT, "Managed claim conflicts with region " + conflicts.getFirst().getId());
        }
    }

    public void install(ManagedClaimIdentity identity, ManagedClaimState state) {
        validate(identity, state);
        ProtectedRegion region = build(identity, state);
        manager(identity).addRegion(region);
        projections.put(identity, region);
    }

    public void reservePrevious(ManagedClaimIdentity identity, ManagedClaimState state) {
        checkThread();
        if (state == null) return;
        RegionManager manager = manager(identity);
        String id = regionId(identity) + "_reservation";
        ProtectedRegion existing = manager.getRegion(id);
        if (existing != null && existing != reservations.get(identity)) {
            throw failure(INVALID_STATE, "Cannot overwrite an unrecognized reservation object");
        }
        ProtectedRegion reservation = new ProtectedCuboidRegion(id, true, min(state.bounds()), max(state.bounds()));
        manager.addRegion(reservation);
        reservations.put(identity, reservation);
    }

    public void releaseReservation(ManagedClaimIdentity identity) {
        checkThread();
        RegionManager manager = manager(identity);
        ProtectedRegion expected = reservations.get(identity);
        if (expected == null) return;
        ProtectedRegion existing = manager.getRegion(expected.getId());
        if (existing != null && existing != expected)
            throw failure(INVALID_STATE, "Cannot remove an unrecognized reservation object");
        if (existing == expected) manager.removeRegion(expected.getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        reservations.remove(identity);
    }

    public void restore(ManagedClaimIdentity identity, ManagedClaimState state) {
        checkThread();
        if (state == null) {
            remove(identity);
            return;
        }
        RegionManager manager = manager(identity);
        ProtectedRegion existing = manager.getRegion(regionId(identity));
        if (existing != null && existing != projections.get(identity)) {
            throw failure(INVALID_STATE, "Cannot restore over an unrecognized WorldGuard object");
        }
        ProtectedRegion region = build(identity, state);
        manager.addRegion(region);
        projections.put(identity, region);
        releaseReservation(identity);
    }

    public void remove(ManagedClaimIdentity identity) {
        checkThread();
        RegionManager manager = manager(identity);
        ProtectedRegion existing = manager.getRegion(regionId(identity));
        if (existing == null) {
            projections.remove(identity);
            releaseReservation(identity);
            return;
        }
        if (existing != projections.get(identity)) {
            throw failure(INVALID_STATE, "Cannot delete an unrecognized WorldGuard object");
        }
        if (manager.getRegions().values().stream().anyMatch(region -> existing.equals(region.getParent()))) {
            throw failure(HAS_CHILDREN, "Managed claims cannot have children");
        }
        manager.removeRegion(existing.getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        projections.remove(identity);
        releaseReservation(identity);
    }

    public boolean matches(ManagedClaimIdentity identity, ManagedClaimState state) {
        checkThread();
        ProtectedRegion current = lookup(identity);
        if (current == null || current != projections.get(identity) || !current.isTransient()
                || !(current instanceof ProtectedCuboidRegion) || current.getParent() != null
                || !current.getOwners().getUniqueIds().isEmpty() || !current.getOwners().getPlayers().isEmpty()
                || !current.getOwners().getGroups().isEmpty() || !current.getMembers().getPlayers().isEmpty()
                || !current.getMembers().getGroups().isEmpty()
                || !current.getMembers().getUniqueIds().equals(state.members())
                || current.getPriority() != state.priority()
                || !current.getMinimumPoint().equals(min(state.bounds()))
                || !current.getMaximumPoint().equals(max(state.bounds()))) return false;
        try {
            return current.getFlags().equals(build(identity, state).getFlags());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void clearProjections() {
        checkThread();
        for (var entry : Map.copyOf(projections).entrySet()) {
            World world = Bukkit.getWorld(entry.getKey().worldId());
            RegionManager manager = world == null ? null : wg.getManager(world);
            if (manager != null && manager.getRegion(regionId(entry.getKey())) == entry.getValue()) {
                manager.removeRegion(entry.getValue().getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
            }
        }
        projections.clear();
    }

    private ProtectedCuboidRegion build(ManagedClaimIdentity identity, ManagedClaimState state) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId(identity), true,
                min(state.bounds()), max(state.bounds()));
        region.setOwners(new DefaultDomain());
        DefaultDomain members = new DefaultDomain();
        state.members().forEach(members::addPlayer);
        region.setMembers(members);
        region.setPriority(state.priority());
        for (var entry : state.flags().entrySet()) {
            String name = entry.getKey();
            if (name.equals(BoundaryStyles.FLAG_NAME) || name.startsWith("twilightclaims-managed")) {
                throw failure(INVALID_STATE, "Managed metadata and boundary style must use their dedicated fields");
            }
            Flag<?> flag = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (flag == null) throw failure(INVALID_STATE, "Unknown WorldGuard flag: " + name);
            setFlag(region, flag, entry.getValue());
        }
        if (state.boundaryStyle() != null) BoundaryStyles.set(region, state.boundaryStyle());
        return region;
    }

    private <T> void setFlag(ProtectedRegion region, Flag<T> flag, String input) {
        try {
            T value = flag.parseInput(FlagContext.create()
                    .setSender(WorldGuardPlugin.inst().wrapCommandSender(Bukkit.getConsoleSender()))
                    .setInput(input).setObject("region", region).build());
            if (value == null) throw failure(INVALID_STATE,
                    "Omit a flag from the complete desired state to clear it: " + flag.getName());
            Object encoded = flag.marshal(value);
            T detached = flag.unmarshal(encoded);
            if (encoded == null || detached == null) throw failure(INVALID_STATE,
                    "WorldGuard flag cannot be round-tripped: " + flag.getName());
            region.setFlag(flag, detached);
        } catch (InvalidFlagFormat | IllegalArgumentException exception) {
            throw failure(INVALID_STATE, "Invalid WorldGuard flag " + flag.getName() + ": " + exception.getMessage());
        }
    }

    private RegionManager manager(ManagedClaimIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        World world = Bukkit.getWorld(identity.worldId());
        RegionManager manager = world == null ? null : wg.getManager(world);
        if (manager == null) throw failure(WORLD_UNAVAILABLE, "The managed claim world or WorldGuard is unavailable");
        return manager;
    }

    private void validateBounds(World world, ClaimBounds bounds) {
        if (bounds.minX() < -COORDINATE_LIMIT || bounds.maxX() > COORDINATE_LIMIT
                || bounds.minZ() < -COORDINATE_LIMIT || bounds.maxZ() > COORDINATE_LIMIT
                || bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()
                || (long) bounds.maxX() - bounds.minX() + 1 < config.getMinClaimSize()
                || (long) bounds.maxZ() - bounds.minZ() + 1 < config.getMinClaimSize()
                || !world.getWorldBorder().isInside(new Location(world,
                bounds.minX() + 0.5, bounds.minY(), bounds.minZ() + 0.5))
                || !world.getWorldBorder().isInside(new Location(world,
                bounds.maxX() + 0.5, bounds.maxY(), bounds.maxZ() + 0.5))) {
            throw failure(INVALID_BOUNDS, "Managed claim bounds violate minimum size, build height or world border");
        }
    }

    private static BlockVector3 min(ClaimBounds bounds) {
        return BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ());
    }

    private static BlockVector3 max(ClaimBounds bounds) {
        return BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static void checkThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Managed WorldGuard projection requires the server thread");
    }

    private static ClaimOperationException failure(ClaimOperationException.Reason reason, String message) {
        return new ClaimOperationException(reason, message);
    }
}
