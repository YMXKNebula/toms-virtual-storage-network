package dev.tpc.core;

import java.util.*;
import java.util.function.Consumer;

/** Dimension-local directed compressed graph. All mutable methods belong to one owner thread. */
public final class Topology {
    public static final class Lease {
        private boolean valid = true, ready;
        private Set<Long> visited = new HashSet<>();
        private Set<Point> functions = new LinkedHashSet<>();
        private ArrayDeque<Long> queue = new ArrayDeque<>();
        private final long version;
        private Lease(long root, long version) { this.version = version; queue.add(root); }
        public boolean valid() { return valid && ready; }
        public Set<Point> functions() { return valid() ? Collections.unmodifiableSet(functions) : Set.of(); }
    }
    public record Edit(List<Long> removed, List<CableRun> added, long version) {
        public Edit { removed = List.copyOf(removed); added = List.copyOf(added); }
    }
    private final Thread owner = Thread.currentThread();
    private final SpatialIndex index = new SpatialIndex();
    private final ChunkIndex chunks = new ChunkIndex();
    private final Map<Integer,NavigableSet<Integer>> validatingX = new HashMap<>(), validatingZ = new HashMap<>();
    private final Map<Long, CableRun> runs = new HashMap<>();
    private final Map<Long, Set<Long>> edges = new HashMap<>(), incoming = new HashMap<>();
    private final Map<Point, Set<Long>> targetSources = new HashMap<>();
    private final Map<Long, Set<Lease>> watchers = new HashMap<>();
    private final LinkedHashMap<Long, Lease> queries = new LinkedHashMap<>(16, .75f, true);
    private final Set<Long> suspended = new HashSet<>();
    private final int maxQueries;
    private final ArrayDeque<Lease> jobs = new ArrayDeque<>();
    private final ArrayDeque<Lease> cleanup = new ArrayDeque<>();
    private long nextId = 1, version, cableCount, searchSteps, edits;
    private Consumer<Edit> listener = e -> {};

    public Topology(int maxQueries) {
        if (maxQueries < 1) throw new IllegalArgumentException("maxQueries");
        this.maxQueries = maxQueries;
    }
    public void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Topology accessed off owner thread"); }
    public void onEdit(Consumer<Edit> listener) { checkThread(); this.listener = listener; }
    public long version() { return version; }
    public long cableCount() { return cableCount; }
    public int runCount() { return runs.size(); }
    public long searchSteps() { return searchSteps; }
    public long editCount() { return edits; }
    public int pendingQueries() { return jobs.size(); }
    public int queryCount() { return queries.size(); }
    public CableRun find(Point point) { checkThread(); return index.find(point); }
    public Collection<CableRun> snapshot() { checkThread(); return List.copyOf(runs.values()); }
    public final class SnapshotJob {
        private final long capturedVersion=version;
        private final Iterator<CableRun> iterator=runs.values().iterator();
        private final List<CableRun> records=new ArrayList<>();
        private boolean cancelled,done;
        public boolean advance(int budget,long deadline) {
            checkThread();if(capturedVersion!=version){cancelled=true;return true;}
            while(budget-->0 && System.nanoTime()<deadline && iterator.hasNext())records.add(iterator.next());
            done=!iterator.hasNext();return done;
        }
        public boolean cancelled(){return cancelled;}
        public Edit result(){if(!done || cancelled)throw new IllegalStateException("Snapshot incomplete");return new Edit(List.of(),records,capturedVersion);}
    }
    public SnapshotJob beginSnapshot(){checkThread();return new SnapshotJob();}
    public List<CableRun> runsInChunk(int x,int z) { checkThread(); return chunks.query(x,z); }
    public void beginChunkValidation(int x,int z) {
        checkThread(); validatingX.computeIfAbsent(z,k->new TreeSet<>()).add(x); validatingZ.computeIfAbsent(x,k->new TreeSet<>()).add(z);
        Set<Long> affected=new HashSet<>(); for(CableRun r:chunks.query(x,z))affected.add(r.id());invalidate(affected);
    }
    public void endChunkValidation(int x,int z) {
        checkThread(); var xs=validatingX.get(z);if(xs!=null){xs.remove(x);if(xs.isEmpty())validatingX.remove(z);}
        var zs=validatingZ.get(x);if(zs!=null){zs.remove(z);if(zs.isEmpty())validatingZ.remove(x);}
    }
    private boolean isValidating(CableRun r) {
        Point a=r.start(),b=r.finish();
        if(r.axis()==2){var zs=validatingZ.get(a.x()>>4);Integer z=zs==null?null:zs.ceiling(a.z()>>4);return z!=null && z<=b.z()>>4;}
        var xs=validatingX.get(a.z()>>4);Integer x=xs==null?null:xs.ceiling(a.x()>>4);return x!=null && x<=b.x()>>4;
    }

