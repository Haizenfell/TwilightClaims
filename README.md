# TwilightClaims

TwilightClaims is a Paper plugin that turns WorldGuard regions into player claims. Personal claims are full-height cuboid regions stored by WorldGuard, with their owners, members and flags. Plugins can also create externally managed regions whose authoritative state and operation receipts are stored in TwilightClaims’ local managed journal.

Players can create claims, resize them with a shovel, inspect them with a stick, manage trusted players and delete claims remotely from a clickable list. Claim points limit the total area a player can own.

## Requirements

| Component | Version |
| --- | --- |
| Paper | `26.3.build.214-stable`, API version `26.3` |
| Java | JDK 26 for building and running the plugin |
| WorldGuard | `7.0.20`, required |
| WorldEdit | `7.4.6`, required |
| PlaceholderAPI | `2.12.3`, optional |
| Maven | `3.10.0`, installed separately |

## Build and installation

Set `JAVA_HOME` to a JDK 26 installation and run:

```bash
mvn clean package
```

The plugin JAR is `target/TwilightClaims-1.7.0.jar`. The build also produces `target/TwilightClaims-1.7.0-api.jar` for other plugin developers.

1. Install WorldEdit and WorldGuard on the server.
2. Install PlaceholderAPI if you need purchased points or the plugin's placeholders.
3. Copy the plugin JAR into the server's `plugins` directory.
4. Start the server to generate `plugins/TwilightClaims/config.yml`.
5. Configure points, permissions, tools and messages, then restart the server.

## Commands and permissions

| Command | Permission | Description |
| --- | --- | --- |
| `/claim` | `twilight.claim.command.claim` | Create a claim around the player. |
| `/claimsmanaged status\|list\|inspect\|reconcile` | `twilight.claim.managed.admin` | Inspect managed state and explicitly reconcile known durable links. |
| `/claim info` | `twilight.claim.command.claim` | Show information about the current region. |
| `/claim list [page]` | `twilight.claim.command.claim` | List owned claims, with 10 entries per page. |
| `/claim points` | `twilight.claim.command.claimpoints` | Show total, used and free claim points. |
| `/claim unclaim` | `twilight.claim.command.unclaim` | Request confirmation to delete the current owned claim. |
| `/claim tool <shovel|stick>` | `twilight.claim.command.claimtool` | Receive a claim shovel or inspector stick. |
| `/trust <player>` | `twilight.claim.command.trust` | Add a player to the current claim's members. |
| `/untrust <player>` | `twilight.claim.command.untrust` | Remove a player from the current claim's members. |
| `/trustlist` | `twilight.claim.command.trustlist` | Show the current claim's trusted players. |
| `/trustlist <player>` | `twilight.claim.command.trustlist` | Show trusted players for an online player standing in their own claim. |

All command permissions default to operators. Grant the required permissions to regular players through your permissions plugin. The `twilight.claim.command.claim` permission also controls access to the `/claim` command and shovel resizing. Removing claims from the list requires `twilight.claim.command.unclaim`.

## Creating and naming claims

A new claim is centered on the player's X/Z position and covers the world's full build height. The default `claim.min-radius: 5` creates an `11 × 11` claim costing `121` points. Claims cannot overlap other WorldGuard regions except `__global__`. No additional gap between regions is required.

Each player has their own sequence of names: `claim_1`, `claim_2` and so on, shared across their loaded worlds. New claims receive the smallest available number. Deleting `claim_2` from `1, 2, 3` leaves `1, 3`; the next claim receives `2`. Existing names do not change after a restart, resize or deletion of another claim.

WorldGuard stores a unique internal ID such as `5827340192658431_claim_2`. Its 16 random digits are checked for collisions across loaded worlds, and the suffix persists the player's claim number directly in WorldGuard. Player messages use the short `claim_2` name.

New claims have one owner. Trust adds members without granting ownership. Recognized legacy claims keep their original IDs and display names. Administrative and technical regions are not renamed or managed by the plugin.

## Claim list and remote deletion

`/claim list` shows each claim's name, center coordinates, world and area:

```text
- claim_1 | 54, 43 | Overworld | 121 blocks² [Remove]
- claim_2 | -0.5, 42.5 | Nether | 168 blocks² [Remove]
```

Entries are sorted numerically, and page navigation is clickable. Centers use X/Z coordinates; even side lengths produce half-block coordinates.

