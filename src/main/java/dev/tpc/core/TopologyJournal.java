package dev.tpc.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32C;

/** Append-only committed edit frames. The worker receives immutable graph data only. */
public final class TopologyJournal implements AutoCloseable {
    private static final int MAGIC = 0x54504331, FORMAT = 1;
    public record Recovery(List<CableRun> runs, long version, boolean trusted, String reason) {}
    private final Path path, clean;
    private final String identity;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4096), r -> { Thread t = new Thread(r,"tpc-topology-io"); t.setDaemon(true); return t; });
    private volatile IOException failure;
    private volatile long frames;
    private volatile boolean compacting;
    private DataOutputStream out;
    private FileOutputStream file;
    private boolean canClean = true;

    public TopologyJournal(Path path, String identity) { this.path = path; this.clean = path.resolveSibling(path.getFileName()+".clean"); this.identity = identity; }
    public Recovery open(int maxResidentRuns) throws IOException {
        Files.createDirectories(path.getParent());
        boolean exists = Files.exists(path), trusted = false;
        Map<Long,CableRun> records = new HashMap<>(); long version = 0;
        String reason = "new index";
        if (exists) {
            boolean closed = Files.exists(clean) && Files.readString(clean).equals(Long.toString(Files.size(path)));
            Files.deleteIfExists(clean); // Must happen before returning any live connections.
            if (closed) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                    if (in.readInt()!=MAGIC || in.readInt()!=FORMAT || !identity.equals(in.readUTF())) throw new IOException("Journal identity or format mismatch");
                    while (in.available()>0) {
                        frames++;
                        int length=in.readInt(); if (length<0 || length>64*1024*1024) throw new IOException("Invalid frame length");
                        byte[] bytes=in.readNBytes(length); if (bytes.length!=length) throw new EOFException("Incomplete edit");
                        int checksum=in.readInt(); CRC32C crc=new CRC32C(); crc.update(bytes);
                        if ((int)crc.getValue()!=checksum) throw new IOException("CRC mismatch");
                        try (DataInputStream edit=new DataInputStream(new ByteArrayInputStream(bytes))) {
                            version=edit.readLong(); int removes=checkedCount(edit.readInt());
                            for(int i=0;i<removes;i++) records.remove(edit.readLong());
                            int adds=checkedCount(edit.readInt());
                            for(int i=0;i<adds;i++) { CableRun run=readRun(edit); records.put(run.id(),run); if(records.size()>maxResidentRuns) throw new IOException("Resident graph budget exceeded"); }
                            if(edit.available()!=0) throw new IOException("Trailing edit bytes");
                        }
                    }
                    trusted=true; reason="clean, CRC-verified journal";
                } catch (IOException | IllegalArgumentException e) { records.clear(); version=0; reason=e.getMessage(); }
            } else { reason="unclean session: revalidation required"; }
            if (!trusted) {
                // Keep recovery evidence. A fresh file cannot accidentally trust a partial old prefix.
                Files.move(path,path.resolveSibling(path.getFileName()+".untrusted-"+UUID.randomUUID()));
            }
        }
        file=new FileOutputStream(path.toFile(),trusted);
        out=new DataOutputStream(new BufferedOutputStream(file));
        if (!trusted) { out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeUTF(identity); out.flush(); file.getChannel().force(true); }
        return new Recovery(List.copyOf(records.values()),version,trusted,reason);
    }
    private static int checkedCount(int count) throws IOException { if(count<0 || count>2_000_000) throw new IOException("Invalid frame count"); return count; }
    public void append(Topology.Edit edit) {
        if (failure!=null) return;
        try { worker.execute(() -> { try { write(edit); } catch(IOException e) { failure=e; } }); }
        catch (RejectedExecutionException e) { failure=new IOException("Journal queue full; clean recovery disabled",e); }
    }
    private void write(Topology.Edit edit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream data=new DataOutputStream(bytes)) {
            data.writeLong(edit.version()); data.writeInt(edit.removed().size());
            for(long id:edit.removed()) data.writeLong(id);
            data.writeInt(edit.added().size()); for(CableRun run:edit.added()) writeRun(data,run);
        }
        byte[] payload=bytes.toByteArray(); CRC32C crc=new CRC32C(); crc.update(payload);
        out.writeInt(payload.length); out.write(payload); out.writeInt((int)crc.getValue());
        frames++;
    }
    public boolean needsCompaction(){return failure==null && !compacting && frames>4096;}
    /** Enqueue after capturing a version-stable snapshot; subsequent edits follow it in the same queue. */
    public void compact(Topology.Edit snapshot) {
        if(failure!=null || compacting)return;compacting=true;
        try{worker.execute(()->{
            Path tmp=path.resolveSibling(path.getFileName()+".compact");
            try {
                FileOutputStream tempFile=new FileOutputStream(tmp.toFile());
                DataOutputStream tempOut=new DataOutputStream(new BufferedOutputStream(tempFile));
                DataOutputStream previous=out;FileOutputStream previousFile=file;
                try {
                    out=tempOut;file=tempFile;out.writeInt(MAGIC);out.writeInt(FORMAT);out.writeUTF(identity);
                    frames=0;
                    for(int i=0;i<snapshot.added().size();i+=512)write(new Topology.Edit(List.of(),snapshot.added().subList(i,Math.min(i+512,snapshot.added().size())),snapshot.version()));
                    out.flush();file.getChannel().force(true);
                } finally {tempOut.close();out=previous;file=previousFile;}
                out.flush();file.getChannel().force(true);out.close();
                Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                file=new FileOutputStream(path.toFile(),true);out=new DataOutputStream(new BufferedOutputStream(file));
            }catch(IOException e){failure=e;}finally{compacting=false;}
        });}catch(RejectedExecutionException e){compacting=false;failure=new IOException("Compaction queue full",e);}
    }
    public IOException failure() { return failure; }
    public void disableCleanRecovery() { canClean=false; }
    @Override public void close() throws IOException {
        worker.shutdown();
        try { if (!worker.awaitTermination(30,TimeUnit.SECONDS)) { canClean=false; worker.shutdownNow(); throw new IOException("Journal close timeout"); } }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); canClean=false; throw new IOException(e); }
        if(out!=null) { out.flush(); file.getChannel().force(true); out.close(); }
        if(failure!=null) throw failure;
        if(canClean) {
            Path tmp=clean.resolveSibling(clean.getFileName()+".tmp"); Files.writeString(tmp,Long.toString(Files.size(path)));
            Files.move(tmp,clean,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static void writePoint(DataOutput out, Point p) throws IOException { out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); }
    private static Point readPoint(DataInput in) throws IOException { return new Point(in.readInt(),in.readInt(),in.readInt()); }
    static void writeRun(DataOutput out,CableRun r) throws IOException {
        out.writeLong(r.id()); writePoint(out,r.start()); out.writeByte(r.axis()); out.writeInt(r.end());
        NodeSpec s=r.spec(); out.writeByte(s.incoming()); out.writeByte(s.outgoing()); out.writeBoolean(s.functional()); out.writeInt(s.extra().size());
        for(NodeSpec.Target t:s.extra()) { writePoint(out,t.point()); out.writeByte(t.incomingFace()); }
    }
    static CableRun readRun(DataInput in) throws IOException {
        long id=in.readLong(); Point p=readPoint(in); int axis=in.readUnsignedByte(),end=in.readInt();
        int incoming=in.readUnsignedByte(),outgoing=in.readUnsignedByte(); boolean functional=in.readBoolean();
        int count=checkedCount(in.readInt()); List<NodeSpec.Target> targets=new ArrayList<>();
        for(int i=0;i<count;i++) targets.add(new NodeSpec.Target(readPoint(in),in.readUnsignedByte()));
        return new CableRun(id,p,axis,end,new NodeSpec(incoming,outgoing,functional,targets));
    }
}
