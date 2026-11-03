package dev.tpc.virtual;

import dev.tpc.core.Point;
import java.util.*;
import java.util.function.BiConsumer;

/** Warehouse adjacency only; cable reachability has exactly one authority, the existing Topology. */
public final class WarehouseGroups {
    private static final class Group {final long id;final Set<Point> members=new HashSet<>();long revision;Group(long id){this.id=id;}}
    private final Map<Point,Group> membership=new HashMap<>();
    private final Map<Long,Group> groups=new HashMap<>();
    private final LinkedHashSet<Group> dirty=new LinkedHashSet<>();
    private final BiConsumer<Point,Long> changed;
    private long nextId=1;
    private Split split;
    public WarehouseGroups(BiConsumer<Point,Long> changed){this.changed=changed;}
    public void restore(Point p,long group){var g=groups.computeIfAbsent(group,Group::new);g.members.add(p);membership.put(p,g);nextId=Math.max(nextId,group+1);dirty.add(g);}
    public long register(Point p){
        var old=membership.get(p);if(old!=null)return old.id;
        var neighbors=new HashSet<Group>();for(int d=0;d<6;d++){var g=membership.get(p.step(d));if(g!=null)neighbors.add(g);}
        Group target=neighbors.stream().max(Comparator.comparingInt(g->g.members.size())).orElseGet(()->{var g=new Group(nextId++);groups.put(g.id,g);return g;});
        for(var g:neighbors)if(g!=target){
            for(var member:g.members){target.members.add(member);membership.put(member,target);changed.accept(member,target.id);}
            groups.remove(g.id);dirty.remove(g);g.revision++;
        }
        target.members.add(p);membership.put(p,target);target.revision++;changed.accept(p,target.id);dirty.add(target);return target.id;
    }
    public void remove(Point p){var g=membership.remove(p);if(g==null)return;g.members.remove(p);g.revision++;if(g.members.isEmpty()){groups.remove(g.id);dirty.remove(g);}else dirty.add(g);}
    public long group(Point p){var g=membership.get(p);return g==null?0:g.id;}
    public int groupCount(){return groups.size();}
    public int pending(){return dirty.size()+(split==null?0:1);}
    public void tick(int budget){
        while(budget-->0){
            if(split!=null && (split.revision!=split.original.revision || !groups.containsKey(split.original.id))){split=null;}
            if(split==null){if(dirty.isEmpty())return;var it=dirty.iterator();var g=it.next();it.remove();if(!groups.containsKey(g.id))continue;split=new Split(g);}
            if(split.step()){split.finish();split=null;}
        }
    }
    private final class Split {
        final Group original;final long revision;
        final Set<Point> remaining;
        final ArrayDeque<Point> frontier=new ArrayDeque<>();
        final List<Set<Point>> components=new ArrayList<>();
        Set<Point> current;
        Split(Group group){original=group;revision=group.revision;remaining=new HashSet<>(group.members);}
        boolean step(){
            if(frontier.isEmpty()){
                if(remaining.isEmpty())return true;
                Point start=remaining.iterator().next();remaining.remove(start);frontier.add(start);current=new HashSet<>();components.add(current);
            }
            Point p=frontier.remove();current.add(p);
            for(int d=0;d<6;d++){Point q=p.step(d);if(remaining.remove(q))frontier.add(q);}
            return remaining.isEmpty() && frontier.isEmpty();
        }
        void finish(){
            if(components.size()<2)return;
            var largest=components.stream().max(Comparator.comparingInt(Set::size)).orElseThrow();original.members.clear();original.members.addAll(largest);
            for(var component:components)if(component!=largest){var g=new Group(nextId++);groups.put(g.id,g);g.members.addAll(component);for(var p:component){membership.put(p,g);changed.accept(p,g.id);}}
        }
    }
}