Clicking `[Remove]` opens a confirmation showing the owner, claim name, center, world, area and points to be released. Click `[Confirm deletion]` to delete the claim or `[Cancel]` to cancel. You can do this from any location or world without teleporting. `/claim unclaim` opens the same confirmation for the current claim.

Both list actions and confirmations expire after 30 seconds, can only be used once and belong to the player and the specific region. Opening another confirmation cancels the previous one. Changes to ownership, boundaries, members or flags invalidate an action; open the list again to continue. Reusing a deleted claim's number cannot make an old button delete its replacement. Pending actions are cleared on disconnect and restart.

Claims with child regions cannot be deleted. A failed WorldGuard save rolls the deletion back without releasing points. Claims in unloaded worlds disappear from the list and point accounting until their world is loaded; the plugin does not load worlds automatically.

World labels support `&` color codes and use actual world names as keys:

```yaml
world-names:
  world: "&aOverworld"
  world_nether: "&cNether"
  world_the_end: "&dEnd"
```

Worlds without a configured label use their actual names.

## Claim points

```text
total = purchased + hours_played * points_per_hour
used = total X/Z area of the player's plugin claims in loaded worlds
free = total - used
```

| Value | Source |
| --- | --- |
| `purchased` | PlaceholderAPI's `%yov_player_key:claim_points%`; zero without PlaceholderAPI. |
| `hours_played` | `%statistic_hours_played%`, falling back to Bukkit's `PLAY_ONE_MINUTE` statistic divided by `72000`. |
| `points_per_hour` | Highest matching rate from `points.groups`, selected by `twilight.claim.points.<group>`; otherwise `points.per-hour-default`. |

Invalid or negative point values contribute zero; fractional played hours are rounded down. Point calculations use `long` arithmetic. An explicitly configured group rate of `0` is valid.

```yaml
points:
  per-hour-default: 50
  groups:
    ember: 55
    moonlight: 100
```

A player with `twilight.claim.points.moonlight` receives `100` points per played hour. If several group permissions match, the highest configured rate applies.

## Trusted players

Trust commands apply to the claim the player is standing in and require ownership. Players are resolved by exact online name or the cache of previously joined players. Adding or removing a member is saved through WorldGuard and rolled back if saving fails.

## Tools

Tool materials, names and lore are configurable. New tools carry a persistent data tag; older tools are recognized by their material and display name.

| Shovel action | Result |
| --- | --- |
| Hold the shovel | Display owned claims in the current world, refreshed every 10 ticks. |
| Right-click an owned claim | Show its boundaries. |
| Right-click a corner, then another position | Move the selected corner. |
| Right-click an edge, then another position | Move the selected edge. |
| Right-click inside a claim | Show the `/claim unclaim` hint. |
| Right-click another player's region | Show an ownership error and error visualization. |

Each resized side must be at least `2 * claim.min-radius + 1` blocks long. Expanding requires enough free points for the added area; shrinking releases points. The plugin checks ownership again before applying a resize, rejects overlaps and world-border violations, and preserves region owners, members, flags, priority and parent/child relationships. Save failures roll the change back.

| Inspector stick action | Result |
| --- | --- |
| Left-click a block or right-click a region | Inspect the region and its owner. |
| Left-click an unclaimed block | Show an unclaimed actionbar message. |
| Right-click outside a region | Find the nearest plugin claim within `stick-tool.scan-radius`. |
| Find a nearby claim | Show its owner, distance and direction arrow; highlight it temporarily when close enough. |

The inspector can display other WorldGuard regions but cannot modify them. Its search does not load chunks. Non-cuboid regions are measured using their bounding rectangle.

## Visualization

Boundaries use client-side block changes and do not modify the world. Markers follow the highest block at each X/Z position. Displays are cleared on disconnect, world change and plugin shutdown.

| Setting | Purpose |
| --- | --- |
| `visual.corner-block`, `visual.extend-block` | Normal corner and edge materials. |
| `visual.pos1-block` | Selected corner material. |
| `visual.corner-block_error`, `visual.extend-block_error` | Error marker materials. |
| `visual.max-distance` | Maximum marker distance; values at or below zero disable the limit. |
| `visual.only-loaded-chunks` | Restrict markers to loaded chunks. |
| `visual.edge-step-small`, `visual.edge-step-big` | Marker spacing for short and long edges. |
| `visual.edge-big-threshold` | Edge length at which larger spacing applies. |
| `visual.visual-duration-ticks` | Duration of a normal one-time display. |
| `visual.error-duration` | Error display duration. |

