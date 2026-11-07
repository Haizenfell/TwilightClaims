# TwilightClaims Developer API

Complete reference for TwilightClaims **1.7.1**, personal API version **1** and managed API version **1**.

The entry point is `com.twilight.claims.api.TwilightClaimsApi`. Obtain it from Bukkit's `ServicesManager`; use the public API rather than the plugin's internal managers or mutable WorldGuard regions.

## Contents

- [Dependencies and connection](#dependencies-and-connection)
- [Execution rules](#execution-rules)
- [Data types](#data-types)
- [Queries and access checks](#queries-and-access-checks)
- [Creating and changing claims](#creating-and-changing-claims)
- [Members, flags and inheritance](#members-flags-and-inheritance)
- [Custom boundary materials](#custom-boundary-materials)
- [Showing boundaries](#showing-boundaries)
- [Points, tools and player interfaces](#points-tools-and-player-interfaces)
- [Events](#events)
- [Errors](#errors)
- [Complete example plugin](#complete-example-plugin)
- [Managed regions for external controllers](#managed-regions-for-external-controllers)

## Dependencies and connection

### Build artifacts

Build and install TwilightClaims into your local Maven repository:

```bash
mvn clean install
```

The build produces:

| Artifact | Purpose |
| --- | --- |
| `TwilightClaims-1.7.1.jar` | The server plugin. Install this in the server's `plugins` directory. |
| `TwilightClaims-1.7.1-api.jar` | Public API classes for compilation. Do not install this as a server plugin. |

The example below uses locally installed artifacts. It does not assume that TwilightClaims is published to a public Maven repository.

### Maven dependency

Add these repositories, dependencies and compiler settings to the consuming plugin's POM:

```xml
<properties>
    <maven.compiler.release>26</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<repositories>
    <repository>
        <id>papermc</id>
        <url>https://repo.papermc.io/repository/maven-public/</url>
    </repository>
    <repository>
        <id>enginehub</id>
        <url>https://maven.enginehub.org/repo/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.twilight</groupId>
        <artifactId>TwilightClaims</artifactId>
        <version>1.7.1</version>
        <classifier>api</classifier>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.papermc.paper</groupId>
        <artifactId>paper-api</artifactId>
        <version>26.3.build.214-stable</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>com.sk89q.worldguard</groupId>
        <artifactId>worldguard-bukkit</artifactId>
        <version>7.0.20</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.16.0</version>
        </plugin>
    </plugins>
</build>
```

The WorldGuard dependency supplies the types used by the flag methods and examples. Do not shade or relocate the TwilightClaims API into your plugin: the provider and consumers must share the same API classes.

### Runtime dependency

Declare this in the consuming plugin's `plugin.yml`:

```yaml
depend: [TwilightClaims]
```

This makes TwilightClaims a required dependency and enables it before your plugin. TwilightClaims itself requires WorldGuard and WorldEdit.

### Obtain the service

```java
import com.twilight.claims.api.TwilightClaimsApi;
import org.bukkit.Bukkit;

TwilightClaimsApi api = Bukkit.getServicesManager().load(TwilightClaimsApi.class);
if (api == null) {
    throw new IllegalStateException("TwilightClaims API is unavailable");
}

String installedVersion = api.getPluginVersion();
int supportedApiVersion = TwilightClaimsApi.API_VERSION;
```

Resolve the service in your plugin's `onEnable()`. If integration is optional, use `softdepend: [TwilightClaims]` instead and handle an absent service without calling its methods. Compile-time dependencies are still needed.

`TwilightClaims#getApi()` is an alternative when compiling against the complete plugin artifact. That implementation class is intentionally absent from the API-only artifact; the service interface is the recommended integration point.

The service is unregistered when TwilightClaims shuts down. Calls through an old service reference then throw `IllegalStateException`. A consumer should release its reference on shutdown, not close or unregister the shared provider.

## Execution rules

The sections before the managed API reference describe `TwilightClaimsApi`. Its global and spatial read methods can return managed region snapshots for inspection and boundary display. `isOwner` and `isTrusted` return false for managed regions, personal lists/points exclude them, and attempts to mutate them or use them as parents through API v1 fail with `INVALID_STATE`. A stored membership snapshot is never a substitute for managed ACL trust.

### Threading

All queries that inspect regions, mutations, visualizations, tool methods and player interfaces must run on the server's main thread. Off-thread access throws `IllegalStateException`.

Exceptions:

- `getCachedPoints(UUID)` reads an immutable cached value and supports asynchronous calls.
- `getPluginVersion()` reads plugin metadata and does not require the main thread.
- Immutable data objects can be retained and read off-thread; this does not make later API calls asynchronous-safe.

Schedule region work from an asynchronous callback:

```java
Bukkit.getScheduler().runTask(yourPlugin, () -> {
    api.getClaim(key).ifPresent(claim -> {
        yourPlugin.getLogger().info(claim.name() + ": " + claim.area());
    });
});
```

The API does not automatically schedule operations. Do not wait for asynchronous work on the server thread if that work is waiting for a scheduled API call.

Unless shown as a complete class, Java examples are fragments for your plugin: `api` is the resolved service, worlds and players have already been obtained, and keys refer to existing claims. Place imports at the top of your Java file. Run these fragments on the main thread unless they only read cached points or immutable data.

### Authority and scope

This is a trusted server-plugin API. Direct mutation methods do not check player command permissions, require the caller to stand in a claim, or request chat confirmation. Your plugin must decide which players can trigger these methods.

Use `requestDeletion(Player, ClaimKey)` when you want TwilightClaims to check ownership and its deletion permission and display the normal confirmation interface.

The API manages recognized TwilightClaims cuboids. It does not adopt or rename arbitrary administrative WorldGuard regions. Only loaded worlds participate in queries, personal numbering and point usage. Unloading a world removes its claims from that active view; operations do not automatically load it.

An unloaded world's claims do not reserve their numbers in the current allocator. Keep all claim worlds loaded when personal-number uniqueness across those worlds matters; use `ClaimKey`, not a personal number, for persistent references.

Mutations validate their final state and save it through WorldGuard. Save failures restore the previous region state. Successful API changes survive restarts; transient visualization state does not.

### Point-limit policy

The `enforcePointLimit` argument controls budget checks, not geometry or permissions:

| Operation with `true` | Budget check |
| --- | --- |
| Creation, including a child claim | The online owner's free points must cover the new claim's full X/Z area. |
| Enlargement | The online owner's free points must cover the added area. |
| Shrinking or resizing without extra area | No additional points are required. |
| Ownership transfer | The online recipient must be able to afford the incoming area. |

Use `false` explicitly for trusted provisioning, including claims granted to offline owners. It bypasses point checks only. Minimum dimensions, world borders, build height, conflicts and inheritance rules still apply. `createClaim(Player, int)` always enables point checks.

## Data types

These public types are in `com.twilight.claims.api`.

### ClaimKey

```java
ClaimKey key = new ClaimKey(worldUuid, permanentWorldGuardId);
```

| Accessor | Type | Meaning |
| --- | --- | --- |
| `worldId()` | `UUID` | The world's persistent UUID. |
| `regionId()` | `String` | The actual WorldGuard ID, not the short display name. |

Both values are required, and the region ID must not be blank. Store both when persisting a reference in your own plugin.

New IDs look like `5827340192658431_claim_2`; players see `claim_2`. Numbers are personal, shared across the owner's loaded worlds and allocated from the smallest free positive number. Deleting a claim frees its number, so a later `claim_2` can be a different region. Never use a short name alone as a stored identity.

A key is stable across ordinary resizing, membership changes and style changes. Ownership transfer can allocate a new ID to avoid a number collision; always retain the returned key. Renaming a region outside the API also invalidates an old key.

Legacy claims keep their original IDs and display names. `findClaim(owner, number)` resolves the new numbered format, not synthetic numbers for legacy IDs.

### ClaimBounds

```java
ClaimBounds bounds = new ClaimBounds(minX, minY, minZ, maxX, maxY, maxZ);
ClaimBounds fullHeight = ClaimBounds.fullHeight(world, 100, 100, 110, 110);
```

All six coordinates are inclusive. Minimum coordinates must not exceed maximum coordinates. The constructor validates ordering; creation and resize validate world-specific limits.

| Method | Result |
| --- | --- |
| `minX()`, `minY()`, `minZ()`, `maxX()`, `maxY()`, `maxZ()` | Integer coordinates. |
| `area()` | X/Z area as `long`: `(maxX - minX + 1) * (maxZ - minZ + 1)`. Extreme values saturate at `Long.MAX_VALUE`. |
| `centerX()`, `centerZ()` | Center of the inclusive coordinate range, possibly ending in `.5`. |
| `center(World)` | A new `Location` with the midpoint of all three coordinate ranges in the supplied world. |
| `fullHeight(World, int minX, int minZ, int maxX, int maxZ)` | Bounds from the world's minimum build height through `getMaxHeight() - 1`. |

For example, X/Z `100..110` creates an `11 × 11` area costing `121` points. Y does not affect point cost.

`center(World)` is a geometric midpoint, not a terrain-aware or safe teleport destination. It does not verify that the supplied world matches a claim.

Creation and resize require X/Z side lengths of at least `2 * claim.min-radius + 1`, which is `11` with the default configuration. Coordinates must fit the world border, the world's build height and the absolute X/Z limit of `29,999,984`.

### Claim

A `Claim` is an immutable snapshot, not a live region handle.

| Accessor or method | Result |
| --- | --- |
| `key()` | `ClaimKey`. |
| `name()` | Player-facing name, such as `claim_2`. |
| `bounds()` | `ClaimBounds`. |
| `owners()` | Immutable `Set<UUID>`. New claims have one owner. |
| `members()` | Immutable `Set<UUID>` of direct trusted members. |
| `priority()` | WorldGuard priority as `int`. |
| `parent()` | Parent `ClaimKey`, or `null` when absent. |
| `parentKey()` | The parent as `Optional<ClaimKey>`. |
| `boundaryStyle()` | Effective `BoundaryStyle` at the time of the snapshot. |
| `area()` | `bounds().area()`. |
| `isOwner(UUID)` | Direct UUID ownership in this snapshot. |
| `isMember(UUID)` | Direct UUID membership in this snapshot; does not include ownership. |
| `owner()` | The first UUID owner in UUID sort order, or an empty `Optional` if none. |

For ordinary claims, `owner()` identifies the sole owner. `owners()` can represent legacy or administratively modified data with several UUID owners; `owner()` does not establish that such a region has only one owner.

Read a fresh snapshot after mutations:

```java
Claim updated = api.addMember(claim.key(), trustedPlayerUuid);
Claim refreshed = api.getClaim(updated.key()).orElseThrow();
```

### BoundaryStyle

```java
BoundaryStyle style = new BoundaryStyle(
        Material.EMERALD_BLOCK,
        Material.GOLD_BLOCK,
        Material.DIAMOND_BLOCK,
        Material.REDSTONE_BLOCK,
        Material.LAPIS_BLOCK
);
```

The five accessors and constructor arguments are:

| Accessor | Usage |
| --- | --- |
| `corner()` | Normal corners. |
| `edge()` | Normal edges and corner arms. |
| `selectedCorner()` | The selected corner during shovel editing. |
| `errorCorner()` | Error and inspector-discovery corners. |
| `errorEdge()` | Error and inspector-discovery edges. |

Every value must be a non-null, non-air block `Material`. Item-only materials and air cause `IllegalArgumentException`.

The short constructor `new BoundaryStyle(corner, edge)` sets the selected and error corners to `corner`, and error edges to `edge`.

### ClaimPoints

| Accessor or method | Meaning |
| --- | --- |
| `used()` | Total area of owned plugin claims in loaded worlds. |
| `total()` | Current total point limit. |
| `purchased()` | Purchased points supplied by the configured external placeholder. |
| `hoursPlayed()` | Whole played hours. |
| `perHour()` | Current hourly rate. |
| `free()` | `total() - used()`, which can be negative. |
| `hoursPoints()` | `(long) hoursPlayed() * perHour()`. |

`used`, `total`, `free` and `hoursPoints` are `long`; purchased points, played hours and hourly rate are `int`.

## Queries and access checks

| Signature | Behavior |
| --- | --- |
| `String getPluginVersion()` | Installed plugin version, currently `1.7.1`. |
| `List<Claim> getClaims()` | All recognized claims across loaded worlds. |
| `List<Claim> getClaims(World world)` | All recognized claims in the specified loaded WorldGuard world. |
| `List<Claim> getClaims(UUID owner)` | Owned claims across loaded worlds, ordered numerically by personal number. |
| `Optional<Claim> getClaim(ClaimKey key)` | Empty if the claim is absent, unrecognized or its world is unloaded. |
| `Optional<Claim> getClaimAt(Location location)` | A claim containing the position, including its Y coordinate. |
| `Optional<Claim> getClaimAt(Player player, Location location)` | Prefers a claim owned by that player when claims overlap; can still return another owner's claim. |
| `Optional<Claim> findNearestClaim(Location location, double radius)` | Nearest claim within an inclusive horizontal radius in blocks. Radius must be finite and non-negative. |
| `Optional<Claim> findClaim(UUID owner, long number)` | Resolve a positive personal number across the owner's loaded worlds. |
| `boolean isOwner(ClaimKey key, UUID player)` | Direct UUID ownership; false when the key cannot be resolved. |
| `boolean isTrusted(ClaimKey key, UUID player)` | Direct UUID ownership or membership; false when the key cannot be resolved. |
| `boolean hasConflict(World world, ClaimBounds bounds, ClaimKey ignoredClaim)` | Test WorldGuard overlap and relevant inheritance constraints; pass `null` to ignore no claim. |

Returned lists are snapshots. Ordering of the all-world and world-specific lists is not a public sorting guarantee. Lookup at overlapping regions considers ownership preference, corners/edges, priority, area and ID; it is not simply a highest-priority-only lookup.

Nearest-claim lookup ignores height and does not read terrain or load chunks. A position outside a region's vertical range can still have horizontal distance zero. Position lookups with no world return an empty `Optional`.

`isOwner` and `isTrusted` are direct UUID checks, not a replacement for WorldGuard's full permission and protection evaluation. They do not resolve group owners, group members or inherited access.

`getClaims(World)` and `hasConflict` throw `WORLD_UNAVAILABLE` if the world or its WorldGuard manager is unavailable; `getClaims()` can propagate that failure for a loaded world without an available manager. A non-null ignored key must exist and belong to the queried world. Conflict checks do not validate every creation rule, including budget, minimum size, build height and world border; the final mutation performs its own checks.

Example:

```java
for (Claim claim : api.getClaims(ownerUuid)) {
    logger.info(claim.name() + " " + claim.key().regionId() + " " + claim.area());
}

api.findClaim(ownerUuid, 2).ifPresent(claim -> {
    api.showBoundary(viewer, claim.key(), 200L);
});

boolean occupied = api.hasConflict(world, proposedBounds, null);
boolean trusted = api.isTrusted(key, playerUuid);
```

The viewer must be in the claim's world for the visualization call in this example.

## Creating and changing claims

| Signature | Behavior |
| --- | --- |
| `Claim createClaim(UUID owner, World world, ClaimBounds bounds, boolean enforcePointLimit)` | Create a cuboid with configured default boundary materials. |
| `Claim createClaim(UUID owner, World world, ClaimBounds bounds, BoundaryStyle style, boolean enforcePointLimit)` | Create with a style saved atomically alongside the region. `null` uses defaults. |
| `Claim createClaim(Player owner, int radius)` | Create at the player's current position, full height, with point checks enabled. Side length is `2 * radius + 1`; world and minimum-size checks still apply. |
| `Claim createChildClaim(UUID owner, ClaimKey parent, ClaimBounds bounds, BoundaryStyle style, boolean enforcePointLimit)` | Create within an existing managed parent; the parent's key supplies the world. `null` style uses global defaults. |
| `Claim resizeClaim(ClaimKey key, ClaimBounds bounds, boolean enforcePointLimit)` | Replace bounds while preserving members, owners, flags, priority and inheritance. |
| `void deleteClaim(ClaimKey key)` | Delete immediately, without player confirmation. Rejects a claim with children. |
| `Claim transferOwner(ClaimKey key, UUID owner, boolean enforcePointLimit)` | Replace the sole owner. The new owner is removed from direct members. May return a new region ID. |

All methods returning `Claim` return the resulting snapshot. Store its key when an operation can change identity. No API method adds a second owner; transfer requires the existing region to have exactly one UUID owner and no name/group owners.

### Create with custom materials

```java
BoundaryStyle style = new BoundaryStyle(
        Material.EMERALD_BLOCK,
        Material.GOLD_BLOCK,
        Material.DIAMOND_BLOCK,
        Material.REDSTONE_BLOCK,
        Material.LAPIS_BLOCK
);

Claim claim = api.createClaim(
        ownerUuid,
        world,
        ClaimBounds.fullHeight(world, 100, 100, 110, 110),
        style,
        true
);
```

Here the owner must be online and able to afford `121` points. For an intentional offline grant, pass `false` instead. A custom style does not bypass any geometry checks.

### Resize and transfer

```java
Claim resized = api.resizeClaim(
        claim.key(),
        ClaimBounds.fullHeight(world, 100, 100, 120, 120),
        true
);

Claim transferred = api.transferOwner(resized.key(), recipientUuid, true);
ClaimKey currentKey = transferred.key();
```

If the recipient already owns the encoded personal number, transfer assigns a free number and a new internal ID. Transfer of a legacy-format claim also allocates a numbered ID. Otherwise the existing internal ID can remain. Existing child-parent links and custom boundary styles are preserved.

### Create a parent and children

```java
Claim parent = api.createClaim(
        ownerUuid,
        world,
        ClaimBounds.fullHeight(world, 200, 200, 240, 240),
        true
);

Claim child = api.createChildClaim(
        ownerUuid,
        parent.key(),
        ClaimBounds.fullHeight(world, 205, 205, 215, 215),
        new BoundaryStyle(Material.OBSIDIAN, Material.CRYING_OBSIDIAN),
        true
);
```

The entire child must fit inside the parent and all ancestors. Siblings and unrelated regions must not overlap it. The owner can differ from the parent's owner. Parent and child are separate claims for numbering and points; their overlapping areas each count toward their respective owner's usage.

Child styles are independent. A child with no override uses the global configuration, not its parent's custom style.

### Delete directly or ask the player

```java
api.requestDeletion(player, claim.key());
```

This opens the permission-checked confirmation interface. For an authorized server-side action without player interaction:

```java
api.deleteClaim(claim.key());
```

Delete children before deleting their parent. Detaching a child with `setParent(childKey, null)` also removes its inheritance relationship; it does not move that region or remove its geometry.

## Members, flags and inheritance

| Signature | Behavior |
| --- | --- |
| `Claim addMember(ClaimKey key, UUID player)` | Add a direct member; an owner is not added to members. |
| `Claim removeMember(ClaimKey key, UUID player)` | Remove direct membership. |
| `Claim clearMembers(ClaimKey key)` | Clear the member domain, including any administratively added name/group members. |
| `<T> Optional<T> getFlag(ClaimKey key, Flag<T> flag)` | Read the region's explicit value for a typed flag. |
| `Map<Flag<?>, Object> getFlags(ClaimKey key)` | Read explicit flags as a copied, unmodifiable map. |
| `<T> Claim setFlag(ClaimKey key, Flag<T> flag, T value)` | Set a registered typed flag; `null` clears its explicit value. |
| `Claim clearFlags(ClaimKey key)` | Remove all explicit flags, including the custom boundary-style override. |
| `Claim setPriority(ClaimKey key, int priority)` | Set WorldGuard priority. |
| `Claim setParent(ClaimKey key, ClaimKey parent)` | Assign a managed cuboid parent in the same world; `null` clears the parent. |

Flag reads are not effective inherited/global WorldGuard evaluation. An empty `getFlag` result means there is no explicit value on this region, not that an action is necessarily permitted. Use WorldGuard's protection queries when evaluating whether gameplay actions are allowed.

Flags passed to `setFlag` must be the registered WorldGuard flag instance. Values must support the flag's serialization contract. Standard `Set`, `List` and `Map` values are recursively copied; custom scalar flag types retain their own value-type contract.

```java
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;

api.addMember(key, trustedPlayerUuid);
api.removeMember(key, formerMemberUuid);
api.setFlag(key, Flags.PVP, StateFlag.State.DENY);

StateFlag.State explicitPvp = api.getFlag(key, Flags.PVP).orElse(null);

api.setFlag(key, Flags.PVP, null);
api.setPriority(key, 10);
api.setParent(child.key(), parent.key());
api.setParent(child.key(), null);
```

Inheritance must remain acyclic. The parent's entire cuboid must contain the child's volume. Parent resizing must retain existing descendants, and child resizing must stay inside its ancestors. Metadata mutation does not rename administrative regions or make unrelated overlaps legal.

## Custom boundary materials

| Signature | Behavior |
| --- | --- |
| `BoundaryStyle getDefaultBoundaryStyle()` | Global style loaded from configuration when the plugin enables. |
| `BoundaryStyle getBoundaryStyle(ClaimKey key)` | Effective region style: a valid override or the global style. |
| `Optional<BoundaryStyle> getBoundaryStyleOverride(ClaimKey key)` | Empty when no valid region override is stored. |
| `Claim setBoundaryStyle(ClaimKey key, BoundaryStyle style)` | Save a region override and refresh active displays. Style must not be null. |
| `Claim resetBoundaryStyle(ClaimKey key)` | Remove the override and use global defaults. |

```java
api.setBoundaryStyle(key, new BoundaryStyle(Material.OBSIDIAN, Material.CRYING_OBSIDIAN));

BoundaryStyle effective = api.getBoundaryStyle(key);
Optional<BoundaryStyle> override = api.getBoundaryStyleOverride(key);

api.resetBoundaryStyle(key);
```

Overrides are stored in the WorldGuard string flag `twilightclaims-boundary-style`. The plugin registers this flag during startup, before region loading. Restart the server after installing the plugin or this update.

The same override is used for normal boundaries, shovel editing, the selected corner, automatic displays, inspector discovery and explicit API displays. It survives restart, resize and ownership transfer. Reset removes only the override; it does not reload or change global configuration. Malformed stored styles fall back to the global style and produce a warning.

The API supports block materials, not arbitrary block-state strings or actual world-block replacement. Use `setBoundaryStyle`, rather than manually constructing the internal string flag value.

## Showing boundaries

| Signature | Behavior and units |
| --- | --- |
| `void showBoundary(Player viewer, ClaimKey key)` | Normal display using `visual.visual-duration-ticks`; zero means until cleared or replaced. |
| `void showBoundary(Player viewer, ClaimKey key, long durationTicks)` | Normal display for `1..Integer.MAX_VALUE` ticks. At normal server speed, 20 ticks is one second. |
| `void showErrorBoundary(Player viewer, ClaimKey key, long durationSeconds)` | Error/discovery display for `1..107374182` seconds. Uses the error materials. |
| `void showOwnedBoundaries(Player viewer)` | Manual display of the viewer's owned claims in their current world. Configured normal duration is clamped to `1..Integer.MAX_VALUE` ticks. |
| `void clearBoundaries(Player viewer)` | Clear all tracked normal/error displays for this viewer and restore client blocks where possible. |
| `boolean isVisualizing(Player viewer)` | Whether a visualization state is currently tracked for this viewer. |

```java
api.showBoundary(viewer, key, 200L);
api.showErrorBoundary(viewer, key, 5L);
api.clearBoundaries(viewer);
api.showOwnedBoundaries(viewer);
```

For methods targeting a claim, the viewer must be in that claim's world; a different world causes `IllegalArgumentException`. The methods do not teleport players. Normal and error displays use separate layers and timers; the error layer takes precedence while active.

Markers are client-side changes, not edits to the world. They follow the configured terrain height, range, loaded-chunk and work limits. A tracked view can have no visible markers because of distance or chunk filters; `isVisualizing` is not a guarantee that the client currently sees blocks. If `visual.only-loaded-chunks` is disabled, terrain reads during rendering can load chunks.

Style changes refresh active displays without replacing their timers. Deletion clears tracked displays of the deleted region. Disconnect, world changes and plugin shutdown clean up visual state.

## Points, tools and player interfaces

| Signature | Behavior |
| --- | --- |
| `ClaimPoints getPoints(Player player)` | Refresh and return the player's current points on the main thread. |
| `Optional<ClaimPoints> getCachedPoints(UUID player)` | Thread-safe read of the latest cached points, or empty before a sample is available or after it has been cleared. |
| `boolean canAfford(Player player, long additionalArea)` | Check an added area against current free points; returns true for zero or negative added area. |
| `ItemStack getShovel()` | An independent copy of the configured shovel. |
| `ItemStack getInspector()` | An independent copy of the configured inspector. |
| `boolean isShovel(ItemStack item)` | Recognize a configured shovel; null returns false. |
| `boolean isInspector(ItemStack item)` | Recognize a configured inspector; null returns false. |
| `void showClaimList(Player player, int page)` | Open the player's clickable list, with one-based page numbering and 10 entries per page. |
| `void requestDeletion(Player player, ClaimKey key)` | Request the normal owner-only, permission-checked, 30-second deletion confirmation. |

The cached sample is refreshed periodically, on player join and after relevant API changes for online owners. It can lag current data and is cleared on quit. It is not a persistent offline balance. Region methods and `getPoints` should not be replaced with cached values when enforcing a live budget.

```java
ClaimPoints points = api.getPoints(player);
player.sendMessage("Free points: " + points.free());

api.getCachedPoints(player.getUniqueId()).ifPresent(sample -> {
    logger.info("Cached used area: " + sample.used());
});

player.getInventory().addItem(api.getShovel());
api.showClaimList(player, 1);
api.requestDeletion(player, key);
```

The point system uses played hours and the existing external purchased-points provider. There are no API setters for total or purchased points. Granting a claim with point checks disabled still increases used area and can leave free points negative.

`canAfford` is a budget check only. A true result does not establish permission, valid geometry or absence of conflicts. Final mutations with point checking enabled perform their own budget validation.

`showClaimList` is a trusted API call and does not enforce the normal `/claim` command permission. Its Remove button requires `twilight.claim.command.unclaim`. Invalid page numbers produce a configured player message.

`requestDeletion` checks `twilight.claim.command.unclaim`, fresh ownership and current region state. The player need not be in the region or its world. UI denials are normally sent to the player; missing keys or unloaded worlds can still throw an API exception while resolving the target. Confirmation is one-time and invalidated by expiration, disconnect, replacement or relevant region changes. Calling `deleteClaim` bypasses this player interface entirely.

## Events

Events are in `com.twilight.claims.api.event` and run synchronously on the main thread.

### ClaimPreChangeEvent

This event implements `Cancellable`.

| Method | Meaning |
| --- | --- |
| `getChangeType()` | `ClaimChangeType`. |
| `getBefore()` | `Optional<Claim>` before the operation; empty for creation. |
| `getChanges()` | Immutable map of proposed values, described below. |
| `isCancelled()`, `setCancelled(boolean)` | Read or change cancellation. |

Cancelling prevents that operation from applying and causes `ClaimOperationException` with reason `CANCELLED`. A pre-change event is not proof that later validation will succeed. Do not mutate claims synchronously from a pre-change listener: reentrant mutations are rejected. Schedule follow-up work if needed. Direct changes through WorldGuard during the event can invalidate the outer operation.

### ClaimChangeEvent

| Method | Meaning |
| --- | --- |
| `getChangeType()` | Completed operation type. |
| `getBefore()` | Optional previous snapshot; empty for creation. |
| `getAfter()` | Optional resulting snapshot; empty for deletion. |

This event is not cancellable. It is emitted after a successful API operation, after persistence for operations that write data. Cancelled or failed operations do not emit this successful event. Idempotent calls can still emit an event; compare snapshots if you need to distinguish an actual value change.

Corresponding built-in creation, deletion, shovel resizing and trust mutations also go through these events. Direct changes made through WorldGuard outside this API do not produce TwilightClaims events.

### Change types and proposed values

Keys in `getChanges()` have these exact spellings. An absent optional key represents no supplied value, not a map entry containing null.

| `ClaimChangeType` | Proposed map values |
| --- | --- |
| `CREATE` | `owner`: UUID; `worldId`: UUID; `bounds`: ClaimBounds; `enforcePointLimit`: Boolean; optional `boundaryStyle`: BoundaryStyle; optional `parent`: ClaimKey for child creation. |
| `RESIZE` | `bounds`: ClaimBounds; `enforcePointLimit`: Boolean. |
| `DELETE` | Empty map. |
| `TRANSFER_OWNER` | `owner`: UUID; `enforcePointLimit`: Boolean. |
| `ADD_MEMBER`, `REMOVE_MEMBER` | `member`: UUID. |
| `CLEAR_MEMBERS` | Empty map. |
| `SET_FLAG` | `flag`: String containing the registered name; optional `value`: typed flag value. An absent value clears the flag. |
| `CLEAR_FLAGS` | Empty map. |
| `SET_PRIORITY` | `priority`: Integer. |
| `SET_PARENT` | Optional `parent`: ClaimKey. An absent parent clears inheritance. |
| `SET_BOUNDARY_STYLE` | `boundaryStyle`: BoundaryStyle. |
| `RESET_BOUNDARY_STYLE` | Empty map. |

Setting or clearing the reserved boundary-style flag through `setFlag` is routed to the corresponding boundary-style operation and event type.

### Listener example

```java
@EventHandler(ignoreCancelled = true)
public void onClaimPreChange(ClaimPreChangeEvent event) {
    if (event.getChangeType() != ClaimChangeType.DELETE) return;
    event.getBefore().ifPresent(claim -> {
        if (protectedClaimKeys.contains(claim.key())) {
            event.setCancelled(true);
        }
    });
}

@EventHandler
public void onClaimChanged(ClaimChangeEvent event) {
    event.getAfter().ifPresent(claim -> {
        getLogger().info(event.getChangeType() + ": " + claim.name());
    });
}
```

Register the listener using your plugin's `PluginManager`, as in the complete example below.

## Errors

Catch `ClaimOperationException` for domain failures and inspect `getReason()`:

| Reason | Meaning |
| --- | --- |
| `NOT_FOUND` | The target is absent or is not a managed claim. |
| `WORLD_UNAVAILABLE` | Its world is unloaded or the WorldGuard manager is unavailable. |
| `INVALID_BOUNDS` | Invalid size, world height, world border or parent containment. |
| `CONFLICT` | Proposed geometry conflicts with another region or existing inheritance constraints. |
| `NOT_ENOUGH_POINTS` | A requested point check failed. |
| `HAS_CHILDREN` | Deletion would remove a parent that still has children. |
| `SAVE_FAILED` | WorldGuard could not persist the operation; previous state is restored. |
| `INVALID_OWNER` | Online point validation cannot resolve an online owner, or an operation requires a sole UUID owner. |
| `INVALID_STATE` | Invalid metadata, unregistered or unserializable flag, inheritance cycle, or state changed during the pre-change event. |
| `CANCELLED` | A listener cancelled the pre-change event. |

Malformed arguments can also produce `IllegalArgumentException` or `NullPointerException`, independently of domain errors. Off-thread use, use after shutdown and reentrant pre-change mutations produce `IllegalStateException`.

```java
try {
    Claim changed = api.resizeClaim(key, newBounds, true);
    player.sendMessage("New area: " + changed.area());
} catch (ClaimOperationException exception) {
    switch (exception.getReason()) {
        case NOT_ENOUGH_POINTS -> player.sendMessage("Not enough claim points.");
        case CONFLICT -> player.sendMessage("Another region blocks this change.");
        case CANCELLED -> player.sendMessage("Another plugin cancelled this action.");
        default -> player.sendMessage("Cannot change claim: " + exception.getReason());
    }
}
```

## Complete example plugin

This example creates an `11 × 11` claim around the player, enforces their point limit, assigns custom boundary blocks atomically and displays the result. It registers a post-change listener as well.

Place this class in `src/main/java/example/ClaimsIntegrationPlugin.java` in your consuming plugin project:

```java
package example;

import com.twilight.claims.api.BoundaryStyle;
import com.twilight.claims.api.Claim;
import com.twilight.claims.api.ClaimBounds;
import com.twilight.claims.api.ClaimOperationException;
import com.twilight.claims.api.TwilightClaimsApi;
import com.twilight.claims.api.event.ClaimChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class ClaimsIntegrationPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private TwilightClaimsApi api;

    @Override
    public void onEnable() {
        api = Bukkit.getServicesManager().load(TwilightClaimsApi.class);
        if (api == null) {
            getLogger().severe("TwilightClaims API is unavailable");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("exampleclaim")).setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("exampleclaims.create")) {
            player.sendMessage("You do not have permission.");
            return true;
        }

        Location center = player.getLocation();
        World world = player.getWorld();
        ClaimBounds bounds = ClaimBounds.fullHeight(world,
                center.getBlockX() - 5, center.getBlockZ() - 5,
                center.getBlockX() + 5, center.getBlockZ() + 5);
        BoundaryStyle style = new BoundaryStyle(Material.EMERALD_BLOCK, Material.GOLD_BLOCK);

        try {
            Claim claim = api.createClaim(player.getUniqueId(), world, bounds, style, true);
            api.showBoundary(player, claim.key(), 200L);
            player.sendMessage("Created " + claim.name() + " with area " + claim.area());
        } catch (ClaimOperationException exception) {
            player.sendMessage("Cannot create claim: " + exception.getReason());
        }
        return true;
    }

    @EventHandler
    public void onClaimChanged(ClaimChangeEvent event) {
        getLogger().info("Claim operation: " + event.getChangeType());
    }

    @Override
    public void onDisable() {
        api = null;
    }
}
```

Use this `src/main/resources/plugin.yml` for the example plugin:

```yaml
name: ClaimsIntegrationExample
version: '1.0.0'
main: example.ClaimsIntegrationPlugin
api-version: '26.3'
depend: [TwilightClaims]

commands:
  exampleclaim:
    description: Create a claim with custom boundary materials.
    permission: exampleclaims.create

permissions:
  exampleclaims.create:
    default: op
```

Build with JDK 26, install the full TwilightClaims server plugin and this example plugin, then restart the server. The API-only JAR stays a compile-time dependency. `/exampleclaim` needs sufficient free claim points and unoccupied space; use the normal TwilightClaims tools and commands to inspect or edit the created claim afterward.

## Managed regions for external controllers

`com.twilight.claims.api.managed.ManagedClaimsApi` is a separate Bukkit service. The existing `TwilightClaimsApi` remains API v1; existing personal integrations do not need recompilation. New consumers must call `getApiVersion()` and inspect `getCapabilities()` on the actual provider. A compile-time constant cannot reliably detect the running provider version. The 1.7.1 cursor, retirement and compaction methods are default interface methods, so older API v1 implementations remain loadable; check the relevant capabilities before calling them. Their default implementation reports `PROVIDER_UNAVAILABLE` when unsupported.

Managed regions belong to an external identity, not a player or clan leader. They have no WorldGuard owner, use an exact UUID member ACL, and do not consume anyone's personal claim points. Personal commands, shovel edits and API v1 mutations cannot change them, create children inside them or assign them as parents. Creating a clan region does not convert existing personal regions.

### Connection and controller registration

Use the API classifier dependency and `depend: [TwilightClaims]` shown earlier. Do not shade or relocate the API or install its JAR as a server plugin.

```java
import com.twilight.claims.api.managed.ManagedClaimsApi;
import com.twilight.claims.api.managed.ManagedControllerRegistration;

ManagedClaimsApi managed = Bukkit.getServicesManager().load(ManagedClaimsApi.class);
if (managed == null || managed.getApiVersion() != 1) {
    throw new IllegalStateException("Managed Claims API v1 is required");
}
ManagedControllerRegistration controller = managed.registerController(
        yourPlugin, "twilightclans", (context, action, desired) -> {
            return yourVerifiedLocalPolicy.authorize(context, action, desired);
        });
```

Register on the server thread. Authorization runs on the server thread and must use already verified local state; it must not perform SQL, Redis, network calls or blocking waits. A controller ID is a stable namespace such as `twilightclans`. The controller must form contexts and budgets from trusted server state, not forward unvalidated client input. Close the registration during controller shutdown. Plugin disable also revokes it.

The remaining service methods return `CompletionStage` and accept calls from any thread; the provider schedules live Bukkit/WorldGuard work on the server thread. Never call `join()` or `get()` on the server thread. Completion callbacks do not promise a Bukkit-safe thread: schedule player messages and other game interactions back onto your plugin's server task.

The returned `ManagedControllerRegistration` owns these controller-scoped methods:

| Method | Contract |
| --- | --- |
| `CompletionStage<Boolean> renewAcl(ManagedClaimIdentity, long revision, long leaseTicks)` | Trust the matching confirmed revision for 1..1200 ticks only after authoritative ACL verification. |
| `CompletionStage<Boolean> invalidateAcl(ManagedClaimIdentity)` | Revoke local trust for this controller's identity. |
| `CompletionStage<ManagedResult> retireOperations(ManagedClaimIdentity, long throughRevision)` | Permanently close the instance's delivery window after the controller's durable outbox closure; see the retirement protocol below. |
| `void close()` | Release the registration and invalidate its ACL leases. |

### Identities, versions and full state

| Type | Fields and meaning |
| --- | --- |
| `ManagedClaimIdentity` | `String controllerId`, `UUID externalId`, `UUID instanceId`, `String serverId`, `UUID worldId`. `externalId` is the permanent clan UUID; `instanceId` identifies one lifetime of its region. `regionId()` returns the associated WG ID. |
| `ManagedOperationContext` | `ManagedClaimIdentity identity`, `UUID operationId`, `long revision`, nullable `UUID initiator`. Revisions are positive and increase per instance. |
| `ManagedClaimState` | `ClaimBounds bounds`, `long areaBudget`, `Set<UUID> members`, `Map<String,String> flags`, nullable `BoundaryStyle boundaryStyle`, `int priority`. Complete desired state, not a patch. Collections are copied. |
| `ManagedClaimSnapshot` | `identity`, `ClaimKey key`, `long revision`, `ManagedLifecycle lifecycle`, nullable `ManagedClaimState state` for deletion. Immutable revision snapshot; the result status determines whether it is confirmed. |
| `ManagedResult` | `ManagedStatus status`, nullable `snapshot`, nullable `operationId`, `String message`; `isSuccess()` accepts only `APPLIED`, `DELETED`, `REPLAYED`. |
| `ManagedClaimPage` | `status`, immutable `claims`, `nextOffset`; bounded reconciliation results including tombstones, with `-1` indicating the end. |
| `ManagedClaimCursorPage` | `status`, immutable `claims`, nullable `UUID nextCursor`, `long generation`; live ordered reconciliation page. A null next cursor indicates the end. |
| `ManagedDiagnostics` | `ready`, `queuedOperations`, `pendingRecovery`, `activeClaims`, `tombstones`, `receipts`, `controllers`, `trustedClaims`, `lastError`, immutable world-availability map. |

`controllerId` accepts lowercase letters, digits, `_`, `.`, `-`, starts with a letter/digit and is at most 64 characters. `serverId` is nonblank and at most 128 characters. A state accepts at most 50 member UUIDs and 128 explicit flags; flag names are at most 128 characters and input values at most 8192. Budget is nonnegative; actual creation requires a positive budget and an area within it.

Flag values are WorldGuard command-input strings, for example `Map.of("pvp", "deny")`. Missing keys clear old explicit flags because this is a complete replacement. The provider validates registered flags and parses/round-trips them before applying. Boundary materials use the dedicated `boundaryStyle` field; internal metadata/style flags cannot be passed through the flags map. A null style selects the configured default.

Area is inclusive X/Z: `(maxX-minX+1)*(maxZ-minZ+1)`. Y does not cost area. The provider enforces the passed budget independently of personal points, and still checks the minimum size, build height, world border, intersections and parent/child restrictions. A 5000-block budget is a maximum, not a request to invent an exactly 5000-block square.

A leader change needs no region ownership transfer. Update only the desired ACL if membership policy changes. The instance UUID is never reused after deletion, even for the same clan. Persist at least server ID, world UUID, instance UUID and WG region ID in the controlling plugin.

### Service methods

| Method | Contract |
| --- | --- |
| `int getApiVersion()` | Actual runtime managed API version. |
| `Set<String> getCapabilities()` | Immutable provider capability set. |
| `String getServerId()` | Durable local server identity after diagnostics report ready; use it to route requests. |
| `ManagedControllerRegistration registerController(Plugin, String, ManagedController)` | Register one live controller namespace and authorization callback. Server thread only. |
| `CompletionStage<ManagedResult> ensureManagedClaim(ManagedOperationContext, ManagedClaimState)` | Idempotently create/recover the externally identified instance with full initial ACL, flags and budget. |
| `CompletionStage<ManagedResult> applyManagedState(ManagedOperationContext, ManagedClaimState)` | Apply a newer complete desired state; replaces the whole ACL atomically. |
| `CompletionStage<ManagedResult> deleteManagedClaim(ManagedOperationContext)` | Durably delete this exact instance and retain terminal protection against resurrection. |
| `CompletionStage<ManagedResult> lookupManagedClaim(ManagedClaimIdentity)` | Indexed local lookup distinguishing absence from unavailable world/provider and wrong server. |
| `CompletionStage<ManagedResult> getOperationResult(UUID)` | Find an earlier operation after timeout or restart; returns `RETIRED` after its delivery window is closed. Does not create a new operation. |
| `CompletionStage<ManagedClaimPage> listManagedClaims(String, int offset, int limit)` | Indexed controller-specific enumeration including terminal instances; `offset >= 0`, `limit` 1..100, `nextOffset == -1` at the end. Preparation costs `O(log N + limit)` without loading missing worlds. |
| `CompletionStage<ManagedClaimCursorPage> scanManagedClaims(String, UUID afterInstanceId, int limit)` | Indexed ascending UUID traversal after an exclusive cursor; null starts a scan, `limit` is 1..100. Returns a generation for detecting changes between pages. |
| `CompletionStage<ManagedDiagnostics> getDiagnostics()` | Queue/recovery/controller/ACL/world health. |
| `CompletionStage<ManagedResult> reconcileManagedClaim(ManagedClaimIdentity)` | Explicit reconciliation of one durable external link and its projection; never guesses ownership from a name. |
| `CompletionStage<ManagedResult> compactJournal()` | Write an atomic durable checkpoint and reclaim superseded journal files on the storage worker. Does not itself authorize receipt retirement. |

Until its delivery window has been retired, the same operation UUID with exactly the same context/action/state is a safe replay; changing any content under that operation UUID yields `PAYLOAD_CONFLICT`. Member order and flag-map order do not affect the canonical payload. At the same revision, equivalent full state is a replay; different state is a conflict. Lower revisions are rejected, and revision gaps are allowed. A delayed snapshot of revision 21 cannot re-add a member removed in revision 22.

Only one active local instance is allowed per controller/external UUID. After terminal deletion, a new instance can be created. Repeating deletion of the old instance does not remove the replacement. Clans must enforce network-wide reservation in its authoritative database; local checks on independent Paper servers cannot implement global uniqueness.

### Explicit results and retries

| Status | Meaning / caller action |
| --- | --- |
| `APPLIED` | Desired state durably confirmed. |
| `DELETED` | Exact instance deletion durably confirmed. |
| `REPLAYED` | Safe replay of an already confirmed result. Receipts can describe historical revisions; use lookup for the current state. |
| `FOUND` | Lookup found the instance and confirmed snapshot. |
| `NOT_FOUND` | Available local storage/world confirms absence; an unknown operation ID has no receipt. |
| `PENDING` | Accepted work has not reached durable confirmation. Poll the same operation. |
| `STALE_REVISION` | Older revision rejected with no state change. |
| `PAYLOAD_CONFLICT` | Reused operation/revision contains incompatible state. |
| `INSTANCE_CONFLICT` | Different active instance or incompatible external link already exists. |
| `TOMBSTONED` | This lifetime is terminal; it cannot be recreated or updated. |
| `INVALID_BOUNDS` | Size, height, world border or geometry invalid. |
| `CONFLICT` | Intersects another protected region or inheritance restriction. |
| `BUDGET_EXCEEDED` | Inclusive area exceeds the controller-authorized limit. |
| `CONTROLLER_DENIED` | Controller absent, disabled or authorization rejected. |
| `CANCELLED` | Pre-change event cancelled the operation. |
| `SAVE_FAILED` | Durable write failed before success was confirmed. |
| `UNCERTAIN` | Result requires reconciliation/polling; do not assume cancellation or start a different operation. |
| `WORLD_UNAVAILABLE` | World is unloaded or WG manager unavailable. Deletion is not confirmed. |
| `PROVIDER_UNAVAILABLE` | Provider is starting, stopped or failed to initialize. |
| `WRONG_SERVER` | Identity belongs to another server; route to that server. |
| `QUEUE_FULL` | Bounded provider queue is full; retry with backoff and the same operation UUID. |
| `DRIFT_DETECTED` | WG projection differs from durable state or an unknown object occupies its identity. Investigate/reconcile explicitly. |
| `RETIRED` | Delivery window or operation UUID was permanently retired. The old result is unavailable; the request is rejected and cannot become a new operation. |

A response timeout does not cancel a durable intention. Query `getOperationResult(operationId)` and/or `lookupManagedClaim(identity)` and reuse the original operation UUID. Never interpret an empty personal `getClaim()` result as evidence of managed deletion.

### Durable storage and restoration

Managed state is authoritative in the plugin's local managed journal. WG cuboids are transient projections: they participate in WorldGuard protection and spatial lookup but WG does not write them to its region database. This prevents delayed WG autosaves from overwriting newer managed state and avoids sharing live WG objects with an I/O worker.

The provider reserves a projection on the server thread while access is gated, writes a recoverable prepared intention and committed receipt through one bounded worker, and only then publishes confirmed success. Journal writes use checksums, file fsync, atomic replacement and directory fsync. Live region inspection and mutations remain on the server thread. Existing personal API saves keep their prior synchronous contract. The managed service never calls `RegionManager.saveChanges()` as its durability mechanism. The journal is part of the server data and must be backed up together with the plugin configuration; managed regions are not present in WorldGuard’s persistent region file.

Startup loads the journal in the background. Recovery projects confirmed state when its world is available and resumes durable incomplete intentions. A world load triggers recovery without requiring any former member to join. Unknown/corrupt journal data causes a safe unavailable state, not an empty database. Direct WG edits are outside normal API guarantees and are detected by projection comparison; unknown objects are not deleted merely because a name matches. `reconcileManagedClaim` is a privileged server integration/recovery operation: it restores known durable state while clearing ACL trust, does not accept new state, and will not overwrite an unrecognized WG object. Present it to administrators only, never as a player-controlled request.

Operation receipts and terminal instance identifiers have no arbitrary automatic TTL. Preserve receipt payloads for the entire possible delivery lifetime of Clans jobs; use the explicit delivery-retirement protocol below to reclaim them safely. The provider does not add Redis, MariaDB or a second clan economy. Clans owns global reservation, routing, durable outbox, membership authority and purchase validation.

### Receipt retirement and journal compaction

`ManagedControllerRegistration.retireOperations(identity, throughRevision)` permanently closes delivery through a positive inclusive revision for that exact instance. It is a trusted controller assertion: before calling it, Clans must durably establish that no queued, delayed, retried, recovered or externally replayed job at or below that revision can be delivered again. Persist that closure in the authoritative job/outbox system before requesting retirement. A current region revision, a renewed lease, a heartbeat, elapsed time or an empty local queue is not proof of delivery closure.

The registered controller may retire only its own complete identity. The floor cannot exceed the current confirmed revision and cannot decrease. Repeating the same floor is idempotent. Unfinished durable intentions on any instance must be resolved before checkpoint maintenance and yield `UNCERTAIN`; queued/running storage work yields `PENDING`, so wait and retry. Invalid floor advancement yields `PAYLOAD_CONFLICT`, an unavailable registration yields `CONTROLLER_DENIED`, and success yields `APPLIED`. Retirement does not delete the region, change its ACL or extend its lease.

```java
controller.retireOperations(identity, durablyClosedThroughRevision).thenAccept(result -> {
    if (!result.isSuccess()) {
        yourPlugin.getLogger().warning("Delivery retirement not confirmed: " + result.status());
    }
});
```

After confirmation, requests at or below the persisted floor return `RETIRED` rather than recreating work. The retired operation UUIDs are also preserved in a sorted disk archive; rewriting an old UUID with a higher revision, a different instance or different content does not make it usable again. `getOperationResult` returns `RETIRED` for an archived UUID and no longer provides its historical full snapshot. Use a new UUID only for a genuinely new authorized operation above the floor, never to evade the rejection of a delayed old job.

Retirement writes a checkpoint retaining the latest full confirmed state of each instance, its terminal state where applicable, unfinished intentions and all unretired receipts. Older retired full receipts are removed from disk and RAM after durable checkpoint publication. One current state and its retirement floor remain available even when that state's original operation has been retired. Tombstones still prevent instance UUID reuse; retirement never permits resurrection.

`managed.compactJournal()` independently consolidates the journal without advancing any delivery floor. It returns `APPLIED` after durable checkpoint publication; `snapshot` and `operationId` can be null. It is a trusted maintenance method, not a player endpoint. It does not discard unretired receipt payloads, so applications that never close delivery windows still accumulate those receipts.

```java
managed.compactJournal().thenAccept(result -> {
    if (!result.isSuccess()) {
        yourPlugin.getLogger().warning("Journal compaction not confirmed: " + result.status());
    }
});
```

Both operations use the bounded storage worker. Checkpoint and archive files are fsynced and atomically published before superseded files are unlinked; startup follows the published checkpoint if a crash interrupted cleanup. A timeout or write error can yield `UNCERTAIN`. It does not prove cancellation: after uncertain checkpoint publication the provider may require restart to load the durable outcome before accepting further writes. Keep the whole managed directory, including the referenced archive and checkpoint, together in backups.

Compaction bounds full receipt payloads only when the controller participates in retirement. It does not promise fixed total disk use: the exact UUID archive grows by 16 bytes per retired operation, plus file headers/checksums; terminal identities and retirement floors consume space per instance. UUID archive searches and checkpoint work run on the storage worker, and the full archive is not loaded as a RAM set. No TTL removes replay protection.

### Indexed reconciliation and concurrent changes

Both page methods use a per-controller rank-indexed AVL tree in UUID order. A page costs `O(log N + limit)` and returns at most 100 immutable snapshots. An offset deep into the index does not trigger a scan of earlier entries. The existing `listManagedClaims` signature remains available for older consumers.

For new reconciliation code, use `scanManagedClaims(controllerId, afterInstanceId, limit)`. Start with a null cursor, process the returned claims, then pass `nextCursor` as the next exclusive cursor. Stop when it is null. The `generation` belongs to that controller and changes when a confirmed snapshot is inserted or replaced. Compare it across pages: a difference means the scan overlapped a change, so discard the partial reconciliation and start a new pass. After provider restart, always begin a new pass; generations are local to one provider lifetime.

The following standalone helper traverses all pages without accumulating the whole controller in the provider or blocking its server thread. `stagePage` must asynchronously stage each batch under one reconciliation-pass identifier; commit that staged pass only when the returned stage succeeds, and discard it on failure. Its immediate callback must be nonblocking.

```java
package example;

import com.twilight.claims.api.managed.ManagedClaimSnapshot;
import com.twilight.claims.api.managed.ManagedClaimsApi;
import com.twilight.claims.api.managed.ManagedStatus;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public final class ManagedReconciliation {
    private ManagedReconciliation() {}

    public static CompletionStage<Long> scan(ManagedClaimsApi api, String controllerId,
            Function<List<ManagedClaimSnapshot>, CompletionStage<Void>> stagePage) {
        Objects.requireNonNull(api);
        Objects.requireNonNull(controllerId);
        Objects.requireNonNull(stagePage);
        return page(api, controllerId, null, null, stagePage);
    }

    private static CompletionStage<Long> page(ManagedClaimsApi api, String controllerId,
            UUID cursor, Long expectedGeneration,
            Function<List<ManagedClaimSnapshot>, CompletionStage<Void>> stagePage) {
        return api.scanManagedClaims(controllerId, cursor, 100).thenCompose(result -> {
            if (result.status() != ManagedStatus.FOUND) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Reconciliation unavailable: " + result.status()));
            }
            if (expectedGeneration != null && result.generation() != expectedGeneration.longValue()) {
                return CompletableFuture.failedFuture(new ConcurrentModificationException(
                        "Controller changed; discard staging and restart the pass"));
            }
            return stagePage.apply(result.claims()).thenCompose(ignored -> {
                if (result.nextCursor() == null) {
                    return CompletableFuture.completedFuture(result.generation());
                }
                return page(api, controllerId, result.nextCursor(), result.generation(), stagePage);
            });
        });
    }
}
```

On `ConcurrentModificationException`, discard the pass's staged results and begin again with a null cursor and a new staging identifier. Do not publish partially reconciled results from a failed pass. Neither offset nor cursor pages provide snapshot isolation. Inserts before an already visited cursor can be absent from that pass; offsets can shift between calls. The generation check detects such changes. Reconciliation should converge through repeated passes; a separate authoritative coordination protocol is needed if a permanently changing controller requires a transactional whole-set view.

### ACL trust and controller failures

A saved membership set is not itself proof that membership is current. While the journal is loading, if it cannot be validated, or if a loaded managed world's protection cannot be restored, sensitive actions are conservatively denied across worlds until protection is ready; a missing WG manager also fails closed in worlds with known managed instances. Worlds without managed state keep their normal behavior when WG regions are disabled there. Resolve the journal/provider failure rather than treating it as an empty region database. After startup, after state changes and after controller disconnection, managed access remains restricted until the controller confirms the exact applied revision against its authoritative membership state.

```java
controller.renewAcl(identity, confirmedRevision, 200L).thenAccept(accepted -> {
    if (!accepted) {
        yourPlugin.getLogger().warning("Managed ACL revalidation rejected");
    }
});
```

`renewAcl(identity, revision, leaseTicks)` returns `CompletionStage<Boolean>` and accepts 1..1200 ticks (up to 60 seconds); it only trusts an existing confirmed revision belonging to that registration. The expiry uses monotonic elapsed time. `invalidateAcl(identity)` explicitly revokes trust. `close()`, plugin disable, revision replacement and provider restart invalidate leases. Redis heartbeat alone is not a membership proof; renew only after establishing current ACL consistency. A bounded lease cannot guarantee instant worldwide revocation during a network partition.

With a fresh trusted revision, the listener admits exact member UUIDs and permits actorless automation to proceed to normal WorldGuard checks. Without trust, both are restricted. The provider's listener only adds denials and never bypasses WG restrictions. It protects block changes, container opening/use, interactions, buckets, entities, hanging entities, vehicles, harvest/fertilization, item access and relevant automation/environmental effects. Splash potion intensities are reduced to zero only for denied targets; lingering clouds filter their target list on every application, including clouds created before ACL revocation. The source is resolved from the projectile or cloud source; if that source is no longer available, its retained Paper owner UUID is used. Truly ownerless/dispenser sources follow the same null-actor trust policy as automation. Filtering is per target across region boundaries, including non-damaging effects such as Slowness; it never restores a WorldGuard-cancelled event, raises a zeroed intensity or re-adds a removed target. Lack of management permission and lack of ACL trust are separate controls. Setting a regional PvP flag to ALLOW is not an implementation of global clan friendly-fire rules.

### Managed events

Events are dispatched on the server thread and are separate from personal API events.

| Event | Methods |
| --- | --- |
| `ManagedClaimPreChangeEvent` | `getContext()`, `getAction()`, nullable `getBefore()`, nullable `getDesiredState()` for deletion, `isCancelled()`, `setCancelled(boolean)`. Cancel before durable work. |
| `ManagedClaimPersistedEvent` | `getContext()`, `getAction()`, `getResult()`, `isRecovery()`. Indicates confirmed durable success, including recoveries. |

The context includes controller, external/instance identity, operation UUID, revision and optional initiator. Events are notifications, not the only recovery mechanism: restart may prevent their delivery. Recover from stored results and Clans' durable jobs instead.

### Provider configuration

```yaml
managed:
  server-id: ""
  queue-capacity: 256
  operation-timeout-seconds: 30
```

An empty `managed.server-id` generates a durable server UUID in `plugins/TwilightClaims/managed/server-id`. An explicit configured ID must match the journal once initialized. Keep it unique per server and stable across restarts. Queue capacity is clamped to 1..4096; operation timeout to 1..300 seconds. A running timeout yields `UNCERTAIN` and does not cancel disk work; a job expiring before execution yields `QUEUE_FULL`. Back off on saturation and reuse the same operation ID. No unbounded per-request disk workers are created.

The managed service capability set is `managed-identities`, `revisioned-state`, `atomic-acl`, `durable-receipts`, `terminal-tombstones`, `async-durability`, `controller-acl-leases`, `indexed-lookup`, `reconciliation`, `persistent-boundary-styles`, `receipt-retirement`, `journal-compaction` and `indexed-cursor-pagination`. Check the last three capabilities before using the 1.7.1 extensions with an older provider.

### Administrative diagnostics

The built-in administrative command requires `twilight.claim.managed.admin` (default: operator):

| Command | Purpose |
| --- | --- |
| `/claimsmanaged status` | Read readiness, durable server ID, queue/recovery counts, controller/lease counts and last error. |
| `/claimsmanaged list <controller> [offset]` | Enumerate 20 durable snapshots, including terminal instances, starting at the given offset. |
| `/claimsmanaged inspect <controller> <externalUUID> <instanceUUID> <worldUUID>` | Inspect the exact external link on this server; reports availability/drift explicitly. |
| `/claimsmanaged reconcile <controller> <externalUUID> <instanceUUID> <worldUUID>` | Explicitly restore a known durable projection, clearing ACL trust. Does not remove or overwrite unknown objects. |

Reconciliation waits for in-flight managed work to finish and reports `PENDING` if busy. It restores the already recorded state, not a new player-submitted geometry or membership set. The controlling plugin must revalidate the ACL afterward.

### Upgrade and rollback

Stop the server and back up the entire TwilightClaims data folder and WorldGuard data before upgrading. Version 1.7.1 automatically reads the 1.7.0 per-operation journal, builds a durable checkpoint and cleans up superseded files without retiring any receipt unless the controller explicitly closes its delivery window. Install the server JAR, keep the API classifier for compilation only, then update the controller integration. Keep the durable server identity stable and unique per server; do not copy an active managed journal to another server as a way of creating a new region.

Version 1.7.0 cannot read the new checkpoint/archive format; rolling back to it requires restoring a consistent pre-upgrade data backup together with the matching plugin/controller versions. On rollback to a version without managed support, remove/disable the controller and explicitly migrate or retire managed regions first. Old versions do not understand the authoritative managed journal and cannot restore transient projections, so replacing the JAR alone does not preserve their protection. Restore a consistent backup when reverting an unsuccessful rollout. Personal regions are not automatically converted.


### Complete managed controller example

The class below is a small adapter for trusted server-side jobs. `submitVerifiedIntent` must be called only after the controlling plugin has validated membership, rights, budget and routing and durably stored the exact context/state in its own outbox. It is not a command endpoint and does not implement global reservation or purchase validation. The exact-operation authorization map is local and nonblocking; after a restart the controller reconstructs jobs from its authoritative store.

Use the earlier Maven setup with the `1.7.1` API classifier. Place this class in `src/main/java/example/ManagedClaimsIntegrationPlugin.java`:

```java
package example;

import com.twilight.claims.api.managed.ManagedAction;
import com.twilight.claims.api.managed.ManagedClaimIdentity;
import com.twilight.claims.api.managed.ManagedClaimState;
import com.twilight.claims.api.managed.ManagedClaimsApi;
import com.twilight.claims.api.managed.ManagedControllerRegistration;
import com.twilight.claims.api.managed.ManagedOperationContext;
import com.twilight.claims.api.managed.ManagedResult;
import com.twilight.claims.api.managed.ManagedStatus;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class ManagedClaimsIntegrationPlugin extends JavaPlugin {
    private final Map<UUID, ApprovedIntent> approved = new ConcurrentHashMap<>();
    private ManagedClaimsApi managed;
    private ManagedControllerRegistration registration;

    private record ApprovedIntent(ManagedOperationContext context, ManagedAction action,
                                  ManagedClaimState state) {}

    @Override
    public void onEnable() {
        managed = Bukkit.getServicesManager().load(ManagedClaimsApi.class);
        if (managed == null || managed.getApiVersion() != 1
                || !managed.getCapabilities().contains("controller-acl-leases")) {
            getLogger().severe("Compatible ManagedClaimsApi is required");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        registration = managed.registerController(this, "twilightclans", (context, action, state) ->
                new ApprovedIntent(context, action, state).equals(approved.get(context.operationId())));
    }

    public CompletionStage<ManagedResult> submitVerifiedIntent(ManagedOperationContext context,
                                                              ManagedAction action,
                                                              ManagedClaimState state) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(action);
        if (!context.identity().controllerId().equals("twilightclans")) {
            throw new IllegalArgumentException("Wrong controller namespace");
        }
        if (action != ManagedAction.DELETE) Objects.requireNonNull(state);
        if (action == ManagedAction.DELETE && state != null) {
            throw new IllegalArgumentException("Deletion has no desired state");
        }
        ApprovedIntent intent = new ApprovedIntent(context, action, state);
        ApprovedIntent previous = approved.putIfAbsent(context.operationId(), intent);
        if (previous != null && !previous.equals(intent)) {
            return CompletableFuture.completedFuture(new ManagedResult(
                    ManagedStatus.PAYLOAD_CONFLICT, null, context.operationId(), "Local intent differs"));
        }
        return switch (action) {
            case ENSURE -> managed.ensureManagedClaim(context, state);
            case APPLY -> managed.applyManagedState(context, state);
            case DELETE -> managed.deleteManagedClaim(context);
        };
    }

    public CompletionStage<Boolean> confirmVerifiedAcl(ManagedClaimIdentity identity, long revision) {
        return registration.renewAcl(identity, revision, 200L);
    }

    public CompletionStage<Boolean> markAclUntrusted(ManagedClaimIdentity identity) {
        return registration.invalidateAcl(identity);
    }

    public void releaseCompletedIntent(UUID operationId) {
        approved.remove(operationId);
    }

    @Override
    public void onDisable() {
        if (registration != null) registration.close();
        approved.clear();
        registration = null;
        managed = null;
    }
}
```

Matching `src/main/resources/plugin.yml`:

```yaml
name: ManagedClaimsIntegrationExample
version: '1.0.0'
main: example.ManagedClaimsIntegrationPlugin
api-version: '26.3'
depend: [TwilightClaims]
```

A trusted Clans job forms a complete state, for example:

```java
ManagedClaimState desired = new ManagedClaimState(
        ClaimBounds.fullHeight(world, 100, 100, 149, 199),
        5000L,
        verifiedMemberUuids,
        Map.of("pvp", "deny"),
        new BoundaryStyle(Material.EMERALD_BLOCK, Material.GOLD_BLOCK),
        0);

ManagedOperationContext context = new ManagedOperationContext(
        durableIdentity, durableOperationUuid, durableRevision, initiatingPlayerUuid);

adapter.submitVerifiedIntent(context, ManagedAction.ENSURE, desired).thenAcceptAsync(result -> {
    if (result.isSuccess()) {
        saveAcknowledgementToYourDurableOutbox(result);
    } else {
        scheduleRetryOrReconciliationAccordingToStatus(result);
    }
});
```

The final fragment uses integration-specific persistence/retry functions rather than implementing MariaDB or Redis. Its completion work runs asynchronously; any Bukkit actions must be scheduled back to the server thread. The bounds are `50 × 100`, exactly 5000 inclusive blocks. Keep `durableIdentity.instanceId()` and `durableOperationUuid` stable when retrying; create new ones only for a genuinely new instance or new job. Use `APPLY` with a higher revision for resize/ACL/budget/flags changes, and `DELETE` with a higher revision and no state to retire the instance. Do not call `confirmVerifiedAcl` merely because a write succeeded: first verify that its exact revision still represents the current authoritative membership state. Release the local approved intent only after its durable lifecycle is resolved; cap and reconcile the controller's own outstanding jobs.
