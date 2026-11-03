package dev.tpc.core;

import java.util.*;

/** Interval treaps indexed by the fixed horizontal chunk coordinate; one entry per run. */
final class ChunkIndex {
    private record Key(int axis, int a, int b) {}
    private static final class Node {
        CableRun run; Node left,right; int high,max,priority,low;
        Node(CableRun r) { run=r; low=coordinate(r.start(),r.axis()); high=coordinate(r.finish(),r.axis()); max=high; priority=Long.hashCode(r.id()*0x9E3779B97F4A7C15L); }
    }
    private final Map<Key,Node> roots=new HashMap<>();
    private static int coordinate(Point p,int axis) { return axis==0?p.x()>>4:axis==2?p.z()>>4:p.y(); }
    private static Key key(CableRun r) { Point p=r.start(); return r.axis()==0?new Key(0,p.z()>>4,0):r.axis()==2?new Key(2,p.x()>>4,0):new Key(1,p.x()>>4,p.z()>>4); }
    private static void fix(Node n) { n.max=Math.max(n.high,Math.max(n.left==null?Integer.MIN_VALUE:n.left.max,n.right==null?Integer.MIN_VALUE:n.right.max)); }
    private static boolean less(Node a,Node b) { return a.low<b.low || a.low==b.low && a.run.id()<b.run.id(); }
    private static Node right(Node n) { Node l=n.left; n.left=l.right; l.right=n; fix(n);fix(l);return l; }
    private static Node left(Node n) { Node r=n.right;n.right=r.left;r.left=n;fix(n);fix(r);return r; }
    private static Node add(Node n,Node fresh) {
        if(n==null)return fresh;
        if(less(fresh,n)) { n.left=add(n.left,fresh);if(n.left.priority<n.priority)n=right(n); }
        else { n.right=add(n.right,fresh);if(n.right.priority<n.priority)n=left(n); }
        fix(n);return n;
    }
    private static Node remove(Node n,Node target) {
        if(n==null)return null;
        if(n.run.id()==target.run.id()) {
            if(n.left==null)return n.right;if(n.right==null)return n.left;
            if(n.left.priority<n.right.priority){n=right(n);n.right=remove(n.right,target);}else{n=left(n);n.left=remove(n.left,target);}
        } else if(less(target,n))n.left=remove(n.left,target);else n.right=remove(n.right,target);
        fix(n);return n;
    }
    void add(CableRun r){Key k=key(r);roots.put(k,add(roots.get(k),new Node(r)));}
    void remove(CableRun r){Key k=key(r);Node root=remove(roots.get(k),new Node(r));if(root==null)roots.remove(k);else roots.put(k,root);}
    private static void query(Node n,int coordinate,Collection<CableRun> result){
        if(n==null || n.max<coordinate)return;
        query(n.left,coordinate,result);
        if(n.low<=coordinate){if(n.high>=coordinate)result.add(n.run);query(n.right,coordinate,result);}
    }
    List<CableRun> query(int x,int z){
        List<CableRun> result=new ArrayList<>();query(roots.get(new Key(0,z,0)),x,result);query(roots.get(new Key(2,x,0)),z,result);
        all(roots.get(new Key(1,x,z)),result);return result;
    }
    private static void all(Node n,List<CableRun> result){if(n!=null){all(n.left,result);result.add(n.run);all(n.right,result);}}
}
