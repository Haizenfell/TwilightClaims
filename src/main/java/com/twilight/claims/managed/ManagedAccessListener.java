package com.twilight.claims.managed;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.wg.WGHook;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ManagedAccessListener implements Listener {
    private final WGHook wg;
    private final BiPredicate<ProtectedRegion, UUID> authorized;
    private final BooleanSupplier protectionReady;
    private final Predicate<UUID> requiresProtection;

    public ManagedAccessListener(WGHook wg, BiPredicate<ProtectedRegion, UUID> authorized) {
        this(wg, authorized, () -> true);
    }

    public ManagedAccessListener(WGHook wg, BiPredicate<ProtectedRegion, UUID> authorized,
                                 BooleanSupplier protectionReady) {
        this(wg, authorized, protectionReady, worldId -> true);
    }

    public ManagedAccessListener(WGHook wg, BiPredicate<ProtectedRegion, UUID> authorized,
                                 BooleanSupplier protectionReady, Predicate<UUID> requiresProtection) {
        this.wg = Objects.requireNonNull(wg, "wg");
        this.authorized = Objects.requireNonNull(authorized, "authorized");
        this.protectionReady = Objects.requireNonNull(protectionReady, "protectionReady");
        this.requiresProtection = Objects.requireNonNull(requiresProtection, "requiresProtection");
    }

    public boolean isDenied(Location location, UUID actor) {
        try {
            if (!protectionReady.getAsBoolean()) return true;
        } catch (RuntimeException exception) {
            return true;
        }
        if (location == null || location.getWorld() == null) return false;
        RegionManager manager = wg.getManager(location.getWorld());
        if (manager == null) {
            try {
                return requiresProtection.test(location.getWorld().getUID());
            } catch (RuntimeException exception) {
                return true;
            }
        }
        for (ProtectedRegion region : manager.getApplicableRegions(BukkitAdapter.asBlockVector(location))) {
            if (!ManagedRegions.isManaged(region)) continue;
            try {
                if (!authorized.test(region, actor)) return true;
            } catch (RuntimeException exception) {
                return true;
            }
        }
        return false;
    }

    private boolean denied(Block block, UUID actor) {
        return block != null && isDenied(block.getLocation(), actor);
    }

    private static UUID actor(Entity entity) {
        if (entity instanceof Player player) return player.getUniqueId();
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            return source instanceof Entity shooter ? actor(shooter)
                    : source == null ? projectile.getOwnerUniqueId() : null;
        }
        if (entity instanceof Tameable tameable && tameable.getOwner() != null) {
            return tameable.getOwner().getUniqueId();
        }
        return null;
    }

    private static UUID potionActor(ProjectileSource source, UUID ownerId) {
        return source instanceof Entity entity ? actor(entity) : source == null ? ownerId : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (denied(event.getBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (denied(event.getBlockPlaced(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        if (event.getReplacedBlockStates().stream().anyMatch(state -> isDenied(state.getLocation(), actor))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        UUID actor = event.getPlayer().getUniqueId();
        if (clicked != null && (denied(clicked, actor) || event.getItem() != null
                && event.getItem().getType().name().endsWith("_SPAWN_EGG")
                && denied(clicked.getRelative(event.getBlockFace()), actor))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (isDenied(event.getEntity().getLocation(),
                event.getPlayer() == null ? null : event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        if (denied(event.getBlock(), actor) || denied(event.getBlockClicked(), actor)
                || denied(event.getBlockClicked().getRelative(event.getBlockFace()), actor)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denied(event.getBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (isDenied(event.getEntity().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (denied(event.getBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isDenied(event.getInventory().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isDenied(event.getView().getTopInventory().getLocation(), event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isDenied(event.getView().getTopInventory().getLocation(), event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (isDenied(event.getRightClicked().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (isDenied(event.getRightClicked().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (isDenied(event.getRightClicked().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (isDenied(event.getEntity().getLocation(), actor(event.getDamager()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        UUID actor = potionActor(event.getPotion().getShooter(), event.getPotion().getOwnerUniqueId());
        for (var entity : event.getAffectedEntities()) {
            if (isDenied(entity.getLocation(), actor)) event.setIntensity(entity, 0.0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloud(AreaEffectCloudApplyEvent event) {
        UUID actor = potionActor(event.getEntity().getSource(), event.getEntity().getOwnerUniqueId());
        event.getAffectedEntities().removeIf(entity -> isDenied(entity.getLocation(), actor));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (isDenied(event.getEntity().getLocation(), event.getPlayer() == null ? null : event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        UUID actor = event instanceof HangingBreakByEntityEvent byEntity ? actor(byEntity.getRemover()) : null;
        if (isDenied(event.getEntity().getLocation(), actor)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (isDenied(event.getVehicle().getLocation(), actor(event.getAttacker()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (isDenied(event.getVehicle().getLocation(), actor(event.getAttacker()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (isDenied(event.getVehicle().getLocation(), actor(event.getEntered()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (isDenied(event.getEntity().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (isDenied(event.getEntity().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (isDenied(event.getEntity().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (denied(event.getLectern().getBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLecternInsert(PlayerInsertLecternBookEvent event) {
        if (denied(event.getBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLecternPage(PlayerLecternPageChangeEvent event) {
        if (denied(event.getLectern().getBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlowerpot(PlayerFlowerPotManipulateEvent event) {
        if (denied(event.getFlowerpot(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (isDenied(event.getVillager().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (denied(event.getHarvestedBlock(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        UUID actor = event.getPlayer() == null ? null : event.getPlayer().getUniqueId();
        if (denied(event.getBlock(), actor)
                || event.getBlocks().stream().anyMatch(state -> isDenied(state.getLocation(), actor))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        UUID actor = event.getPlayer() == null ? null : event.getPlayer().getUniqueId();
        if (isDenied(event.getLocation(), actor)
                || event.getBlocks().stream().anyMatch(state -> isDenied(state.getLocation(), actor))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getCaught() != null && isDenied(event.getCaught().getLocation(), event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (isDenied(event.getItem().getLocation(), actor(event.getEntity()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (isDenied(event.getItemDrop().getLocation(), event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (isDenied(event.getSource().getLocation(), null) || isDenied(event.getDestination().getLocation(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isDenied(event.getInventory().getLocation(), null) || isDenied(event.getItem().getLocation(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (denied(block, null)
                || block.getBlockData() instanceof Directional direction && denied(block.getRelative(direction.getFacing()), null)
                || event instanceof BlockDispenseArmorEvent armor && isDenied(armor.getTargetEntity().getLocation(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (denied(event.getBlock(), null) || denied(event.getToBlock(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (denied(event.getBlock(), null) || event.getBlocks().stream().anyMatch(block -> denied(block, null)
                || denied(block.getRelative(event.getDirection()), null))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (denied(event.getBlock(), null) || event.getBlocks().stream().anyMatch(block -> denied(block, null)
                || denied(block.getRelative(event.getDirection()), null))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> denied(block, null));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> denied(block, null));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBlockChange(EntityChangeBlockEvent event) {
        if (denied(event.getBlock(), actor(event.getEntity()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        if (denied(event.getBlock(), actor(event.getEntity()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPhysicalInteract(EntityInteractEvent event) {
        if (denied(event.getBlock(), actor(event.getEntity()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (denied(event.getBlock(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (denied(event.getBlock(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (denied(event.getBlock(), actor(event.getIgnitingEntity()))) event.setCancelled(true);
    }
}
