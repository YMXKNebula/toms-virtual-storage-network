package dev.tpc.virtual;

import java.util.*;
import java.util.function.Consumer;

/** Notifications follow committed coordinator changes; listeners cannot own inventory mutations. */
public final class VirtualEvents {
    public record Id(UUID transaction,long sequence) {}
    public sealed interface Event permits BarrelRegistered,BarrelRemoved,GroupChanged,InventoryCommitted,WarehouseOnline,OverflowChanged {Id id();}
    public record BarrelRegistered(Id id,long barrel,long group) implements Event {}
    public record BarrelRemoved(Id id,long barrel,long group) implements Event {}
    public record GroupChanged(Id id,Set<Long> barrels) implements Event {public GroupChanged{barrels=Set.copyOf(barrels);}}
    public record InventoryCommitted(Id id,Set<Long> barrels) implements Event {public InventoryCommitted{barrels=Set.copyOf(barrels);}}
    public record WarehouseOnline(Id id,long barrel,boolean online) implements Event {}
    public record OverflowChanged(Id id,UUID job,boolean delivered) implements Event {}
    private static final class Listener {final Consumer<Event> action;long last=-1;Listener(Consumer<Event> action){this.action=action;}}
    private final Thread owner=Thread.currentThread();
    private final List<Listener> listeners=new ArrayList<>();
    private final ArrayDeque<Event> pending=new ArrayDeque<>();
    private boolean publishing;
    public AutoCloseable subscribe(Consumer<Event> listener){var l=new Listener(listener);listeners.add(l);return ()->listeners.remove(l);}
    public void publish(Event event){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Events off server thread");
        pending.add(event);if(publishing)return;publishing=true;
        try{while(!pending.isEmpty()){var e=pending.remove();for(var l:List.copyOf(listeners))if(e.id().sequence()>l.last){l.action.accept(e);l.last=e.id().sequence();}}}
        finally{publishing=false;}
    }
}
