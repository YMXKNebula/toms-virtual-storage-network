package dev.tpc.virtual;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/** Sparse, world-independent authority. A position is a capacity node, never a slot array. */
public final class WarehouseLedger<V> {
    public static final int POSITIONS=64;
    public static final class Node<V> {
        public final long position;
        public final UUID incarnation;
        public long group;
        private boolean online;
        private long occupied;
        private final Map<V,Long> contents=new LinkedHashMap<>();
        private PersistentCounts<V> snapshot=new PersistentCounts<>();
        private Node(long position,UUID incarnation,long group){this.position=position;this.incarnation=incarnation;this.group=group;}
        public Map<V,Long> contents(){return Collections.unmodifiableMap(contents);}
        public boolean online(){return online;}
        public long amount(V variant){return contents.getOrDefault(variant,0L);}
        public PersistentCounts<V> snapshot(){return snapshot;}
    }
    private final Thread owner=Thread.currentThread();
    private final Map<Long,Node<V>> nodes=new LinkedHashMap<>();
    private final Map<Long,Set<Scope>> observers=new HashMap<>();
    private final ToLongFunction<V> stackLimit;
    private final LongSupplier ruleEpoch;
    private final Map<V,Rule> rules=new LinkedHashMap<>();
    private long checkedEpoch=Long.MIN_VALUE;
    private final class Rule {
        long limit;final Set<Long> positions=new HashSet<>();
        Rule(V variant){limit=limitNow(variant);}
    }
    public WarehouseLedger(){this(v->64,null);}
    public WarehouseLedger(ToLongFunction<V> stackLimit){this(stackLimit,null);}
    public WarehouseLedger(ToLongFunction<V> stackLimit,LongSupplier ruleEpoch){this.stackLimit=Objects.requireNonNull(stackLimit);this.ruleEpoch=ruleEpoch;}
    private long limitNow(V variant){return Math.max(1,stackLimit.applyAsLong(variant));}
    private long slots(long quantity,long limit){return quantity==0?0:1+(quantity-1)/limit;}
    private long freeSlots(long occupied){return Math.max(0,POSITIONS-occupied);}
    // Check each stored variant once per active server tick, never every barrel on a read.
    // A runtime limit change updates only nodes which actually contain that variant.
    private void refreshRules(){
        checkThread();if(ruleEpoch!=null){long epoch=ruleEpoch.getAsLong();if(epoch==checkedEpoch)return;checkedEpoch=epoch;}
        for(var entry:rules.entrySet()){
            V variant=entry.getKey();var rule=entry.getValue();long limit=limitNow(variant),old=rule.limit;if(limit==old)continue;
            rule.limit=limit;
            for(long p:rule.positions){var node=nodes.get(p);long quantity=node.amount(variant),delta=slots(quantity,limit)-slots(quantity,old);node.occupied+=delta;
                for(var scope:observers.getOrDefault(p,Set.of())){scope.used+=delta;scope.available+=freeSlots(node.occupied)-freeSlots(node.occupied-delta);scope.partial(node,variant,quantity,limit);scope.free(node);}
            }
        }
    }
    public long usedSlots(long position){refreshRules();return Objects.requireNonNull(nodes.get(position)).occupied;}
    private long mutations;
    private long candidatesVisited;
    public void checkThread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Ledger accessed off owner thread");}
    public Node<V> get(long position){checkThread();return nodes.get(position);}
    public Collection<Node<V>> nodes(){checkThread();return Collections.unmodifiableCollection(nodes.values());}
    public int nodeCount(){return nodes.size();}
    public long mutations(){return mutations;}
    public long candidatesVisited(){return candidatesVisited;}