    /** Called synchronously before a deferred world observation; old leases become unusable now. */
    public void suspend(Point point) {
        checkThread();
        var r = index.find(point);
        Set<Long> affected = neighbors(point);
        if (r != null) { suspended.add(r.id()); affected.add(r.id()); }
        invalidate(affected);
    }
    public void invalidateAll() {
        checkThread();
        for (Lease l : new ArrayList<>(queries.values())) expire(l);
        queries.clear(); jobs.clear();
    }
    private Set<Long> neighbors(Point p) {
        Set<Long> result = new HashSet<>(targetSources.getOrDefault(p, Set.of()));
        for (int d = 0; d < 6; d++) { CableRun n = index.find(p.step(d)); if (n != null) result.add(n.id()); }
        return result;
    }
    public boolean observe(Point point, NodeSpec spec) {
        checkThread();
        CableRun old = index.find(point);
        if (old == null && spec == null) return false;
        if (old != null && old.spec().equals(spec) && !suspended.contains(old.id())) return false;
        Set<Long> affected = neighbors(point);
        List<Long> removed = new ArrayList<>();
        List<CableRun> added = new ArrayList<>();
        if (old != null) {
            affected.addAll(incoming.getOrDefault(old.id(), Set.of())); affected.add(old.id());
            remove(old, removed);
            int c = point.coordinate(old.axis());
            if (c > old.start().coordinate(old.axis())) added.add(create(old.start(), old.axis(), c - 1, old.spec()));
            if (c < old.end()) added.add(create(point.at(old.axis(), c + 1), old.axis(), old.end(), old.spec()));
        }
        if (spec != null) {
            int axis = Math.max(0, spec.compressionAxis());
            CableRun fresh = create(point, axis, point.coordinate(axis), spec);
            if (spec.compressionAxis() >= 0) {
                int neg = axis == 0 ? 4 : axis == 1 ? 0 : 2;
                // Added fragments must be searchable for local rejoining after an unchanged observation.
                for (CableRun r : added) put(r);
                added.clear();
                for (int d : new int[]{neg, neg + 1}) {
                    CableRun n = index.find(d == neg ? fresh.start().step(d) : fresh.finish().step(d));
                    if (n != null && n.axis() == axis && n.spec().equals(spec)) {
                        affected.add(n.id()); affected.addAll(incoming.getOrDefault(n.id(), Set.of()));
                        remove(n, removed);
                        fresh = create(d == neg ? n.start() : fresh.start(), axis, d == neg ? fresh.end() : n.end(), spec);
                    }
                }
            }
            added.add(fresh);
        }
        for (CableRun r : added) put(r);
        // Include fragments inserted during merging, and every previously incident source.
        for (CableRun r : localRuns(point, old)) affected.add(r.id());
        affected.addAll(neighbors(point));
        invalidate(affected);
        version++; edits++;
        for (long id : affected) if (runs.containsKey(id)) relink(id);
        // The persistence delta is computed from surviving local records, never individual cable positions.
        List<CableRun> surviving = new ArrayList<>();
        for (long id : affected) { CableRun r = runs.get(id); if (r != null) surviving.add(r); }
        listener.accept(new Edit(removed, surviving, version));
        return true;
    }
    private Collection<CableRun> localRuns(Point p, CableRun old) {
        Set<CableRun> found = new HashSet<>();
        CableRun r = index.find(p); if (r != null) found.add(r);
        for (int d = 0; d < 6; d++) { r = index.find(p.step(d)); if (r != null) found.add(r); }
        if (old != null) {
            r = index.find(old.start()); if (r != null) found.add(r);
            r = index.find(old.finish()); if (r != null) found.add(r);
        }
        return found;
    }
    private CableRun create(Point p, int axis, int end, NodeSpec spec) { return new CableRun(nextId++, p, axis, end, spec); }
    private void put(CableRun r) {
        index.add(r); chunks.add(r); runs.put(r.id(), r); cableCount += r.length();
        for (NodeSpec.Target t : targets(r)) targetSources.computeIfAbsent(t.point(), k -> new HashSet<>()).add(r.id());
    }
    private void remove(CableRun r, List<Long> removed) {
        invalidate(Set.of(r.id()));
        suspended.remove(r.id());
        for (NodeSpec.Target t : targets(r)) {
            Set<Long> sources = targetSources.get(t.point());
            if (sources != null) { sources.remove(r.id()); if (sources.isEmpty()) targetSources.remove(t.point()); }
        }
        for (long n : edges.getOrDefault(r.id(), Set.of())) {
            Set<Long> in = incoming.get(n); if (in != null) in.remove(r.id());
        }
        for (long n : incoming.getOrDefault(r.id(), Set.of())) {
            Set<Long> out = edges.get(n); if (out != null) out.remove(r.id());
        }
        edges.remove(r.id()); incoming.remove(r.id());
        runs.remove(r.id()); index.remove(r); chunks.remove(r); cableCount -= r.length(); removed.add(r.id());
    }
    private List<NodeSpec.Target> targets(CableRun r) {
        List<NodeSpec.Target> result = new ArrayList<>(r.spec().extra());
        for (int d = 0; d < 6; d++) if ((r.spec().outgoing() & (1 << d)) != 0) {
            Point p = r.start();
            if (r.length() > 1 && (d & 1) == 1) p = r.finish();
            result.add(new NodeSpec.Target(p.step(d), d ^ 1));
        }
        return result;
    }
    private void relink(long id) {
        for (long n : edges.getOrDefault(id, Set.of())) {
            Set<Long> in = incoming.get(n); if (in != null) in.remove(id);
        }
        Set<Long> out = new HashSet<>();
        for (NodeSpec.Target target : targets(runs.get(id))) {
            CableRun next = index.find(target.point());
            if (next != null && next.id() != id && (next.spec().incoming() & (1 << target.incomingFace())) != 0) {
                out.add(next.id()); incoming.computeIfAbsent(next.id(), k -> new HashSet<>()).add(id);
            }
        }
        edges.put(id, out);
    }
    private void invalidate(Set<Long> nodes) {
        Set<Lease> invalid = new HashSet<>();
        for (long n : nodes) invalid.addAll(watchers.getOrDefault(n, Set.of()));
        // A running search has not yet watched its entire reachable subgraph.
        invalid.addAll(jobs);
        for (Lease l : invalid) expire(l);
    }
    private void expire(Lease l) {
        if (!l.valid) return;
        l.valid = false;
        // Immediate revocation is constant work per lease; retiring its watches is budgeted.
        l.queue = new ArrayDeque<>(); l.functions = new LinkedHashSet<>(); cleanup.add(l);
    }
    public Lease query(Point source, int immediateBudget) {
        checkThread();
        CableRun root = index.find(source);
        if (root == null || suspended.contains(root.id())) { Lease l = new Lease(0, version); l.valid = false; return l; }
        Lease cached = queries.get(root.id());
        if (cached != null && cached.valid) return cached;
        Lease lease = new Lease(root.id(), version);
        queries.put(root.id(), lease);
        while (queries.size() > maxQueries) {
            var it = queries.entrySet().iterator(); expire(it.next().getValue()); it.remove();
        }
        advance(lease, Math.max(0, immediateBudget), Long.MAX_VALUE);
        if (lease.valid && !lease.ready) jobs.add(lease);
        return lease;
    }
    private int advance(Lease l, int budget, long deadline) {
        int used = 0;
        if (!l.valid) return used;
        if (!l.ready && l.version != version) { expire(l); return used; }
        while (!l.queue.isEmpty() && used < budget && System.nanoTime() < deadline) {
            long id = l.queue.remove();
            if (!l.visited.add(id)) continue;
            searchSteps++; used++;
            if (suspended.contains(id)) { expire(l); return used; }
            CableRun r = runs.get(id);
            if (r == null) { expire(l); return used; }
            if (isValidating(r)) { expire(l); return used; }
            watchers.computeIfAbsent(id, k -> new HashSet<>()).add(l);
            if (r.spec().functional()) l.functions.add(r.start());
            for (long target : edges.getOrDefault(id, Set.of())) if (!l.visited.contains(target)) l.queue.add(target);
        }
        l.ready = l.queue.isEmpty();
        return used;
    }
    public void tick(int nodeBudget, long deadline) {
        checkThread();
        int cleanupBudget=Math.max(1,nodeBudget/4);
        while(cleanupBudget>0 && !cleanup.isEmpty() && System.nanoTime()<deadline) {
            Lease l=cleanup.peek();var it=l.visited.iterator();
            while(it.hasNext() && cleanupBudget-->0 && System.nanoTime()<deadline) {
                long n=it.next();it.remove();Set<Lease> ws=watchers.get(n);if(ws!=null){ws.remove(l);if(ws.isEmpty())watchers.remove(n);}
            }
            if(l.visited.isEmpty()){l.visited=new HashSet<>();cleanup.remove();}else break;
        }
        int count = jobs.size();
        while (nodeBudget > 0 && count-- > 0 && System.nanoTime() < deadline) {
            Lease l = jobs.remove();
            nodeBudget -= advance(l, nodeBudget, deadline);
            if (l.valid && !l.ready) jobs.add(l);
        }
    }
    /** Used only by a CRC-verified journal reader; callers decide whether the world is trusted. */
    public void restore(Collection<CableRun> records, long restoredVersion) {
        checkThread();
        if (!runs.isEmpty()) throw new IllegalStateException("Restore into nonempty graph");
        for (CableRun r : records) { put(r); nextId = Math.max(nextId, r.id() + 1); }
        for (long id : runs.keySet()) relink(id);
        version = restoredVersion;
    }
}