## PlaceholderAPI

The expansion identifier is `tc`.

| Placeholder | Value |
| --- | --- |
| `%tc_points_total%` | Total point limit. |
| `%tc_points_used%` | Points used by claims. |
| `%tc_points_free%` | Free points. |
| `%tc_points_purchased%` | Purchased points. |
| `%tc_points_hours_played%` | Played hours. |
| `%tc_points_hours%` | Points earned through played hours. |
| `%tc_points_per_hour%` | Current hourly point rate. |

These placeholders use compact values rounded upward: `13600` becomes `14k`, `1001` becomes `2k`, and `999` stays `999`. Add `_raw` before the closing `%` for exact values, for example `%tc_points_total_raw%`. Every listed placeholder has a raw variant.

Placeholder values come from a snapshot refreshed on the main thread once per second; asynchronous requests return zero before the first update. Commands and resizing checks read current values. Without PlaceholderAPI, the expansion is not registered, purchased points are zero and played-hour points still work through Bukkit statistics.

## Configuration

| Section | Purpose |
| --- | --- |
| `claim` | Initial claim radius and minimum resize dimensions. |
| `world-names` | World labels with `&` color support. |
| `points` | Default and permission-group hourly rates. |
| `tools.shovel`, `tools.stick` | Tool materials, names and lore. |
| `stick-tool` | Inspector search radius, messages and direction arrows. |
| `visual` | Boundary materials, spacing, range and duration. |
| `messages` | Player messages, list entries and confirmation buttons. |
| `actionbar.duration-ticks` | Duration of repeated shovel actionbar messages. |
| `managed` | Durable server ID, managed operation queue capacity and response timeout. |

Message templates support placeholders such as `%id%`, `%owner%`, `%x%`, `%z%`, `%world%` and `%area%`, depending on the message. Use the bundled `config.yml` as the reference when customizing templates.

## Developer API

The complete method reference, data contracts and runnable integration example are in [API.md](API.md).

The public API is `com.twilight.claims.api.TwilightClaimsApi`, registered with Bukkit's `ServicesManager`. API version `1` is exposed as `TwilightClaimsApi.API_VERSION`. Use the public interface and immutable `Claim`, `ClaimKey`, `ClaimBounds`, `ClaimPoints` and `BoundaryStyle` values rather than internal managers or live WorldGuard region objects.

Install TwilightClaims into your local Maven repository with `mvn clean install`, then add this dependency to the consuming plugin:

```xml
<dependency>
    <groupId>com.twilight</groupId>
    <artifactId>TwilightClaims</artifactId>
    <version>1.7.0</version>
    <classifier>api</classifier>
    <scope>provided</scope>
</dependency>
```

Declare your own Paper API dependency as `provided`. Add the WorldGuard API as `provided` when using the typed flag methods. Do not shade the TwilightClaims API into your plugin: all plugins must share the service's API classes.

Add the runtime dependency to your `plugin.yml`:

```yaml
depend: [TwilightClaims]
```

Resolve the API in your plugin's `onEnable()`:

```java
TwilightClaimsApi api = Bukkit.getServicesManager().load(TwilightClaimsApi.class);
if (api == null) {
    throw new IllegalStateException("TwilightClaims API is unavailable");
}
```

The service is unregistered when TwilightClaims is disabled. Previously obtained service instances reject further calls after shutdown. `TwilightClaims#getApi()` is also available when depending on the complete plugin artifact instead of the API classifier.

### API operations

