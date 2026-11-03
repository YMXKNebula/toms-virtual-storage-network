package dev.tpc.virtual;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/** Immutable delta frames only. An unclean session is quarantined, never automatically replayed live. */
public final class LedgerJournal implements AutoCloseable {
    public record Frame(long sequence,byte[] payload) {}
    public record Recovery(boolean trusted,String reason,long sequence) {}
    private static final int MAGIC=0x5456534e,FORMAT=1,MAX_FRAME=64*1024*1024;
    private final Path path,clean;
    private final String identity;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4096),r->{var t=new Thread(r,"tvsn-ledger-io");t.setDaemon(true);return t;});
    private FileOutputStream file;
    private DataOutputStream out;
    private volatile IOException failure;
    private volatile long checkpoints;
    private long sequence;
    private boolean trusted,closed;
    public LedgerJournal(Path path,String identity){this.path=path;clean=path.resolveSibling(path.getFileName()+".clean");this.identity=identity;}
    public Recovery open(Consumer<Frame> replay)throws IOException{
        Files.createDirectories(path.getParent());boolean exists=Files.exists(path);String reason="new ledger";
        trusted=!exists || Files.exists(clean) && Files.readString(clean).equals(Long.toString(Files.size(path)));
        Files.deleteIfExists(clean);
        if(exists){
            if(!trusted)reason="unclean world/ledger boundary: inventory and overflow quarantined";
            try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
                if(in.readInt()!=MAGIC || in.readInt()!=FORMAT || !identity.equals(in.readUTF()))throw new IOException("Ledger identity or format mismatch");
                while(in.available()>0){
                    int length=in.readInt();if(length<8 || length>MAX_FRAME)throw new IOException("Invalid ledger frame size");
                    byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException("Partial ledger frame");
                    int expected=in.readInt();CRC32C crc=new CRC32C();crc.update(bytes);if((int)crc.getValue()!=expected)throw new IOException("Ledger CRC mismatch");
                    try(var frame=new DataInputStream(new ByteArrayInputStream(bytes))){
                        long next=frame.readLong();if(next<=sequence)throw new IOException("Ledger sequence did not advance");
                        replay.accept(new Frame(next,frame.readAllBytes()));sequence=next;
                    }
                }
                if(trusted)reason="clean, CRC-verified ledger";
            }catch(IOException | RuntimeException ex){trusted=false;reason="ledger quarantined: "+ex.getMessage();}
        }
        if(trusted){file=new FileOutputStream(path.toFile(),exists);out=new DataOutputStream(new BufferedOutputStream(file));if(!exists){header(out);out.flush();file.getChannel().force(true);}}
        return new Recovery(trusted,reason,sequence);
    }
    public long sequence(){return sequence;}
    public IOException failure(){return failure;}
    public long checkpoints(){return checkpoints;}
    public boolean healthy(){return trusted && failure==null && !closed;}
    public boolean acceptingTransactions(){return healthy() && io.getQueue().remainingCapacity()>16;}
    public void append(byte[] payload){
        if(!healthy())throw new IllegalStateException("Ledger is quarantined or unavailable",failure);
        if(payload.length>MAX_FRAME-8)throw new IllegalArgumentException("Ledger transaction frame too large");
        var frame=new Frame(++sequence,payload.clone());
        submit(()->write(out,frame));
    }
    public void flush(){if(healthy())submit(()->{out.flush();file.getChannel().force(false);});}
    public void export(Path destination)throws IOException{
        if(out==null || failure!=null || closed){Files.copy(path,destination);return;}
        var result=new CompletableFuture<Void>();
        submit(()->{try{out.flush();file.getChannel().force(true);Files.copy(path,destination);result.complete(null);}catch(IOException ex){result.completeExceptionally(ex);throw ex;}});
        try{result.get(30,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}catch(ExecutionException | TimeoutException ex){throw new IOException("Ledger export did not finish",ex);}
    }
    public void checkpoint(List<byte[]> records){
        if(!healthy())return;
        // One logical snapshot split into independently checked frames. Later deltas follow on the same worker.
        var immutable=records.stream().map(byte[]::clone).toList();long first=sequence+1;sequence+=immutable.size();
        submit(()->{
            Path temp=path.resolveSibling(path.getFileName()+".checkpoint");
            try(var f=new FileOutputStream(temp.toFile());var data=new DataOutputStream(new BufferedOutputStream(f))){
                header(data);for(int i=0;i<immutable.size();i++)write(data,new Frame(first+i,immutable.get(i)));data.flush();f.getChannel().force(true);
            }
            out.flush();file.getChannel().force(true);out.close();
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            file=new FileOutputStream(path.toFile(),true);out=new DataOutputStream(new BufferedOutputStream(file));
            checkpoints++;
        });
    }
    private void header(DataOutputStream data)throws IOException{data.writeInt(MAGIC);data.writeInt(FORMAT);data.writeUTF(identity);}
    private static void write(DataOutputStream data,Frame frame)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var payload=new DataOutputStream(bytes)){payload.writeLong(frame.sequence);payload.write(frame.payload);}
        byte[] value=bytes.toByteArray();CRC32C crc=new CRC32C();crc.update(value);data.writeInt(value.length);data.write(value);data.writeInt((int)crc.getValue());
    }
    @FunctionalInterface private interface IoTask{void run()throws IOException;}
    private void submit(IoTask task){
        try{io.execute(()->{if(failure!=null)return;try{task.run();}catch(IOException ex){failure=ex;}});}
        catch(RejectedExecutionException ex){failure=new IOException("Ledger IO queue full; further access blocked",ex);throw new IllegalStateException(failure);}
    }
    public void closeUnclean()throws IOException{finish(false);}
    @Override public void close()throws IOException{finish(true);}
    private void finish(boolean normal)throws IOException{
        if(closed)return;closed=true;io.shutdown();
        try{if(!io.awaitTermination(30,TimeUnit.SECONDS)){io.shutdownNow();throw new IOException("Ledger IO shutdown timed out");}}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
        IOException problem=failure;
        if(out!=null)try{out.flush();if(file.getChannel().isOpen())file.getChannel().force(true);out.close();}
        catch(IOException ex){if(problem==null)problem=ex;else problem.addSuppressed(ex);}
        if(problem!=null)throw problem;
        if(normal && trusted){
            Path temp=clean.resolveSibling(clean.getFileName()+".tmp");
            try(var f=new FileOutputStream(temp.toFile())){f.write(Long.toString(Files.size(path)).getBytes(java.nio.charset.StandardCharsets.UTF_8));f.getChannel().force(true);}
            Files.move(temp,clean,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
