# TwilightClaims Developer API

Complete reference for TwilightClaims **1.6.0**, public API version **1**.

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

## Dependencies and connection

### Build artifacts

Build and install TwilightClaims into your local Maven repository:

```bash
mvn clean install
```

The build produces:

| Artifact | Purpose |
| --- | --- |
| `TwilightClaims-1.6.0.jar` | The server plugin. Install this in the server's `plugins` directory. |
| `TwilightClaims-1.6.0-api.jar` | Public API classes for compilation. Do not install this as a server plugin. |

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
        <version>1.6.0</version>
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
| `String getPluginVersion()` | Installed plugin version, currently `1.6.0`. |
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