| Area | Methods |
| --- | --- |
| Discovery | `getClaims()`, `getClaims(World)`, `getClaims(UUID)`, `getClaim(ClaimKey)`, `findClaim(UUID, long)` |
| Spatial queries | `getClaimAt(Location)`, `getClaimAt(Player, Location)`, `findNearestClaim(Location, double)`, `hasConflict(World, ClaimBounds, ClaimKey)` |
| Lifecycle | `createClaim(...)`, `createChildClaim(...)`, `resizeClaim(...)`, `deleteClaim(ClaimKey)`, `transferOwner(...)` |
| Membership | `isOwner(...)`, `isTrusted(...)`, `addMember(...)`, `removeMember(...)`, `clearMembers(...)` |
| WorldGuard metadata | `getFlag(...)`, `getFlags(...)`, `setFlag(...)`, `clearFlags(...)`, `setPriority(...)`, `setParent(...)` |
| Boundary styles | `getDefaultBoundaryStyle()`, `getBoundaryStyle(...)`, `getBoundaryStyleOverride(...)`, `setBoundaryStyle(...)`, `resetBoundaryStyle(...)` |
| Visualization | `showBoundary(...)`, `showErrorBoundary(...)`, `showOwnedBoundaries(Player)`, `clearBoundaries(Player)`, `isVisualizing(Player)` |
| Points | `getPoints(Player)`, `getCachedPoints(UUID)`, `canAfford(Player, long)` |
| Tools and player UI | `getShovel()`, `getInspector()`, `isShovel(ItemStack)`, `isInspector(ItemStack)`, `showClaimList(Player, int)`, `requestDeletion(Player, ClaimKey)` |

`ClaimKey` contains the world UUID and permanent WorldGuard ID. A short name such as `claim_2` is only a player-facing label. Use `findClaim(ownerUuid, 2)` to resolve a personal number, then retain the returned key for subsequent operations. When ownership is transferred, a conflicting personal number can require a new permanent ID; always use the returned `Claim.key()` after a transfer.

Snapshots are immutable and do not update automatically. Call `getClaim(key)` again after changes. All claim queries, mutations, tool operations and visualizations require the server main thread. Schedule work with Bukkit's scheduler when calling from an asynchronous task. `getCachedPoints(UUID)` is safe to read asynchronously and returns an empty value until a snapshot is available; `getPluginVersion()` only reads plugin metadata.

The API is a trusted server-plugin interface. Direct mutations do not check a player's command permissions or require chat confirmation. `requestDeletion` provides the normal permission-checked confirmation flow instead. Creation, resizing and transfer accept `enforcePointLimit`: use `true` to apply the owner's current point budget, which requires that owner to be online. `false` explicitly allows trusted provisioning for offline owners without point checks. World-border, build-height, minimum-size, overlap, parent/child and persistence checks still apply. API operations manage recognized TwilightClaims claims, not arbitrary administrative regions.

Points are calculated from the existing playtime and external purchased-points sources. The API reads these values; it does not overwrite an external economy or PlaceholderAPI provider's balance.