    public Node<V> register(long pos,UUID incarnation,long group,Map<V,Long> contents){
        checkThread();if(nodes.containsKey(pos))throw new IllegalStateException("Capacity already registered");
        if(contents.size()>POSITIONS || contents.values().stream().anyMatch(n->n==null || n<=0))throw new IllegalArgumentException("Invalid capacity allocation");
        var node=new Node<V>(pos,incarnation,group);node.contents.putAll(contents);for(var e:contents.entrySet()){
            node.snapshot=node.snapshot.with(e.getKey(),e.getValue());var rule=rules.computeIfAbsent(e.getKey(),Rule::new);rule.positions.add(pos);node.occupied+=slots(e.getValue(),rule.limit);
        }nodes.put(pos,node);mutations++;return node;
    }
    public void online(long pos,boolean online){
        checkThread();var n=nodes.get(pos);if(n==null || n.online==online)return;
        boolean before=n.online;n.online=online;
        for(var scope:List.copyOf(observers.getOrDefault(pos,Set.of())))scope.moveOnline(n,before);
    }
    public Node<V> remove(long pos){
        checkThread();var n=nodes.remove(pos);if(n==null)return null;
        for(var scope:List.copyOf(observers.getOrDefault(pos,Set.of())))scope.close();
        for(V variant:n.contents.keySet()){var rule=rules.get(variant);rule.positions.remove(pos);if(rule.positions.isEmpty())rules.remove(variant);}
        mutations++;return n;
    }
    public void set(long pos,V variant,long quantity){
        set(pos,variant,quantity,false);
    }
    private void set(long pos,V variant,long quantity,boolean restore){
        checkThread();if(quantity<0)throw new IllegalArgumentException("Negative quantity");
        var n=Objects.requireNonNull(nodes.get(pos),"Unknown barrel");long previous=n.amount(variant);
        if(previous==quantity)return;
        var rule=rules.computeIfAbsent(variant,Rule::new);long delta=slots(quantity,rule.limit)-slots(previous,rule.limit);
        if(!restore && delta>0 && n.occupied+delta>POSITIONS)throw new IllegalStateException("Barrel slot capacity exceeded");
        n.occupied+=delta;
        for(var scope:observers.getOrDefault(pos,Set.of())){scope.available+=freeSlots(n.occupied)-freeSlots(n.occupied-delta);scope.change(n,variant,previous,quantity);}
        if(quantity==0)n.contents.remove(variant);else n.contents.put(variant,quantity);
        if(quantity==0){rule.positions.remove(pos);if(rule.positions.isEmpty())rules.remove(variant);}else rule.positions.add(pos);
        n.snapshot=n.snapshot.with(variant,quantity);
        for(var scope:observers.getOrDefault(pos,Set.of()))scope.free(n);
        mutations++;
    }
    public void restore(long pos,Map<V,Long> contents){
        checkThread();var n=Objects.requireNonNull(nodes.get(pos));
        // Remove obsolete variants first, then update only differences; a probe rollback
        // must not rebuild all 64 unchanged allocations.
        for(var v:List.copyOf(n.contents.keySet()))if(n.amount(v)>contents.getOrDefault(v,0L))set(pos,v,contents.getOrDefault(v,0L));
        contents.forEach((v,q)->set(pos,v,q,true));
    }
    public Scope scope(Collection<Long> positions){refreshRules();return new Scope(positions);}

