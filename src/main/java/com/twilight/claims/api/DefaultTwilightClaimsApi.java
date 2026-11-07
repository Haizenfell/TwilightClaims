package com.twilight.claims.api;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.api.event.ClaimChangeEvent;
import com.twilight.claims.api.event.ClaimPreChangeEvent;
import com.twilight.claims.command.claim.ClaimDeletionService;
import com.twilight.claims.command.claim.ClaimListCommand;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.manager.ClaimOperations;
import com.twilight.claims.visual.BoundaryStyles;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class DefaultTwilightClaimsApi implements TwilightClaimsApi, AutoCloseable {
    private final TwilightClaims plugin;
    private final WGHook wg;
    private final ClaimsConfig config;
    private final ClaimManager points;
    private final RegionVisualizer visualizer;
    private final ClaimDeletionService deletions;
    private final ClaimListCommand list;
    private final ClaimOperations operations;
    private volatile boolean active = true;
    private boolean changing;

    public DefaultTwilightClaimsApi(TwilightClaims plugin, WGHook wg, ClaimsConfig config,
                                    ClaimManager points, RegionVisualizer visualizer,
                                    ClaimDeletionService deletions, Messages messages) {
        this.plugin = plugin;
        this.wg = wg;
        this.config = config;
        this.points = points;
        this.visualizer = visualizer;
        this.deletions = deletions;
        this.list = new ClaimListCommand(messages, wg, deletions);
        this.operations = new ClaimOperations(wg, config, points);
    }

    private void checkActive() {
        if (!active) throw new IllegalStateException("TwilightClaims API is disabled");
    }

    private void checkThread() {
        checkActive();
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Use TwilightClaims API on the server thread");
    }

    @Override public String getPluginVersion() { checkActive(); return plugin.getPluginMeta().getVersion(); }

    @Override public List<Claim> getClaims() {
        checkThread();
        return Bukkit.getWorlds().stream().flatMap(world -> getClaims(world).stream()).toList();
    }

    @Override public List<Claim> getClaims(World world) {
        checkThread();
        requireWorld(world);
        return wg.getAllRegions(world).stream().filter(wg::isClaim)
                .map(region -> snapshot(new WGHook.ClaimRef(world, region))).toList();
    }

    @Override public List<Claim> getClaims(UUID owner) {
        checkThread();
        return wg.getOwnedClaims(Objects.requireNonNull(owner, "owner")).stream().map(this::snapshot).toList();
    }

    @Override public Optional<Claim> getClaim(ClaimKey key) {
        checkThread();
        Objects.requireNonNull(key, "key");
        World world = Bukkit.getWorld(key.worldId());
        ProtectedRegion region = wg.getClaim(key.worldId(), key.regionId());
        return world == null || region == null ? Optional.empty() : Optional.of(snapshot(new WGHook.ClaimRef(world, region)));
    }

    @Override public Optional<Claim> getClaimAt(Location location) { return getClaimAt(null, location); }

    @Override public Optional<Claim> getClaimAt(Player player, Location location) {
        checkThread();
        Objects.requireNonNull(location, "location");
        if (location.getWorld() == null) return Optional.empty();
        ProtectedRegion region = wg.getClaimAt(player, location);
        return region == null ? Optional.empty() : Optional.of(snapshot(new WGHook.ClaimRef(location.getWorld(), region)));
    }

    @Override public Optional<Claim> findNearestClaim(Location location, double radius) {
        checkThread();
        Objects.requireNonNull(location, "location");
        if (!Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("Invalid radius");
        if (location.getWorld() == null) return Optional.empty();
        ProtectedRegion region = wg.getClosestRegion(location, radius);
        return region == null ? Optional.empty() : Optional.of(snapshot(new WGHook.ClaimRef(location.getWorld(), region)));
    }

    @Override public Optional<Claim> findClaim(UUID owner, long number) {
        checkThread();
        if (number < 1) throw new IllegalArgumentException("Claim number must be positive");
        return getClaims(owner).stream().filter(claim -> ClaimIdentity.numberOf(claim.key().regionId()).orElse(0) == number).findFirst();
    }

    @Override public boolean isOwner(ClaimKey key, UUID player) {
        return getClaim(key).map(claim -> claim.isOwner(player)).orElse(false);
    }

    @Override public boolean isTrusted(ClaimKey key, UUID player) {
        return getClaim(key).map(claim -> claim.isOwner(player) || claim.isMember(player)).orElse(false);
    }

    @Override public boolean hasConflict(World world, ClaimBounds bounds, ClaimKey ignoredClaim) {
        checkThread();
        requireWorld(world);
        Objects.requireNonNull(bounds, "bounds");
        ProtectedRegion ignored = ignoredClaim == null ? null : require(ignoredClaim).region();
        if (ignoredClaim != null && !world.getUID().equals(ignoredClaim.worldId())) {
            throw new IllegalArgumentException("Ignored claim belongs to another world");
        }
        return wg.checkProximity(world, minimum(bounds), maximum(bounds), ignored) != null;
    }

    @Override public Claim createClaim(UUID owner, World world, ClaimBounds bounds, boolean enforcePointLimit) {
        return createClaim(owner, world, bounds, null, enforcePointLimit);
    }

    @Override public Claim createClaim(UUID owner, World world, ClaimBounds bounds, BoundaryStyle style, boolean enforcePointLimit) {
        checkThread();
        Map<String, Object> changes = new HashMap<>();
        changes.put("owner", Objects.requireNonNull(owner, "owner"));
        changes.put("worldId", Objects.requireNonNull(world, "world").getUID());
        changes.put("bounds", Objects.requireNonNull(bounds, "bounds"));
        changes.put("enforcePointLimit", enforcePointLimit);
        if (style != null) changes.put("boundaryStyle", style);
        return change(ClaimChangeType.CREATE, null, changes,
                () -> operations.create(owner, world, bounds, style, enforcePointLimit));
    }

    @Override public Claim createClaim(Player owner, int radius) {
        checkThread();
        Objects.requireNonNull(owner, "owner");
        if (radius < 0 || radius > 29_999_983) throw new IllegalArgumentException("Invalid radius");
        Location center = owner.getLocation();
        ClaimBounds bounds = ClaimBounds.fullHeight(owner.getWorld(), Math.subtractExact(center.getBlockX(), radius),
                Math.subtractExact(center.getBlockZ(), radius), Math.addExact(center.getBlockX(), radius), Math.addExact(center.getBlockZ(), radius));
        return createClaim(owner.getUniqueId(), owner.getWorld(), bounds, true);
    }

    @Override public Claim resizeClaim(ClaimKey key, ClaimBounds bounds, boolean enforcePointLimit) {
        return change(ClaimChangeType.RESIZE, key, Map.of("bounds", bounds, "enforcePointLimit", enforcePointLimit),
                () -> operations.resize(key, bounds, enforcePointLimit));
    }

    @Override public Claim createChildClaim(UUID owner, ClaimKey parent, ClaimBounds bounds, BoundaryStyle style,
                                             boolean enforcePointLimit) {
        checkThread();
        require(parent);
        Map<String, Object> changes = new HashMap<>();
        changes.put("owner", Objects.requireNonNull(owner, "owner"));
        changes.put("worldId", parent.worldId());
        changes.put("parent", parent);
        changes.put("bounds", Objects.requireNonNull(bounds, "bounds"));
        changes.put("enforcePointLimit", enforcePointLimit);
        if (style != null) changes.put("boundaryStyle", style);
        return change(ClaimChangeType.CREATE, null, changes,
                () -> operations.createChild(owner, parent, bounds, style, enforcePointLimit));
    }

    @Override public void deleteClaim(ClaimKey key) {
        change(ClaimChangeType.DELETE, key, Map.of(), () -> { operations.delete(key); return null; });
    }

    @Override public Claim transferOwner(ClaimKey key, UUID owner, boolean enforcePointLimit) {
        return change(ClaimChangeType.TRANSFER_OWNER, key, Map.of("owner", owner, "enforcePointLimit", enforcePointLimit),
                () -> operations.transferOwner(key, owner, enforcePointLimit));
    }

    @Override public Claim addMember(ClaimKey key, UUID player) {
        return change(ClaimChangeType.ADD_MEMBER, key, Map.of("member", player), () -> operations.addMember(key, player));
    }
    @Override public Claim removeMember(ClaimKey key, UUID player) {
        return change(ClaimChangeType.REMOVE_MEMBER, key, Map.of("member", player), () -> operations.removeMember(key, player));
    }
    @Override public Claim clearMembers(ClaimKey key) {
        return change(ClaimChangeType.CLEAR_MEMBERS, key, Map.of(), () -> operations.clearMembers(key));
    }

    @Override @SuppressWarnings("unchecked") public <T> Optional<T> getFlag(ClaimKey key, Flag<T> flag) {
        checkThread();
        return Optional.ofNullable((T) freeze(require(key).region().getFlag(Objects.requireNonNull(flag, "flag"))));
    }

    @Override public Map<Flag<?>, Object> getFlags(ClaimKey key) {
        checkThread();
        Map<Flag<?>, Object> flags = new HashMap<>();
        require(key).region().getFlags().forEach((flag, value) -> flags.put(flag, freeze(value)));
        return Map.copyOf(flags);
    }

    @Override public <T> Claim setFlag(ClaimKey key, Flag<T> flag, T value) {
        checkThread();
        Objects.requireNonNull(flag, "flag");
        if (flag.getName().equals(BoundaryStyles.flag().getName())) {
            if (value == null) return resetBoundaryStyle(key);
            if (!(value instanceof String encoded)) throw new IllegalArgumentException("Boundary style flag must be a string");
            return setBoundaryStyle(key, BoundaryStyles.decode(encoded));
        }
        Map<String, Object> changes = new HashMap<>();
        changes.put("flag", flag.getName());
        if (value != null) changes.put("value", freeze(value));
        return change(ClaimChangeType.SET_FLAG, key, changes, () -> operations.setFlag(key, flag, value));
    }

    @Override public Claim setPriority(ClaimKey key, int priority) {
        return change(ClaimChangeType.SET_PRIORITY, key, Map.of("priority", priority), () -> operations.setPriority(key, priority));
    }

    @Override public Claim clearFlags(ClaimKey key) {
        return change(ClaimChangeType.CLEAR_FLAGS, key, Map.of(), () -> operations.clearFlags(key));
    }

    @Override public Claim setParent(ClaimKey key, ClaimKey parent) {
        return change(ClaimChangeType.SET_PARENT, key, parent == null ? Map.of() : Map.of("parent", parent),
                () -> operations.setParent(key, parent));
    }

    @Override public BoundaryStyle getDefaultBoundaryStyle() { checkThread(); return visualizer.getDefaultBoundaryStyle(); }
    @Override public BoundaryStyle getBoundaryStyle(ClaimKey key) { checkThread(); return visualizer.getBoundaryStyle(require(key).region()); }
    @Override public Optional<BoundaryStyle> getBoundaryStyleOverride(ClaimKey key) {
        checkThread(); return visualizer.getBoundaryStyleOverride(require(key).region());
    }

    @Override public Claim setBoundaryStyle(ClaimKey key, BoundaryStyle style) {
        Objects.requireNonNull(style, "style");
        return change(ClaimChangeType.SET_BOUNDARY_STYLE, key, Map.of("boundaryStyle", style), () -> saveStyle(key, style));
    }

    @Override public Claim resetBoundaryStyle(ClaimKey key) {
        return change(ClaimChangeType.RESET_BOUNDARY_STYLE, key, Map.of(), () -> saveStyle(key, null));
    }

    private WGHook.ClaimRef saveStyle(ClaimKey key, BoundaryStyle style) {
        WGHook.ClaimRef ref = require(key);
        String before = BoundaryStyles.raw(ref.region());
        if (style == null) BoundaryStyles.reset(ref.region()); else BoundaryStyles.set(ref.region(), style);
        if (!wg.save(ref.world())) {
            ref.region().setFlag(BoundaryStyles.flag(), before);
            throw new ClaimOperationException(ClaimOperationException.Reason.SAVE_FAILED, "Cannot save region boundary style");
        }
        return ref;
    }

    @Override public void showBoundary(Player viewer, ClaimKey key) {
        checkThread(); WGHook.ClaimRef ref = visible(viewer, key); visualizer.show(viewer, ref.region(), null);
    }
    @Override public void showBoundary(Player viewer, ClaimKey key, long durationTicks) {
        checkThread();
        if (durationTicks < 1 || durationTicks > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid durationTicks");
        WGHook.ClaimRef ref = visible(viewer, key); visualizer.show(viewer, ref.region(), null, durationTicks);
    }
    @Override public void showErrorBoundary(Player viewer, ClaimKey key, long durationSeconds) {
        checkThread();
        if (durationSeconds < 1 || durationSeconds > Integer.MAX_VALUE / 20) throw new IllegalArgumentException("Invalid durationSeconds");
        WGHook.ClaimRef ref = visible(viewer, key); visualizer.showErrorTemporary(viewer, ref.region(), durationSeconds);
    }
    @Override public void showOwnedBoundaries(Player viewer) {
        checkThread(); Objects.requireNonNull(viewer, "viewer");
        visualizer.showListTemporary(viewer, wg.getOwnedClaims(viewer).stream()
                .filter(ref -> ref.world().getUID().equals(viewer.getWorld().getUID())).map(WGHook.ClaimRef::region).toList(),
                Math.clamp(plugin.getConfig().getLong("visual.visual-duration-ticks", 200), 1, Integer.MAX_VALUE));
    }
    @Override public void clearBoundaries(Player viewer) { checkThread(); visualizer.clear(Objects.requireNonNull(viewer, "viewer")); }
    @Override public boolean isVisualizing(Player viewer) { checkThread(); return visualizer.isVisualizing(Objects.requireNonNull(viewer, "viewer")); }

    @Override public ClaimPoints getPoints(Player player) {
        checkThread(); Objects.requireNonNull(player, "player"); points.refresh(player);
        return pointSnapshot(points.getSnapshot(player));
    }
    @Override public Optional<ClaimPoints> getCachedPoints(UUID player) {
        checkActive(); return points.getCachedSnapshot(Objects.requireNonNull(player, "player")).map(DefaultTwilightClaimsApi::pointSnapshot);
    }
    @Override public boolean canAfford(Player player, long additionalArea) {
        checkThread(); return additionalArea <= 0 || points.canExpand(Objects.requireNonNull(player, "player"), additionalArea);
    }
    @Override public ItemStack getShovel() { checkThread(); return config.getShovelItem(); }
    @Override public ItemStack getInspector() { checkThread(); return config.getStickItem(); }
    @Override public boolean isShovel(ItemStack item) { checkThread(); return config.isShovel(item); }
    @Override public boolean isInspector(ItemStack item) { checkThread(); return config.isStick(item); }
    @Override public void showClaimList(Player player, int page) { checkThread(); list.execute(Objects.requireNonNull(player, "player"), page); }
    @Override public void requestDeletion(Player player, ClaimKey key) {
        checkThread(); WGHook.ClaimRef ref = require(key);
        deletions.request(player, deletions.createRequest(Objects.requireNonNull(player, "player"), ref));
    }

    private Claim change(ClaimChangeType type, ClaimKey key, Map<String, Object> changes, Supplier<WGHook.ClaimRef> action) {
        checkThread();
        if (changing) throw new IllegalStateException("Do not mutate claims inside a ClaimPreChangeEvent listener");
        Claim before = key == null ? null : snapshot(require(key));
        Map<Flag<?>, Object> previousFlags = key == null ? Map.of() : getFlags(key);
        Claim after;
        changing = true;
        try {
            ClaimPreChangeEvent event = new ClaimPreChangeEvent(type, before, changes);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) throw new ClaimOperationException(ClaimOperationException.Reason.CANCELLED, "Claim change cancelled");
            if (before != null && (!getClaim(key).filter(before::equals).isPresent() || !getFlags(key).equals(previousFlags))) {
                throw new ClaimOperationException(ClaimOperationException.Reason.INVALID_STATE, "Claim was changed during the pre-change event");
            }
            WGHook.ClaimRef result = action.get();
            after = result == null ? null : snapshot(result);
            try {
                if (before != null && (after == null || !before.key().equals(after.key()))) {
                    World oldWorld = Bukkit.getWorld(before.key().worldId());
                    if (oldWorld != null) visualizer.clearRegion(oldWorld, before.key().regionId());
                }
                if (result != null) visualizer.refreshRegion(result.world(), result.region());
                Set<UUID> affected = new HashSet<>();
                if (before != null) affected.addAll(before.owners());
                if (after != null) affected.addAll(after.owners());
                for (UUID owner : affected) {
                    Player online = Bukkit.getPlayer(owner);
                    if (online != null) points.refresh(online);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Claim was saved, but refreshing its display or points failed: " + exception.getMessage());
            }
        } finally {
            changing = false;
        }
        Bukkit.getPluginManager().callEvent(new ClaimChangeEvent(type, before, after));
        return after;
    }

    private WGHook.ClaimRef require(ClaimKey key) {
        Objects.requireNonNull(key, "key");
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) throw new ClaimOperationException(ClaimOperationException.Reason.WORLD_UNAVAILABLE, "Claim world is not loaded");
        ProtectedRegion region = wg.getClaim(key.worldId(), key.regionId());
        if (region == null) throw new ClaimOperationException(ClaimOperationException.Reason.NOT_FOUND, "Claim does not exist");
        return new WGHook.ClaimRef(world, region);
    }

    private void requireWorld(World world) {
        Objects.requireNonNull(world, "world");
        if (Bukkit.getWorld(world.getUID()) != world || wg.getManager(world) == null) {
            throw new ClaimOperationException(ClaimOperationException.Reason.WORLD_UNAVAILABLE, "WorldGuard world is not loaded");
        }
    }

    private WGHook.ClaimRef visible(Player viewer, ClaimKey key) {
        Objects.requireNonNull(viewer, "viewer");
        WGHook.ClaimRef ref = require(key);
        if (!viewer.getWorld().getUID().equals(key.worldId())) throw new IllegalArgumentException("Viewer and claim must be in the same world");
        return ref;
    }

    private Claim snapshot(WGHook.ClaimRef ref) {
        ProtectedRegion region = ref.region();
        BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
        ClaimKey parent = region.getParent() == null ? null : new ClaimKey(ref.world().getUID(), region.getParent().getId());
        return new Claim(new ClaimKey(ref.world().getUID(), region.getId()), wg.getDisplayName(region),
                new ClaimBounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()),
                region.getOwners().getUniqueIds(), region.getMembers().getUniqueIds(), region.getPriority(), parent,
                visualizer.getBoundaryStyle(region));
    }

    private static ClaimPoints pointSnapshot(ClaimManager.PointsSnapshot snapshot) {
        return new ClaimPoints(snapshot.used(), snapshot.total(), snapshot.purchased(), snapshot.hoursPlayed(), snapshot.perHour());
    }

    private static BlockVector3 minimum(ClaimBounds bounds) {
        return BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ());
    }

    private static BlockVector3 maximum(ClaimBounds bounds) {
        return BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static Object freeze(Object value) {
        if (value instanceof Set<?> set) return set.stream().map(DefaultTwilightClaimsApi::freeze).collect(Collectors.toUnmodifiableSet());
        if (value instanceof List<?> list) return list.stream().map(DefaultTwilightClaimsApi::freeze).toList();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new HashMap<>();
            map.forEach((key, entry) -> copy.put(freeze(key), freeze(entry)));
            return Map.copyOf(copy);
        }
        return value;
    }

    @Override public void close() { active = false; }
}