### Create a claim with custom boundary blocks

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
        false
);
```

The five materials represent the normal corner, normal edge, selected corner during editing, error/discovery corner and error/discovery edge. Every material must be a non-air block. A shorter `new BoundaryStyle(corner, edge)` constructor uses those two materials for all states.

Create a contained subregion with `createChildClaim`. The parent must be a recognized TwilightClaims claim in a loaded world. Its cuboid and all ancestors must contain the entire child; siblings and unrelated regions must not overlap it. Child styles are independent: use `null` for the global default or pass a custom style.

```java
Claim parent = api.createClaim(
        ownerUuid,
        world,
        ClaimBounds.fullHeight(world, 200, 200, 240, 240),
        false
);
Claim child = api.createChildClaim(
        ownerUuid,
        parent.key(),
        ClaimBounds.fullHeight(world, 205, 205, 215, 215),
        new BoundaryStyle(Material.OBSIDIAN, Material.CRYING_OBSIDIAN),
        false
);
```

Parents and children remain separate claims for point accounting, so overlapping parent and child areas each contribute to their respective owner's usage. The `enforcePointLimit` argument follows the same rules as normal creation. A parent with children cannot be deleted until its children have been removed or detached.

The style belongs to that region and is saved with its WorldGuard flags using `twilightclaims-boundary-style`. It survives restarts, resizing and ownership changes. The claim shovel, automatic boundary display, inspector discovery and API visualization all use the same style. Claims without an override use the existing `visual` configuration.

```java
api.setBoundaryStyle(claim.key(), new BoundaryStyle(Material.OBSIDIAN, Material.CRYING_OBSIDIAN));
api.showBoundary(viewer, claim.key(), 200L);
api.showErrorBoundary(viewer, claim.key(), 5L);
api.clearBoundaries(viewer);
api.resetBoundaryStyle(claim.key());
```

`showBoundary` durations use ticks; `showErrorBoundary` durations use seconds. The viewer must be in the same world as the claim. Displays remain client-side and obey the configured marker-distance, chunk and work limits. Style changes refresh active displays. Resetting an override restores the global configured materials.

### Metadata and events

```java
api.addMember(claim.key(), trustedPlayerUuid);
api.setFlag(claim.key(), Flags.PVP, StateFlag.State.DENY);
api.setFlag(claim.key(), Flags.PVP, null);
api.setPriority(claim.key(), 10);
api.setParent(child.key(), parent.key());
api.setParent(child.key(), null);
```

Passing `null` to `setFlag` clears that flag; passing `null` to `setParent` clears the parent. `clearFlags` removes all explicit flags, including any custom boundary-style override. Flags supplied to `setFlag` must be registered with WorldGuard. Collection-valued inputs and outputs are copied so changing a caller-owned collection cannot silently alter a claim. Parent and child must be in the same loaded world, inheritance must be acyclic and the parent's volume must contain the child. Deleting a claim with children is rejected.

`com.twilight.claims.api.event.ClaimPreChangeEvent` is cancellable and exposes the change type, optional previous snapshot and proposed values. `ClaimChangeEvent` is sent after a successful persisted change and exposes optional before/after snapshots. `ClaimChangeType` identifies creation, resizing, deletion, transfer, membership, flags, priority, parent and boundary-style changes. These events also cover the corresponding built-in player operations. Schedule follow-up mutations rather than changing claims synchronously from a pre-change listener.

`ClaimPreChangeEvent.getChanges()` is an immutable map with these keys:

| Change | Proposed values |
| --- | --- |
| `CREATE` | `owner` (UUID), `worldId` (UUID), `bounds` (ClaimBounds), `enforcePointLimit` (boolean), optional `boundaryStyle` and `parent` (ClaimKey). |
| `RESIZE` | `bounds`, `enforcePointLimit`. |
| `TRANSFER_OWNER` | `owner`, `enforcePointLimit`. |
| `ADD_MEMBER`, `REMOVE_MEMBER` | `member` (UUID). |
| `SET_FLAG` | `flag` (registered flag name), optional `value`; an absent value clears the flag. |
| `SET_PRIORITY` | `priority` (int). |
| `SET_PARENT` | Optional `parent`; an absent parent clears the relationship. |
| `SET_BOUNDARY_STYLE` | `boundaryStyle` (BoundaryStyle). |

Deletion, member/flag clearing and style reset use an empty map. Creation has no previous snapshot, and deletion has no resulting snapshot. Changes made directly through WorldGuard outside this API do not produce TwilightClaims events.

```java
@EventHandler
public void onClaimChange(ClaimPreChangeEvent event) {
    if (event.getChangeType() == ClaimChangeType.DELETE
            && event.getBefore().map(claim -> protectedClaimKeys.contains(claim.key())).orElse(false)) {
        event.setCancelled(true);
    }
}
```

Mutations can throw `ClaimOperationException`; inspect `getReason()` for `NOT_FOUND`, `WORLD_UNAVAILABLE`, `INVALID_BOUNDS`, `CONFLICT`, `NOT_ENOUGH_POINTS`, `HAS_CHILDREN`, `SAVE_FAILED`, `INVALID_OWNER`, `INVALID_STATE` or `CANCELLED`. Invalid arguments and off-thread access use `IllegalArgumentException` or `IllegalStateException`. A cancelled or failed change does not emit a successful post-change event. WorldGuard save failures restore the previous region state.

## Managed regions for other plugins

TwilightClaims 1.7.0 adds the separate `ManagedClaimsApi` service without changing the personal API v1 interface. It provides external identities, complete revisioned state, atomic replacement of up to 50 members, independent area budgets, idempotent operation IDs, durable deletion tombstones, asynchronous confirmation, recovery and controller ACL leases. Custom boundary materials work with managed regions as well.

Managed regions have no player owner and do not consume personal claim points. Personal commands, the shovel and API v1 mutations cannot change them. The controller applies authorized full snapshots and must verify/renew the exact ACL revision before members receive access. Controller shutdown, restart, expiry or loss of synchronization restricts managed access; normal WorldGuard denials always remain effective.

See [the managed API reference and integration examples](API.md#managed-regions-for-external-controllers) for methods, statuses, controller registration, leases, configuration, durability and recovery. Back up the entire `plugins/TwilightClaims/managed` directory: managed WG cuboids are transient projections restored from that journal. Do not roll back to an older JAR while managed regions are active without a planned migration or a consistent backup. Global cross-server uniqueness and authoritative clan membership remain the controller’s responsibility.
