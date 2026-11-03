package dev.tpc.core;

public record Point(int x, int y, int z) {
    // Ordinals deliberately match Minecraft Direction: DOWN UP NORTH SOUTH WEST EAST.
    public static final int[] DX = {0, 0, 0, 0, -1, 1};
    public static final int[] DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] DZ = {0, 0, -1, 1, 0, 0};
    public Point step(int direction) { return new Point(x + DX[direction], y + DY[direction], z + DZ[direction]); }
    public int coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
    public Point at(int axis, int coordinate) {
        return new Point(axis == 0 ? coordinate : x, axis == 1 ? coordinate : y, axis == 2 ? coordinate : z);
    }
}
