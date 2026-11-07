package com.twilight.claims.api.managed.event;

import com.twilight.claims.api.managed.*;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class ManagedClaimPersistedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ManagedOperationContext context;
    private final ManagedAction action;
    private final ManagedResult result;
    private final boolean recovery;

    public ManagedClaimPersistedEvent(ManagedOperationContext context, ManagedAction action,
                                     ManagedResult result, boolean recovery) {
        this.context = context;
        this.action = action;
        this.result = result;
        this.recovery = recovery;
    }

    public ManagedOperationContext getContext() { return context; }
    public ManagedAction getAction() { return action; }
    public ManagedResult getResult() { return result; }
    public boolean isRecovery() { return recovery; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
