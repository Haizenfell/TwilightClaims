package com.twilight.claims.manager;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.api.ClaimBounds;
import com.twilight.claims.api.ClaimKey;
import com.twilight.claims.api.ClaimOperationException;
import com.twilight.claims.api.BoundaryStyle;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.managed.ManagedRegions;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import com.twilight.claims.visual.BoundaryStyles;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.twilight.claims.api.ClaimOperationException.Reason.*;

public final class ClaimOperations {
    private static final int COORDINATE_LIMIT = 29_999_984;
    private final WGHook wg;
    private final ClaimsConfig config;
    private final ClaimManager points;

    public ClaimOperations(WGHook wg, ClaimsConfig config, ClaimManager points) {
        this.wg = Objects.requireNonNull(wg);
        this.config = Objects.requireNonNull(config);
        this.points = Objects.requireNonNull(points);
    }

    public WGHook.ClaimRef create(UUID owner, World world, ClaimBounds bounds, boolean enforcePoints) {
        return create(owner, world, bounds, null, enforcePoints);
    }

    public WGHook.ClaimRef create(UUID owner, World world, ClaimBounds bounds, BoundaryStyle style,
                                  boolean enforcePoints) {
        return create(owner, world, bounds, style, enforcePoints, null);
    }

    public WGHook.ClaimRef createChild(UUID owner, ClaimKey parentKey, ClaimBounds bounds,
                                      BoundaryStyle style, boolean enforcePoints) {
        WGHook.ClaimRef parent = resolve(parentKey);
        return create(owner, parent.world(), bounds, style, enforcePoints, parent.region());
    }

