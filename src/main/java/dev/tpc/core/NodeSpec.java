package dev.tpc.core;

import java.util.List;

/** A server-thread observation of the original cable API, never an inferred cable. */
public record NodeSpec(int incoming, int outgoing, boolean functional, List<Target> extra) {
    public record Target(Point point, int incomingFace) {}
    public NodeSpec {
        if ((incoming & ~63) != 0 || (outgoing & ~63) != 0) throw new IllegalArgumentException("Direction mask");
        extra = List.copyOf(extra);
        for (Target t : extra) if (t.incomingFace < 0 || t.incomingFace > 5) throw new IllegalArgumentException("Face");
    }
    public static NodeSpec ordinary(int mask) { return new NodeSpec(63, mask, false, List.of()); }
    public int compressionAxis() {
        if (functional || incoming != 63 || !extra.isEmpty()) return -1;
        return switch (outgoing) { case 48 -> 0; case 3 -> 1; case 12 -> 2; default -> -1; };
    }
}