    public final class Scope implements AutoCloseable {
        private final Set<Long> membership;
        private final Map<V,Locations> locations=new LinkedHashMap<>();
        private final Map<V,Long> totals=new LinkedHashMap<>();
        private PersistentCounts<V> snapshot=new PersistentCounts<>();
        private final NavigableSet<Long> freeOnline=new TreeSet<>(),freeOffline=new TreeSet<>();
        private long used;
        private long available;
        private boolean closed;
        private Scope(Collection<Long> positions){
            membership=Set.copyOf(positions);
            for(long p:membership){var n=Objects.requireNonNull(nodes.get(p),"Unregistered capacity");add(n);observers.computeIfAbsent(p,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(this);}
        }
        public boolean live(){return !closed;}
        public Set<Long> membership(){return membership;}
        public Map<V,Long> totals(){checkThread();return Collections.unmodifiableMap(totals);}
        public PersistentCounts<V> snapshot(){checkThread();return snapshot;}
        public long amount(V variant){checkThread();return totals.getOrDefault(variant,0L);}
        public long capacity(){return (long)membership.size()*POSITIONS;}
        public long used(){refreshRules();return used;}
        public long free(){refreshRules();return available;}
        public boolean contains(long position){return membership.contains(position);}
        private void add(Node<V> n){for(var e:n.contents.entrySet())change(n,e.getKey(),0,e.getValue());available+=freeSlots(n.occupied);free(n);}
        private void remove(Node<V> n){for(var e:n.contents.entrySet())change(n,e.getKey(),e.getValue(),0);available-=freeSlots(n.occupied);freeOnline.remove(n.position);freeOffline.remove(n.position);}
        private void change(Node<V> n,V variant,long old,long quantity){
            long total=Math.addExact(totals.getOrDefault(variant,0L),quantity-old);
            if(total==0)totals.remove(variant);else totals.put(variant,total);
            snapshot=snapshot.with(variant,total);
            var l=locations.computeIfAbsent(variant,k->new Locations());
            if(old==0 && quantity>0)(n.online?l.online:l.offline).add(n.position);
            if(old>0 && quantity==0)(n.online?l.online:l.offline).remove(n.position);
            long limit=rules.get(variant).limit;used+=slots(quantity,limit)-slots(old,limit);
            partial(n,variant,quantity,limit);
            if(l.online.isEmpty() && l.offline.isEmpty())locations.remove(variant);
        }
        private void partial(Node<V> n,V variant,long quantity,long limit){
            var l=locations.get(variant);if(l==null)return;var partial=n.online?l.partialOnline:l.partialOffline;
            if(quantity>0 && quantity%limit!=0)partial.add(n.position);else partial.remove(n.position);
        }
        private void free(Node<V> n){
            boolean on=n.occupied<POSITIONS && n.online,off=n.occupied<POSITIONS && !n.online;
            if(on)freeOnline.add(n.position);else freeOnline.remove(n.position);
            if(off)freeOffline.add(n.position);else freeOffline.remove(n.position);
        }
        private void moveOnline(Node<V> n,boolean before){
            for(var e:n.contents.entrySet()){
                var l=locations.get(e.getKey());(before?l.online:l.offline).remove(n.position);(n.online?l.online:l.offline).add(n.position);
                (before?l.partialOnline:l.partialOffline).remove(n.position);if(e.getValue()%rules.get(e.getKey()).limit!=0)(n.online?l.partialOnline:l.partialOffline).add(n.position);
            }
            free(n);
        }
        public long insert(V variant,long maximum,Consumer<Node<V>> before){
            refreshRules();if(closed || maximum<0)throw new IllegalArgumentException("Invalid insert");
            long limit=rules.containsKey(variant)?rules.get(variant).limit:limitNow(variant);
            long inserted=0;
            for(boolean online:new boolean[]{true,false}){
                var l=locations.get(variant);
                if(l!=null)inserted+=fill(online?l.partialOnline:l.partialOffline,variant,maximum-inserted,limit,before);
                if(inserted==maximum)break;
                inserted+=fillNew(online,variant,maximum-inserted,limit,before);
                if(inserted==maximum)break;
            }
            return inserted;
        }
        private long fill(NavigableSet<Long> candidates,V variant,long maximum,long limit,Consumer<Node<V>> before){
            long inserted=0;Long position=candidates.isEmpty()?null:candidates.first();
            while(position!=null && inserted<maximum){
                long p=position;position=candidates.higher(p);var n=nodes.get(p);long old=n.amount(variant);
                candidatesVisited++;
                long amount=Math.min(maximum-inserted,old%limit==0?0:limit-old%limit);if(amount==0)continue;
                before.accept(n);set(p,variant,old+amount);inserted+=amount;
            }
            return inserted;
        }
        private long fillNew(boolean online,V variant,long maximum,long limit,Consumer<Node<V>> before){
            var candidates=online?freeOnline:freeOffline;if(candidates.isEmpty())return 0;
            Long position=candidates.first();long inserted=0;
            while(position!=null && inserted<maximum){
                long p=position;position=candidates.higher(p);candidatesVisited++;
                var n=nodes.get(p);long old=n.amount(variant);
                long room=Math.multiplyExact(Math.max(0,POSITIONS-n.occupied),limit)+(old%limit==0?0:limit-old%limit);
                long amount=Math.min(maximum-inserted,room);if(amount==0)continue;
                before.accept(n);set(p,variant,old+amount);inserted+=amount;
            }
            return inserted;
        }
        public long extract(V variant,long maximum,Consumer<Node<V>> before){
            refreshRules();if(closed || maximum<0)throw new IllegalArgumentException("Invalid extraction");
            var l=locations.get(variant);if(l==null)return 0;long extracted=0;
            for(var candidates:List.of(l.online,l.offline)){
                Long position=candidates.isEmpty()?null:candidates.first();
                while(position!=null && extracted<maximum){
                    long p=position;position=candidates.higher(p);var n=nodes.get(p);long old=n.amount(variant),amount=Math.min(maximum-extracted,old);
                    if(amount>0){before.accept(n);set(p,variant,old-amount);extracted+=amount;}
                }
            }
            return extracted;
        }
        @Override public void close(){
            checkThread();if(closed)return;closed=true;
            for(long p:membership){var set=observers.get(p);if(set!=null){set.remove(this);if(set.isEmpty())observers.remove(p);}}
        }
    }
    private static final class Locations {
        final NavigableSet<Long> online=new TreeSet<>(),offline=new TreeSet<>(),partialOnline=new TreeSet<>(),partialOffline=new TreeSet<>();
    }
}
