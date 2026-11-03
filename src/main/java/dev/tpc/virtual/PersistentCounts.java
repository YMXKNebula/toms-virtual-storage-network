package dev.tpc.virtual;

import java.util.*;

/** Immutable count snapshot. Updates copy a search path, never the whole variant map. */
public final class PersistentCounts<K> extends AbstractMap<K,Long> {
    private record Node<K>(int hash,int priority,List<Map.Entry<K,Long>> bucket,Node<K> left,Node<K> right){}
    private final Node<K> root;
    private final int size;
    public PersistentCounts(){this(null,0);}
    private PersistentCounts(Node<K> root,int size){this.root=root;this.size=size;}
    public PersistentCounts<K> with(K key,long quantity){
        Objects.requireNonNull(key);if(quantity<0)throw new IllegalArgumentException("Negative quantity");
        Long old=get(key);if(old!=null && old==quantity || old==null && quantity==0)return this;
        return new PersistentCounts<>(update(root,key,key.hashCode(),quantity),size+(old==null?1:quantity==0?-1:0));
    }
    @Override public int size(){return size;}
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    @Override public Long get(Object key){
        if(key==null)return null;int hash=key.hashCode();var node=root;
        while(node!=null){int cmp=Integer.compare(hash,node.hash);if(cmp==0){for(var entry:node.bucket)if(entry.getKey().equals(key))return entry.getValue();return null;}node=cmp<0?node.left:node.right;}return null;
    }
    private static int priority(int hash){int h=hash;h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;return h^(h>>>16);}
    private static <K> Node<K> update(Node<K> node,K key,int hash,long quantity){
        if(node==null)return quantity==0?null:new Node<>(hash,priority(hash),List.of(Map.entry(key,quantity)),null,null);
        int cmp=Integer.compare(hash,node.hash);
        if(cmp==0){
            var bucket=new ArrayList<Map.Entry<K,Long>>();boolean found=false;
            for(var entry:node.bucket){if(entry.getKey().equals(key)){found=true;if(quantity>0)bucket.add(Map.entry(key,quantity));}else bucket.add(entry);}
            if(!found && quantity>0)bucket.add(Map.entry(key,quantity));
            return bucket.isEmpty()?merge(node.left,node.right):new Node<>(hash,node.priority,List.copyOf(bucket),node.left,node.right);
        }
        if(cmp<0){
            var child=update(node.left,key,hash,quantity);var result=new Node<>(node.hash,node.priority,node.bucket,child,node.right);
            if(child!=null && Integer.compareUnsigned(child.priority,node.priority)<0)return new Node<>(child.hash,child.priority,child.bucket,child.left,new Node<>(node.hash,node.priority,node.bucket,child.right,node.right));return result;
        }
        var child=update(node.right,key,hash,quantity);var result=new Node<>(node.hash,node.priority,node.bucket,node.left,child);
        if(child!=null && Integer.compareUnsigned(child.priority,node.priority)<0)return new Node<>(child.hash,child.priority,child.bucket,new Node<>(node.hash,node.priority,node.bucket,node.left,child.left),child.right);return result;
    }
    private static <K> Node<K> merge(Node<K> a,Node<K> b){
        if(a==null)return b;if(b==null)return a;
        if(Integer.compareUnsigned(a.priority,b.priority)<0)return new Node<>(a.hash,a.priority,a.bucket,a.left,merge(a.right,b));
        return new Node<>(b.hash,b.priority,b.bucket,merge(a,b.left),b.right);
    }
    @Override public Set<Map.Entry<K,Long>> entrySet(){
        return new AbstractSet<>(){
            public int size(){return PersistentCounts.this.size;}
            public Iterator<Map.Entry<K,Long>> iterator(){return new Iterator<>(){
                final ArrayDeque<Node<K>> stack=new ArrayDeque<>();Iterator<Map.Entry<K,Long>> bucket=Collections.emptyIterator();{push(root);}
                private void push(Node<K> node){while(node!=null){stack.push(node);node=node.left;}}
                public boolean hasNext(){return bucket.hasNext() || !stack.isEmpty();}
                public Map.Entry<K,Long> next(){if(!bucket.hasNext()){if(stack.isEmpty())throw new NoSuchElementException();var node=stack.pop();push(node.right);bucket=node.bucket.iterator();}return bucket.next();}
            };}
        };
    }
}
