package dev.tpc.core;

import java.util.*;

/** Three interval maps. A ten-million-block line occupies one entry, not 625,000 chunk entries. */
final class SpatialIndex {
    private record Line(int axis, Point fixed) {}
    private final Map<Line, NavigableMap<Integer, CableRun>> lines = new HashMap<>();
    private Line key(Point p, int axis) { return new Line(axis, p.at(axis, 0)); }
    CableRun find(Point p) {
        for (int axis = 0; axis < 3; axis++) {
            var line = lines.get(key(p, axis));
            if (line == null) continue;
            var e = line.floorEntry(p.coordinate(axis));
            if (e != null && e.getValue().contains(p)) return e.getValue();
        }
        return null;
    }
    void add(CableRun run) {
        var line = lines.computeIfAbsent(key(run.start(), run.axis()), k -> new TreeMap<>());
        var floor = line.floorEntry(run.end());
        if (floor != null && floor.getValue().end() >= run.start().coordinate(run.axis())) throw new IllegalStateException("Overlapping runs");
        line.put(run.start().coordinate(run.axis()), run);
    }
    void remove(CableRun run) {
        Line k = key(run.start(), run.axis());
        var line = lines.get(k);
        line.remove(run.start().coordinate(run.axis()));
        if (line.isEmpty()) lines.remove(k);
    }
}
