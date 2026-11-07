package com.twilight.claims.api.event;

import com.twilight.claims.api.Claim;
import com.twilight.claims.api.ClaimChangeType;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.Optional;

public final class ClaimChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ClaimChangeType type;
    private final Claim before;
    private final Claim after;

    public ClaimChangeEvent(ClaimChangeType type, Claim before, Claim after) {
        this.type = Objects.requireNonNull(type, "type");
        this.before = before;
        this.after = after;
    }

    public ClaimChangeType getChangeType() { return type; }
    public Optional<Claim> getBefore() { return Optional.ofNullable(before); }
    public Optional<Claim> getAfter() { return Optional.ofNullable(after); }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
