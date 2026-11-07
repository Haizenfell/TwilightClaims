package com.twilight.claims.api.managed.event;

import com.twilight.claims.api.managed.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class ManagedClaimPreChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ManagedOperationContext context;
    private final ManagedAction action;
    private final ManagedClaimSnapshot before;
    private final ManagedClaimState desiredState;
    private boolean cancelled;

    public ManagedClaimPreChangeEvent(ManagedOperationContext context, ManagedAction action,
                                      ManagedClaimSnapshot before, ManagedClaimState desiredState) {
        this.context = context;
        this.action = action;
        this.before = before;
        this.desiredState = desiredState;
    }

    public ManagedOperationContext getContext() { return context; }
    public ManagedAction getAction() { return action; }
    public ManagedClaimSnapshot getBefore() { return before; }
    public ManagedClaimState getDesiredState() { return desiredState; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
