package dev.tpc.core;

public record CableRun(long id, Point start, int axis, int end, NodeSpec spec) {
    public CableRun {
        if (id <= 0 || axis < 0 || axis > 2 || end < start.coordinate(axis)) throw new IllegalArgumentException("Run");
        if (length(start, axis, end) > 1 && spec.compressionAxis() != axis) throw new IllegalArgumentException("Non-compressible run");
    }
    private static long length(Point p, int axis, int end) { return (long) end - p.coordinate(axis) + 1; }
    public long length() { return length(start, axis, end); }
    public Point finish() { return start.at(axis, end); }
    public boolean contains(Point p) {
        return p.at(axis, start.coordinate(axis)).equals(start) && p.coordinate(axis) >= start.coordinate(axis) && p.coordinate(axis) <= end;
    }
}
