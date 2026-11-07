package com.twilight.claims.api.event;

import com.twilight.claims.api.Claim;
import com.twilight.claims.api.ClaimChangeType;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ClaimPreChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ClaimChangeType type;
    private final Claim before;
    private final Map<String, Object> changes;
    private boolean cancelled;

    public ClaimPreChangeEvent(ClaimChangeType type, Claim before, Map<String, Object> changes) {
        this.type = Objects.requireNonNull(type, "type");
        this.before = before;
        this.changes = Map.copyOf(changes);
    }

    public ClaimChangeType getChangeType() { return type; }
    public Optional<Claim> getBefore() { return Optional.ofNullable(before); }
    public Map<String, Object> getChanges() { return changes; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
