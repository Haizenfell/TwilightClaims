package com.twilight.claims.managed;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.api.ClaimOperationException;
import com.twilight.claims.api.managed.*;
import com.twilight.claims.api.managed.event.ManagedClaimPersistedEvent;
import com.twilight.claims.api.managed.event.ManagedClaimPreChangeEvent;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.twilight.claims.api.managed.ManagedStatus.*;

public final class DefaultManagedClaimsApi implements ManagedClaimsApi, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ManagedWorldGuard regions;
    private final ManagedJournal journal;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TwilightClaims-managed-storage");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, ManagedJournal.Entry> receipts = new HashMap<>();
    private final Map<UUID, ManagedJournal.Entry> current = new HashMap<>();
    private final Map<UUID, ManagedJournal.Entry> pending = new HashMap<>();
    private final Map<String, UUID> external = new HashMap<>();
    private final Map<String, Registration> controllers = new HashMap<>();
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final Set<UUID> drift = new HashSet<>();
    private final Set<UUID> installed = new HashSet<>();
    private final Set<UUID> unprotected = new HashSet<>();
    private final Set<CompletableFuture<?>> waiting = ConcurrentHashMap.newKeySet();
    private final AtomicInteger scheduled = new AtomicInteger();
    private final Deque<Job> queue = new ArrayDeque<>();
    private final int capacity;
    private final long timeoutNanos;
    private volatile boolean active = true;
    private volatile boolean ready;
    private volatile String serverId;
    private String lastError = "";
    private Job running;
    private boolean recovering;
    private boolean pumping;
    private int maintenanceCounter;

    private record Lease(long revision, long expiresAt) {}
    private static final class Job {
        final ManagedOperationContext context;
        final ManagedAction action;
        final ManagedClaimState state;
        final CompletableFuture<ManagedResult> future;
        final long submitted = System.nanoTime();
        Job(ManagedOperationContext context, ManagedAction action, ManagedClaimState state,
            CompletableFuture<ManagedResult> future) {
            this.context = context; this.action = action; this.state = state; this.future = future;
        }
    }

    public DefaultManagedClaimsApi(JavaPlugin plugin, WGHook wg, ClaimsConfig config) {
        this.plugin = plugin;
        this.regions = new ManagedWorldGuard(wg, config);
        this.journal = new ManagedJournal(plugin.getDataFolder().toPath().resolve("managed"));
        this.capacity = Math.clamp(plugin.getConfig().getInt("managed.queue-capacity", 256), 1, 4096);
        this.timeoutNanos = TimeUnit.SECONDS.toNanos(Math.clamp(plugin.getConfig().getLong("managed.operation-timeout-seconds", 30), 1, 300));
        this.serverId = plugin.getConfig().getString("managed.server-id", "");
        String configuredServerId = serverId;
        io.execute(() -> {
            try {
                var loaded = journal.load(configuredServerId);
                onMain(() -> initialize(loaded));
            } catch (Exception error) {
                onMain(() -> fail("Cannot load managed journal", error));
            }
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::maintenance, 20L, 20L);
    }

    private void initialize(ManagedJournal.Loaded loaded) {
        serverId = loaded.serverId();
        try {
            var entries = loaded.entries().stream().sorted(Comparator.comparingLong(e -> e.context().revision())).toList();
            for (var entry : entries) {
                var id = entry.context().identity();
                var previous = current.get(id.instanceId());
                if (previous != null && (!previous.context().identity().equals(id)
                        || previous.action() == ManagedAction.DELETE && entry.action() != ManagedAction.DELETE
                        || previous.context().revision() == entry.context().revision()
                        && (!Objects.equals(previous.state(), entry.state()) || previous.action() == ManagedAction.DELETE != (entry.action() == ManagedAction.DELETE))))
                    throw new IllegalStateException("Conflicting durable managed revisions");
                receipts.put(entry.context().operationId(), entry);
                if (entry.confirmed()) current.put(id.instanceId(), entry);
                else pending.merge(id.instanceId(), entry, (a, b) -> a.context().revision() > b.context().revision() ? a : b);
            }
            for (var entry : current.values()) {
                var id = entry.context().identity();
                if (entry.action() != ManagedAction.DELETE) {
                    UUID occupied = external.put(externalKey(id), id.instanceId());
                    if (occupied != null && !occupied.equals(id.instanceId()))
                        throw new IllegalStateException("Multiple active instances for one external identity");
                    if (regions.isAvailable(id.worldId())) restore(entry);
                    else if (Bukkit.getWorld(id.worldId()) != null) unprotected.add(id.instanceId());
                }
            }
            pending.forEach((id, entry) -> {
                var confirmed = current.get(id);
                if (confirmed != null && confirmed.context().revision() >= entry.context().revision())
                    throw new IllegalStateException("Prepared operation is older than confirmed state");
            });
            ready = true;
            recoverNext();
        } catch (Exception error) { fail("Managed recovery requires attention", error); }
    }

    @Override public int getApiVersion() { return 1; }
    @Override public Set<String> getCapabilities() {
        return Set.of("managed-identities", "revisioned-state", "atomic-acl", "durable-receipts", "terminal-tombstones",
                "async-durability", "controller-acl-leases", "indexed-lookup", "reconciliation", "persistent-boundary-styles");
    }
    @Override public String getServerId() { return serverId; }
    public boolean isProtectionReady() { return active && ready && unprotected.isEmpty(); }

    public boolean hasManagedClaims(UUID worldId) {
        return current.values().stream().anyMatch(e -> e.action() != ManagedAction.DELETE && e.context().identity().worldId().equals(worldId))
                || pending.values().stream().anyMatch(e -> e.context().identity().worldId().equals(worldId));
    }

    @Override public ManagedControllerRegistration registerController(Plugin owner, String controllerId, ManagedController controller) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Register controllers on the server thread");
        if (!active || !owner.isEnabled()) throw new IllegalStateException("Provider or controller is disabled");
        Objects.requireNonNull(controller);
        if (!controllerId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid controller ID");
        if (controllers.containsKey(controllerId)) throw new IllegalStateException("Controller ID is already registered");
        var registration = new Registration(owner, controllerId, controller);
        controllers.put(controllerId, registration);
        return registration;
    }

    private final class Registration implements ManagedControllerRegistration {
        final Plugin owner;
        final String id;
        final ManagedController controller;
        volatile boolean open = true;
        Registration(Plugin owner, String id, ManagedController controller) {
            this.owner = Objects.requireNonNull(owner); this.id = id; this.controller = controller;
        }
        boolean usable() { return open && owner.isEnabled() && controllers.get(id) == this; }
        @Override public CompletionStage<Boolean> renewAcl(ManagedClaimIdentity identity, long revision, long leaseTicks) {
            Objects.requireNonNull(identity);
            if (leaseTicks < 1 || leaseTicks > 1200) throw new IllegalArgumentException("ACL lease must be 1..1200 ticks");
            return call(() -> {
                var entry = current.get(identity.instanceId());
                if (!ready || !usable() || !id.equals(identity.controllerId()) || !local(identity)
                        || entry == null || !entry.context().identity().equals(identity) || entry.action() == ManagedAction.DELETE
                        || entry.context().revision() != revision || pending.containsKey(identity.instanceId())
                        || drift.contains(identity.instanceId()) || !regions.isAvailable(identity.worldId())
                        || !regions.matches(identity, entry.state())) return false;
                leases.put(identity.instanceId(), new Lease(revision, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leaseTicks * 50)));
                return true;
            }, false);
        }
        @Override public CompletionStage<Boolean> invalidateAcl(ManagedClaimIdentity identity) {
            return call(() -> {
                if (!usable() || !id.equals(identity.controllerId())) return false;
                leases.remove(identity.instanceId()); return true;
            }, false);
        }
        @Override public void close() {
            open = false;
            onMain(() -> {
                controllers.remove(id, this);
                current.values().stream().filter(e -> e.context().identity().controllerId().equals(id))
                        .forEach(e -> leases.remove(e.context().identity().instanceId()));
            });
        }
    }

    public boolean isAuthorized(ProtectedRegion region, UUID player) {
        if (!active || !ready) return false;
        UUID instance;
        try {
            String suffix = region.getId().substring("tcmanaged_".length());
            instance = new UUID(Long.parseUnsignedLong(suffix.substring(0, 16), 16),
                    Long.parseUnsignedLong(suffix.substring(16), 16));
        }
        catch (RuntimeException error) { return false; }
        var entry = current.get(instance);
        var lease = leases.get(instance);
        if (entry == null || lease == null || entry.action() == ManagedAction.DELETE || pending.containsKey(instance)
                || drift.contains(instance) || lease.revision() != entry.context().revision()
                || !regions.ownsProjection(entry.context().identity(), region)
                || System.nanoTime() - lease.expiresAt() >= 0) return false;
        var registration = controllers.get(entry.context().identity().controllerId());
        return registration != null && registration.usable() && (player == null || entry.state().members().contains(player));
    }

    @Override public CompletionStage<ManagedResult> ensureManagedClaim(ManagedOperationContext c, ManagedClaimState s) {
        return mutate(c, ManagedAction.ENSURE, Objects.requireNonNull(s));
    }
    @Override public CompletionStage<ManagedResult> applyManagedState(ManagedOperationContext c, ManagedClaimState s) {
        return mutate(c, ManagedAction.APPLY, Objects.requireNonNull(s));
    }
    @Override public CompletionStage<ManagedResult> deleteManagedClaim(ManagedOperationContext c) {
        return mutate(c, ManagedAction.DELETE, null);
    }

    private CompletionStage<ManagedResult> mutate(ManagedOperationContext context, ManagedAction action, ManagedClaimState state) {
        Objects.requireNonNull(context);
        CompletableFuture<ManagedResult> future = track(new CompletableFuture<>());
        if (!dispatch(() -> {
            if (!ready) { future.complete(result(PROVIDER_UNAVAILABLE, null, context.operationId(), lastError)); return; }
            if (queue.size() + (running == null ? 0 : 1) >= capacity) {
                future.complete(result(QUEUE_FULL, null, context.operationId(), "Managed queue is full")); return;
            }
            queue.addLast(new Job(context, action, state, future));
            pump();
        })) future.complete(result(active ? QUEUE_FULL : PROVIDER_UNAVAILABLE, null, context.operationId(), "Provider cannot accept work"));
        return future;
    }

    private void pump() {
        if (!active || running != null || recovering || !ready || pumping) return;
        pumping = true;
        try {
            while (active && ready && running == null && !recovering && !queue.isEmpty()) {
                Job job = queue.removeFirst();
                running = job;
                try { begin(job); }
                catch (Exception error) { failOperation(job, error); }
            }
        } finally { pumping = false; }
    }

    private void begin(Job job) throws Exception {
        var c = job.context;
        var id = c.identity();
        if (!local(id)) { finish(job, result(WRONG_SERVER, null, c.operationId(), "Request targets another server")); return; }
        String fingerprint = ManagedJournal.fingerprint(c, job.action, job.state);
        var receipt = receipts.get(c.operationId());
        if (receipt != null) {
            ManagedStatus status = !receipt.fingerprint().equals(fingerprint) ? PAYLOAD_CONFLICT
                    : receipt.confirmed() ? REPLAYED : UNCERTAIN;
            finish(job, result(status, receipt.snapshot(), c.operationId(), "Previously received operation")); return;
        }
        var previous = current.get(id.instanceId());
        var unfinished = pending.get(id.instanceId());
        if (unfinished != null) { finish(job, result(UNCERTAIN, unfinished.snapshot(), c.operationId(), "Instance has unfinished durable work")); return; }
        if (previous != null && !previous.context().identity().equals(id)) {
            finish(job, result(INSTANCE_CONFLICT, null, c.operationId(), "Instance UUID is already bound")); return;
        }
        if (!regions.isAvailable(id.worldId())) { finish(job, result(WORLD_UNAVAILABLE, null, c.operationId(), "World or manager unavailable")); return; }
        if (previous != null && c.revision() < previous.context().revision()) {
            finish(job, result(STALE_REVISION, previous.snapshot(), c.operationId(), "Revision is older than confirmed state")); return;
        }
        if (previous != null && previous.action() == ManagedAction.DELETE) {
            if (job.action == ManagedAction.DELETE) persistReplay(job, previous, fingerprint);
            else finish(job, result(TOMBSTONED, previous.snapshot(), c.operationId(), "Instance is terminally deleted"));
            return;
        }
        if (previous != null && c.revision() == previous.context().revision()) {
            boolean same = job.action != ManagedAction.DELETE && Objects.equals(previous.state(), job.state);
            if (same) persistReplay(job, previous, fingerprint);
            else finish(job, result(PAYLOAD_CONFLICT, previous.snapshot(), c.operationId(), "Revision already applied"));
            return;
        }
        if (job.action == ManagedAction.APPLY && previous == null) {
            finish(job, result(NOT_FOUND, null, c.operationId(), "Ensure the instance before updating")); return;
        }
        if (previous == null && job.action == ManagedAction.DELETE && regions.lookup(id) != null) {
            finish(job, result(DRIFT_DETECTED, null, c.operationId(), "Unknown projections are never deleted")); return;
        }
        UUID occupied = external.get(externalKey(id));
        boolean reserved = pending.values().stream().anyMatch(e -> externalKey(e.context().identity()).equals(externalKey(id))
                && !e.context().identity().instanceId().equals(id.instanceId()));
        if (job.action != ManagedAction.DELETE && (occupied != null && !occupied.equals(id.instanceId()) || reserved)) {
            finish(job, result(INSTANCE_CONFLICT, null, c.operationId(), "Another local instance owns the external identity")); return;
        }
        if (previous != null && (!regions.matches(id, previous.state()) || drift.contains(id.instanceId()))) {
            leases.remove(id.instanceId()); drift.add(id.instanceId());
            finish(job, result(DRIFT_DETECTED, previous.snapshot(), c.operationId(), "Projection differs; explicit reconciliation required")); return;
        }
        if (!authorized(job)) { finish(job, result(CONTROLLER_DENIED, null, c.operationId(), "Controller rejected the operation")); return; }
        if (job.state != null) regions.validate(id, job.state);
        var event = new ManagedClaimPreChangeEvent(c, job.action, previous == null ? null : previous.snapshot(), job.state);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) { finish(job, result(CANCELLED, null, c.operationId(), "Pre-change event cancelled")); return; }
        if (!authorized(job)) { finish(job, result(CONTROLLER_DENIED, null, c.operationId(), "Controller no longer authorizes the operation")); return; }
        leases.remove(id.instanceId());
        var entry = new ManagedJournal.Entry(c, job.action, job.state, fingerprint, false);
        pending.put(id.instanceId(), entry);
        try {
            if (job.action != ManagedAction.DELETE) {
                regions.install(id, job.state);
                if (previous != null) regions.reservePrevious(id, previous.state());
            }
        } catch (Exception error) {
            pending.remove(id.instanceId());
            try { regions.restore(id, previous == null ? null : previous.state()); }
            catch (Exception restoreError) { drift.add(id.instanceId()); }
            throw error;
        }
        io.execute(() -> {
            try {
                journal.save(entry);
                onMain(() -> {
                    receipts.put(c.operationId(), entry);
                    commit(job, entry, false);
                });
            } catch (Exception error) {
                onMain(() -> uncertain(job, entry, previous, error));
            }
        });
    }

    private void commit(Job job, ManagedJournal.Entry entry, boolean recovery) {
        var identity = entry.context().identity();
        if (!regions.isAvailable(identity.worldId())) {
            if (job != null) finish(job, result(UNCERTAIN, entry.snapshot(), entry.context().operationId(), "World became unavailable before confirmation"));
            else { recovering = false; pump(); }
            return;
        }
        io.execute(() -> {
            try {
                var committed = entry.committed();
                journal.save(committed);
                onMain(() -> {
                    try {
                        if (!regions.isAvailable(identity.worldId())) throw new IllegalStateException("World became unavailable during confirmation");
                        if (entry.action() == ManagedAction.DELETE) regions.remove(identity);
                        else if (!regions.matches(identity, entry.state())) throw new IllegalStateException("Projection changed during confirmation");
                        regions.releaseReservation(identity);
                    } catch (Exception error) {
                        lastError = error.toString();
                        if (job != null) finish(job, result(UNCERTAIN, entry.snapshot(), entry.context().operationId(), lastError));
                        else { recovering = false; pump(); }
                        return;
                    }
                    publish(committed);
                    var outcome = result(entry.action() == ManagedAction.DELETE ? DELETED : APPLIED,
                            committed.snapshot(), entry.context().operationId(), "Durable state confirmed");
                    Bukkit.getPluginManager().callEvent(new ManagedClaimPersistedEvent(entry.context(), entry.action(), outcome, recovery));
                    if (job != null) finish(job, outcome);
                    else { recovering = false; recoverNext(); pump(); }
                });
            } catch (Exception error) {
                onMain(() -> {
                    fail("Managed confirmation failed", error);
                    ready = true;
                    if (job != null) finish(job, result(UNCERTAIN, entry.snapshot(), entry.context().operationId(), lastError));
                    else { recovering = false; pump(); }
                });
            }
        });
    }

    private void uncertain(Job job, ManagedJournal.Entry entry, ManagedJournal.Entry previous, Exception error) {
        fail("Managed storage outcome is uncertain", error);
        ready = true;
        receipts.put(entry.context().operationId(), entry);
        try {
            regions.restore(entry.context().identity(), previous == null ? null : previous.state());
            regions.releaseReservation(entry.context().identity());
        }
        catch (Exception restoreError) { drift.add(entry.context().identity().instanceId()); }
        finish(job, result(UNCERTAIN, entry.snapshot(), entry.context().operationId(), lastError));
    }

    private void persistReplay(Job job, ManagedJournal.Entry previous, String fingerprint) {
        var receipt = new ManagedJournal.Entry(job.context, job.action, job.state, fingerprint, true);
        io.execute(() -> {
            try {
                journal.save(receipt);
                onMain(() -> {
                    receipts.put(job.context.operationId(), receipt);
                    if (job.context.revision() > previous.context().revision())
                        current.put(job.context.identity().instanceId(), receipt);
                    finish(job, result(REPLAYED, receipt.snapshot(), job.context.operationId(), "Equivalent state; durable replay receipt"));
                });
            } catch (Exception error) {
                onMain(() -> finish(job, result(UNCERTAIN, previous.snapshot(), job.context.operationId(), error.toString())));
            }
        });
    }

    private void publish(ManagedJournal.Entry entry) {
        var id = entry.context().identity();
        current.put(id.instanceId(), entry);
        receipts.put(entry.context().operationId(), entry);
        pending.remove(id.instanceId());
        drift.remove(id.instanceId());
        installed.add(id.instanceId());
        unprotected.remove(id.instanceId());
        leases.remove(id.instanceId());
        if (entry.action() == ManagedAction.DELETE) external.remove(externalKey(id), id.instanceId());
        else external.put(externalKey(id), id.instanceId());
    }

    private boolean authorized(Job job) {
        var registration = controllers.get(job.context.identity().controllerId());
        if (registration == null || !registration.usable()) return false;
        try { return registration.controller.authorize(job.context, job.action, job.state); }
        catch (RuntimeException error) { lastError = "Controller authorization failed: " + error; return false; }
    }

    private void failOperation(Job job, Exception error) {
        var status = error instanceof ClaimOperationException operation ? switch (operation.getReason()) {
            case WORLD_UNAVAILABLE -> WORLD_UNAVAILABLE;
            case INVALID_BOUNDS -> INVALID_BOUNDS;
            case CONFLICT, HAS_CHILDREN -> CONFLICT;
            case NOT_ENOUGH_POINTS -> BUDGET_EXCEEDED;
            case CANCELLED -> CANCELLED;
            default -> PAYLOAD_CONFLICT;
        } : PAYLOAD_CONFLICT;
        finish(job, result(status, null, job.context.operationId(), String.valueOf(error.getMessage())));
    }

    private void finish(Job job, ManagedResult result) {
        job.future.complete(result);
        if (running == job) running = null;
        pump();
    }

    @Override public CompletionStage<ManagedResult> lookupManagedClaim(ManagedClaimIdentity identity) {
        Objects.requireNonNull(identity);
        return call(() -> lookup(identity), result(PROVIDER_UNAVAILABLE, null, null, "Provider is unavailable"));
    }
    private ManagedResult lookup(ManagedClaimIdentity id) {
        if (!ready) return result(PROVIDER_UNAVAILABLE, null, null, lastError);
        if (!local(id)) return result(WRONG_SERVER, null, null, "Request targets another server");
        if (!regions.isAvailable(id.worldId())) return result(WORLD_UNAVAILABLE, null, null, "World or manager unavailable");
        var unfinished = pending.get(id.instanceId());
        if (unfinished != null) return result(UNCERTAIN, unfinished.snapshot(), unfinished.context().operationId(), "Recovery pending");
        var entry = current.get(id.instanceId());
        if (entry == null) {
            return regions.lookup(id) == null ? result(NOT_FOUND, null, null, "No durable instance")
                    : result(DRIFT_DETECTED, null, null, "Unknown projection exists");
        }
        if (!entry.context().identity().equals(id)) return result(INSTANCE_CONFLICT, null, null, "Identity binding differs");
        boolean matching = entry.action() == ManagedAction.DELETE ? regions.lookup(id) == null : regions.matches(id, entry.state());
        if (!matching) {
            drift.add(id.instanceId()); leases.remove(id.instanceId());
            return result(DRIFT_DETECTED, entry.snapshot(), entry.context().operationId(), "Projection differs from durable state");
        }
        return result(entry.action() == ManagedAction.DELETE ? NOT_FOUND : FOUND, entry.snapshot(), entry.context().operationId(), "Confirmed durable identity");
    }
    @Override public CompletionStage<ManagedResult> getOperationResult(UUID operationId) {
        Objects.requireNonNull(operationId);
        return call(() -> {
            if (!ready) return result(PROVIDER_UNAVAILABLE, null, operationId, lastError);
            var entry = receipts.get(operationId);
            if (entry != null) return result(entry.confirmed() ? entry.action() == ManagedAction.DELETE ? DELETED : APPLIED : UNCERTAIN,
                    entry.snapshot(), operationId, "Durable receipt");
            if (running != null && running.context.operationId().equals(operationId)
                    || queue.stream().anyMatch(j -> j.context.operationId().equals(operationId)))
                return result(PENDING, null, operationId, "Operation is still executing");
            return result(NOT_FOUND, null, operationId, "No receipt; no success is implied");
        }, result(PROVIDER_UNAVAILABLE, null, operationId, "Provider is unavailable"));
    }
    @Override public CompletionStage<ManagedClaimPage> listManagedClaims(String controllerId, int offset, int limit) {
        Objects.requireNonNull(controllerId);
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Offset >= 0, limit 1..100 required");
        return call(() -> {
            if (!ready) return new ManagedClaimPage(PROVIDER_UNAVAILABLE, List.of(), -1);
            var entries = current.values().stream().filter(e -> e.context().identity().controllerId().equals(controllerId))
                    .sorted(Comparator.comparing(e -> e.context().identity().instanceId())).toList();
            var page = entries.stream().skip(offset).limit(limit).map(ManagedJournal.Entry::snapshot).toList();
            return new ManagedClaimPage(FOUND, page, (long) offset + page.size() < entries.size() ? offset + page.size() : -1);
        }, new ManagedClaimPage(PROVIDER_UNAVAILABLE, List.of(), -1));
    }
    @Override public CompletionStage<ManagedDiagnostics> getDiagnostics() {
        return call(() -> {
            Map<String, Boolean> worlds = new TreeMap<>();
            for (var entry : current.values()) {
                var world = entry.context().identity().worldId();
                worlds.put(world.toString(), regions.isAvailable(world));
            }
            int trusted = (int) leases.values().stream().filter(l -> System.nanoTime() - l.expiresAt() < 0).count();
            int tombstones = (int) current.values().stream().filter(e -> e.action() == ManagedAction.DELETE).count();
            return new ManagedDiagnostics(ready, queue.size() + (running == null ? 0 : 1), pending.size(), current.size() - tombstones,
                    tombstones, receipts.size(), controllers.size(), trusted, lastError, worlds);
        }, new ManagedDiagnostics(false, 0, 0, 0, 0, 0, 0, 0, "Provider is unavailable", Map.of()));
    }
    @Override public CompletionStage<ManagedResult> reconcileManagedClaim(ManagedClaimIdentity identity) {
        Objects.requireNonNull(identity);
        return call(() -> {
            if (!ready) return result(PROVIDER_UNAVAILABLE, null, null, lastError);
            if (!local(identity)) return result(WRONG_SERVER, null, null, "Wrong server");
            if (!regions.isAvailable(identity.worldId())) return result(WORLD_UNAVAILABLE, null, null, "World unavailable");
            if (pending.containsKey(identity.instanceId()) || running != null || recovering)
                return result(PENDING, null, null, "Wait for pending work");
            var entry = current.get(identity.instanceId());
            if (entry == null) return result(NOT_FOUND, null, null, "Unknown regions are never removed");
            if (!entry.context().identity().equals(identity)) return result(INSTANCE_CONFLICT, null, null, "Identity differs");
            leases.remove(identity.instanceId());
            try {
                restore(entry);
                drift.remove(identity.instanceId());
                var restored = lookup(identity);
                if (restored.status() == FOUND || restored.status() == NOT_FOUND)
                    Bukkit.getPluginManager().callEvent(new ManagedClaimPersistedEvent(entry.context(), entry.action(),
                            result(entry.action() == ManagedAction.DELETE ? DELETED : APPLIED, entry.snapshot(),
                                    entry.context().operationId(), "Durable projection reconciled"), true));
                return restored;
            }
            catch (Exception error) { return result(DRIFT_DETECTED, entry.snapshot(), null, error.getMessage()); }
        }, result(PROVIDER_UNAVAILABLE, null, null, "Provider unavailable"));
    }

    private void restore(ManagedJournal.Entry entry) {
        var id = entry.context().identity();
        try {
            regions.restore(id, entry.action() == ManagedAction.DELETE ? null : entry.state());
            installed.add(id.instanceId());
            unprotected.remove(id.instanceId());
        } catch (RuntimeException error) {
            unprotected.add(id.instanceId());
            throw error;
        }
    }
    private void recoverNext() {
        if (!ready || running != null || recovering || !active) return;
        for (var entry : pending.values()) {
            var id = entry.context().identity();
            if (!regions.isAvailable(id.worldId())) continue;
            recovering = true;
            leases.remove(id.instanceId());
            try {
                var previous = current.get(id.instanceId());
                if (entry.action() == ManagedAction.DELETE) {
                    if (previous != null && previous.action() != ManagedAction.DELETE && regions.lookup(id) == null)
                        restore(previous);
                } else {
                    regions.install(id, entry.state());
                    regions.reservePrevious(id, previous == null || previous.action() == ManagedAction.DELETE ? null : previous.state());
                }
                io.execute(() -> {
                    try {
                        journal.save(entry);
                        onMain(() -> commit(null, entry, true));
                    } catch (Exception error) {
                        onMain(() -> { lastError = "Recovery persistence failed: " + error; recovering = false; pump(); });
                    }
                });
                return;
            } catch (Exception error) {
                lastError = "Recovery projection failed: " + error;
                drift.add(id.instanceId()); recovering = false;
            }
        }
        pump();
    }
    private void maintenance() {
        if (!active) return;
        long now = System.nanoTime();
        leases.entrySet().removeIf(e -> now - e.getValue().expiresAt() >= 0);
        if (running != null && now - running.submitted >= timeoutNanos)
            running.future.complete(result(UNCERTAIN, null, running.context.operationId(), "Wait timed out; execution may still complete. Poll the receipt."));
        queue.removeIf(job -> {
            if (now - job.submitted < timeoutNanos) return false;
            job.future.complete(result(QUEUE_FULL, null, job.context.operationId(), "Queue wait expired before execution")); return true;
        });
        if (ready && ++maintenanceCounter % 5 == 0) {
            for (var entry : current.values()) {
                var id = entry.context().identity();
                if (entry.action() == ManagedAction.DELETE || pending.containsKey(id.instanceId())) continue;
                if (!regions.isAvailable(id.worldId())) {
                    installed.remove(id.instanceId()); leases.remove(id.instanceId());
                    if (Bukkit.getWorld(id.worldId()) != null) unprotected.add(id.instanceId());
                    else unprotected.remove(id.instanceId());
                    continue;
                }
                if (!installed.contains(id.instanceId())) {
                    try { restore(entry); } catch (Exception error) { drift.add(id.instanceId()); lastError = error.toString(); }
                } else if (!regions.matches(id, entry.state())) { drift.add(id.instanceId()); leases.remove(id.instanceId()); }
            }
            recoverNext();
            pump();
        }
    }
    @EventHandler(priority = EventPriority.HIGH) public void onWorldLoad(WorldLoadEvent event) {
        if (!ready) return;
        Map<UUID, ManagedJournal.Entry> targets = new HashMap<>(current);
        targets.putAll(pending);
        for (var entry : targets.values()) {
            if (entry.context().identity().worldId().equals(event.getWorld().getUID())) {
                var identity = entry.context().identity();
                leases.remove(identity.instanceId());
                installed.remove(identity.instanceId());
                try {
                    if (pending.containsKey(identity.instanceId())) {
                        var previous = current.get(identity.instanceId());
                        if (entry.action() == ManagedAction.DELETE) {
                            if (previous != null && previous.action() != ManagedAction.DELETE) restore(previous);
                        } else {
                            restore(entry);
                            regions.reservePrevious(identity, previous == null || previous.action() == ManagedAction.DELETE ? null : previous.state());
                        }
                    } else restore(entry);
                } catch (Exception error) { drift.add(identity.instanceId()); lastError = error.toString(); }
            }
        }
        recoverNext();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        for (var entry : current.values()) {
            var id = entry.context().identity();
            if (id.worldId().equals(event.getWorld().getUID())) {
                installed.remove(id.instanceId());
                unprotected.remove(id.instanceId());
                leases.remove(id.instanceId());
            }
        }
        for (var entry : pending.values()) {
            var id = entry.context().identity();
            if (id.worldId().equals(event.getWorld().getUID())) {
                installed.remove(id.instanceId());
                unprotected.remove(id.instanceId());
                leases.remove(id.instanceId());
            }
        }
    }
    @EventHandler public void onControllerDisable(PluginDisableEvent event) {
        new ArrayList<>(controllers.values()).stream().filter(r -> r.owner == event.getPlugin()).forEach(Registration::close);
    }
    private <T> CompletionStage<T> call(Supplier<T> action, T unavailable) {
        CompletableFuture<T> future = track(new CompletableFuture<>());
        if (!dispatch(() -> {
            try { future.complete(action.get()); }
            catch (Exception error) { future.completeExceptionally(error); }
        })) future.complete(unavailable);
        return future;
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        waiting.add(future); future.whenComplete((value, error) -> waiting.remove(future)); return future;
    }
    private boolean dispatch(Runnable action) {
        if (!active) return false;
        if (scheduled.incrementAndGet() > capacity) { scheduled.decrementAndGet(); return false; }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                scheduled.decrementAndGet();
                if (active) action.run();
            });
            return true;
        } catch (RuntimeException error) { scheduled.decrementAndGet(); return false; }
    }
    private void onMain(Runnable action) {
        if (!active) return;
        try { Bukkit.getScheduler().runTask(plugin, () -> { if (active) action.run(); }); }
        catch (RuntimeException ignored) { }
    }
    private boolean local(ManagedClaimIdentity identity) { return serverId.equals(identity.serverId()); }
    private static String externalKey(ManagedClaimIdentity id) { return id.controllerId() + ":" + id.externalId(); }
    private static ManagedResult result(ManagedStatus status, ManagedClaimSnapshot snapshot, UUID operation, String message) {
        return new ManagedResult(status, snapshot, operation, message);
    }
    private void fail(String message, Exception error) {
        lastError = message + ": " + error;
        plugin.getLogger().severe(lastError);
        ready = false;
    }
    @Override public void close() {
        regions.lockForShutdown();
        active = false; ready = false;
        controllers.values().forEach(r -> r.open = false);
        controllers.clear(); leases.clear();
        for (var future : List.copyOf(waiting)) future.completeExceptionally(new IllegalStateException("Provider stopped; poll durable operation after restart"));
        io.execute(journal::close);
        io.shutdown();
    }
}