    private WGHook.ClaimRef create(UUID owner, World world, ClaimBounds bounds, BoundaryStyle style,
                                   boolean enforcePoints, ProtectedRegion parent) {
        checkThread();
        Objects.requireNonNull(owner, "owner");
        RegionManager manager = manager(world);
        validateBounds(world, bounds);
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(wg.newRegionId(owner, world), min(bounds), max(bounds));
        if (parent != null) {
            if (!(parent instanceof ProtectedCuboidRegion)
                    || !parent.contains(min(bounds)) || !parent.contains(max(bounds))) {
                throw failure(INVALID_BOUNDS, "The parent must contain the child's entire volume");
            }
            try {
                region.setParent(parent);
            } catch (ProtectedRegion.CircularInheritanceException exception) {
                throw failure(INVALID_STATE, "Region inheritance cannot be circular");
            }
        }
        checkConflict(world, bounds, parent == null ? null : region);
        if (enforcePoints) checkPoints(owner, area(bounds));
        region.getOwners().addPlayer(owner);
        if (style != null) BoundaryStyles.set(region, style);
        manager.addRegion(region);
        if (!wg.save(world)) {
            manager.removeRegion(region.getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
            throw failure(SAVE_FAILED, "Could not persist the new claim");
        }
        return new WGHook.ClaimRef(world, region);
    }

    public WGHook.ClaimRef resize(ClaimKey key, ClaimBounds bounds, boolean enforcePoints) {
        WGHook.ClaimRef ref = resolve(key);
        validateBounds(ref.world(), bounds);
        checkConflict(ref.world(), bounds, ref.region());
        long delta = area(bounds) - wg.getArea(ref.region());
        if (enforcePoints && delta > 0) checkPoints(owner(ref.region()), delta);
        ProtectedCuboidRegion replacement = new ProtectedCuboidRegion(ref.region().getId(), min(bounds), max(bounds));
        replacement.copyFrom(ref.region());
        return replace(ref, replacement);
    }

    public void delete(ClaimKey key) {
        WGHook.ClaimRef ref = resolve(key);
        RegionManager manager = manager(ref.world());
        if (manager.getRegions().values().stream().anyMatch(region -> ref.region().equals(region.getParent()))) {
            throw failure(HAS_CHILDREN, "A claim with child regions cannot be deleted");
        }
        manager.removeRegion(ref.region().getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        if (!wg.save(ref.world())) {
            manager.addRegion(ref.region());
            throw failure(SAVE_FAILED, "Could not persist claim deletion");
        }
    }

    public WGHook.ClaimRef transferOwner(ClaimKey key, UUID newOwner, boolean enforcePoints) {
        Objects.requireNonNull(newOwner, "newOwner");
        WGHook.ClaimRef ref = resolve(key);
        UUID previousOwner = owner(ref.region());
        if (previousOwner.equals(newOwner)) return ref;
        if (enforcePoints) checkPoints(newOwner, wg.getArea(ref.region()));
        var number = ClaimIdentity.numberOf(ref.region().getId());
        boolean occupied = number.isPresent() && wg.getOwnedClaims(newOwner).stream()
                .anyMatch(owned -> ClaimIdentity.numberOf(owned.region().getId()).orElse(-1) == number.getAsLong());
        String id = occupied || number.isEmpty() ? wg.newRegionId(newOwner, ref.world()) : ref.region().getId();
        ProtectedCuboidRegion replacement = copy(ref.region(), id);
        DefaultDomain owners = new DefaultDomain();
        owners.addPlayer(newOwner);
        replacement.setOwners(owners);
        replacement.getMembers().removePlayer(newOwner);
        return replace(ref, replacement);
    }

    public WGHook.ClaimRef addMember(ClaimKey key, UUID member) {
        Objects.requireNonNull(member, "member");
        return mutate(key, region -> {
            if (!region.getOwners().contains(member)) region.getMembers().addPlayer(member);
        });
    }

    public WGHook.ClaimRef removeMember(ClaimKey key, UUID member) {
        Objects.requireNonNull(member, "member");
        return mutate(key, region -> region.getMembers().removePlayer(member));
    }

    public WGHook.ClaimRef clearMembers(ClaimKey key) {
        return mutate(key, region -> region.setMembers(new DefaultDomain()));
    }

    public <T> WGHook.ClaimRef setFlag(ClaimKey key, Flag<T> flag, T value) {
        checkThread();
        Objects.requireNonNull(flag, "flag");
        if (WorldGuard.getInstance().getFlagRegistry().get(flag.getName()) != flag) {
            throw failure(INVALID_STATE, "The flag must be registered with WorldGuard: " + flag.getName());
        }
        T copiedValue = null;
        if (value != null) {
            try {
                Object marshalled = flag.marshal(value);
                if (marshalled == null) throw new IllegalArgumentException("Flag cannot serialize this value");
                T decoded = flag.unmarshal(marshalled);
                if (decoded == null) throw new IllegalArgumentException("Flag cannot deserialize this value");
                copiedValue = immutableValue(decoded);
            } catch (RuntimeException exception) {
                throw failure(INVALID_STATE, "Invalid value for WorldGuard flag " + flag.getName());
            }
        }
        T storedValue = copiedValue;
        return mutate(key, region -> region.setFlag(flag, storedValue));
    }

    public WGHook.ClaimRef clearFlags(ClaimKey key) {
        return mutate(key, region -> region.setFlags(java.util.Map.of()));
    }

    public WGHook.ClaimRef setPriority(ClaimKey key, int priority) {
        return mutate(key, region -> region.setPriority(priority));
    }

    public WGHook.ClaimRef setParent(ClaimKey key, ClaimKey parentKey) {
        WGHook.ClaimRef ref = resolve(key);
        ProtectedRegion parent = null;
        if (parentKey != null) {
            if (!key.worldId().equals(parentKey.worldId())) {
                throw failure(INVALID_STATE, "A parent must be in the same world as its child");
            }
            parent = resolve(parentKey).region();
            Set<ProtectedRegion> visited = new HashSet<>();
            for (ProtectedRegion ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
                if (!visited.add(ancestor) || ancestor.equals(ref.region())) {
                    throw failure(INVALID_STATE, "Region inheritance cannot be circular");
                }
            }
            if (!(parent instanceof ProtectedCuboidRegion)
                    || !parent.contains(ref.region().getMinimumPoint())
                    || !parent.contains(ref.region().getMaximumPoint())) {
                throw failure(INVALID_BOUNDS, "The parent must contain the child's entire volume");
            }
        }
        ProtectedRegion newParent = parent;
        return mutate(key, region -> {
            try {
                region.setParent(newParent);
            } catch (ProtectedRegion.CircularInheritanceException exception) {
                throw failure(INVALID_STATE, "Region inheritance cannot be circular");
            }
        });
    }

    public WGHook.ClaimRef resolve(ClaimKey key) {
        checkThread();
        Objects.requireNonNull(key, "key");
        World world = Bukkit.getWorld(key.worldId());
        RegionManager manager = manager(world);
        ProtectedRegion region = manager.getRegion(key.regionId());
        if (ManagedRegions.isManagedId(key.regionId()) || ManagedRegions.isManaged(region)) {
            throw failure(INVALID_STATE, "This claim is managed by an external controller; use ManagedClaimsApi");
        }
        if (!wg.isClaim(region)) throw failure(NOT_FOUND, "Claim not found: " + key.regionId());
        return new WGHook.ClaimRef(world, region);
    }

    private WGHook.ClaimRef mutate(ClaimKey key, Consumer<ProtectedRegion> mutation) {
        WGHook.ClaimRef ref = resolve(key);
        ProtectedCuboidRegion replacement = copy(ref.region(), ref.region().getId());
        mutation.accept(replacement);
        return replace(ref, replacement);
    }

    private WGHook.ClaimRef replace(WGHook.ClaimRef ref, ProtectedCuboidRegion replacement) {
        RegionManager manager = manager(ref.world());
        ProtectedRegion original = ref.region();
        List<ProtectedRegion> children = manager.getRegions().values().stream()
                .filter(region -> original.equals(region.getParent())).toList();
        boolean renamed = !replacement.getId().equals(original.getId());
        manager.addRegion(replacement);
        if (renamed) {
            try {
                for (ProtectedRegion child : children) child.setParent(replacement);
            } catch (ProtectedRegion.CircularInheritanceException exception) {
                rollbackRename(manager, original, replacement, children);
                throw failure(INVALID_STATE, "Could not retain child-region inheritance");
            }
            manager.removeRegion(original.getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        }
        if (!wg.save(ref.world())) {
            if (renamed) rollbackRename(manager, original, replacement, children);
            else manager.addRegion(original);
            throw failure(SAVE_FAILED, "Could not persist claim changes");
        }
        return new WGHook.ClaimRef(ref.world(), replacement);
    }

    private void rollbackRename(RegionManager manager, ProtectedRegion original, ProtectedRegion replacement,
                                List<ProtectedRegion> children) {
        manager.addRegion(original);
        for (ProtectedRegion child : children) {
            try {
                child.setParent(original);
            } catch (ProtectedRegion.CircularInheritanceException exception) {
                throw failure(INVALID_STATE, "Could not restore child-region inheritance");
            }
        }
        manager.removeRegion(replacement.getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
    }

    private ProtectedCuboidRegion copy(ProtectedRegion region, String id) {
        ProtectedCuboidRegion copy = new ProtectedCuboidRegion(id, region.getMinimumPoint(), region.getMaximumPoint());
        copy.copyFrom(region);
        copy.setOwners(new DefaultDomain(region.getOwners()));
        copy.setMembers(new DefaultDomain(region.getMembers()));
        Map<Flag<?>, Object> flags = new HashMap<>();
        region.getFlags().forEach((flag, value) -> flags.put(flag, immutableValue(value)));
        copy.setFlags(flags);
        return copy;
    }

    private void validateBounds(World world, ClaimBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.minX() < -COORDINATE_LIMIT || bounds.maxX() > COORDINATE_LIMIT
                || bounds.minZ() < -COORDINATE_LIMIT || bounds.maxZ() > COORDINATE_LIMIT
                || bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()
                || bounds.minX() > bounds.maxX() || bounds.minY() > bounds.maxY() || bounds.minZ() > bounds.maxZ()
                || (long) bounds.maxX() - bounds.minX() + 1 < config.getMinClaimSize()
                || (long) bounds.maxZ() - bounds.minZ() + 1 < config.getMinClaimSize()
                || !world.getWorldBorder().isInside(new Location(world, bounds.minX() + 0.5, bounds.minY(), bounds.minZ() + 0.5))
                || !world.getWorldBorder().isInside(new Location(world, bounds.maxX() + 0.5, bounds.maxY(), bounds.maxZ() + 0.5))) {
            throw failure(INVALID_BOUNDS, "Claim bounds violate the minimum size, world height or world border");
        }
    }

    private void checkConflict(World world, ClaimBounds bounds, ProtectedRegion ignored) {
        WGHook.ProximityResult conflict = wg.checkProximity(world, min(bounds), max(bounds), ignored);
        if (conflict != null) throw failure(CONFLICT, "Claim conflicts with region " + conflict.getOther().getId());
    }

    private void checkPoints(UUID owner, long required) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline()) {
            throw failure(INVALID_OWNER, "Point validation requires the owner to be online");
        }
        if (!points.canExpand(player, required)) throw failure(NOT_ENOUGH_POINTS, "The owner has insufficient claim points");
    }

    private UUID owner(ProtectedRegion region) {
        if (region.getOwners().getUniqueIds().size() != 1 || !region.getOwners().getPlayers().isEmpty()
                || !region.getOwners().getGroups().isEmpty()) {
            throw failure(INVALID_OWNER, "The claim must have exactly one UUID owner");
        }
        return region.getOwners().getUniqueIds().iterator().next();
    }

    private RegionManager manager(World world) {
        if (world == null || Bukkit.getWorld(world.getUID()) != world) {
            throw failure(WORLD_UNAVAILABLE, "The claim world must be loaded");
        }
        RegionManager manager = wg.getManager(world);
        if (manager == null) throw failure(WORLD_UNAVAILABLE, "WorldGuard is unavailable for this world");
        return manager;
    }

    @SuppressWarnings("unchecked")
    private static <T> T immutableValue(T value) {
        if (value instanceof Set<?> set) return (T) set.stream().map(ClaimOperations::immutableValue)
                .collect(Collectors.toUnmodifiableSet());
        if (value instanceof List<?> list) return (T) list.stream().map(ClaimOperations::immutableValue).toList();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new HashMap<>();
            map.forEach((key, entry) -> copy.put(immutableValue(key), immutableValue(entry)));
            return (T) Map.copyOf(copy);
        }
        return value;
    }

    private static BlockVector3 min(ClaimBounds bounds) {
        return BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ());
    }

    private static BlockVector3 max(ClaimBounds bounds) {
        return BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static long area(ClaimBounds bounds) {
        return ((long) bounds.maxX() - bounds.minX() + 1) * ((long) bounds.maxZ() - bounds.minZ() + 1);
    }

    private static void checkThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Claim operations require the server main thread");
    }

    private static ClaimOperationException failure(ClaimOperationException.Reason reason, String message) {
        return new ClaimOperationException(reason, message);
    }
}
