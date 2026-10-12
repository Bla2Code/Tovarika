package com.tovarika.tech.exports;

import com.tovarika.tech.products.application.ProductStorage;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.tovarika.tech.exports.ExportSnapshot.*;

@Service
public class ExportWorker {
    private static final long MAX_ARTIFACT_BYTES=110L*1024*1024;
    private final ExportStore store;
    private final ProductStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;
    public ExportWorker(ExportStore store,ProductStorage storage,TransactionTemplate tx,Clock clock) {
        this.store=store;this.storage=storage;this.tx=tx;this.clock=clock;
    }
    public boolean runOnce() {
        var claimed=tx.execute(t->store.claim(clock.instant(),clock.instant().plusSeconds(300)));
        if(claimed==null || claimed.isEmpty()) return false;
        var job=claimed.get();Path temporary=null;String key=null;
        try {
            if(job.attempt()>3) throw new IOException("Recovery attempts exhausted");
            var value=store.find(job.exportId()).orElseThrow();
            if(!value.expiresAt().isAfter(clock.instant())) throw new IOException("Export expired");
            var first=value.items().getFirst();
            boolean unchanged="original".equals(value.format()) || ExportStore.extension(first.mediaType()).equals(value.format());
            if("single".equals(value.packaging()) && unchanged) {
                try(var input=storage.open(first.storageKey())) {
                    if(copyLimited(input,OutputStream.nullOutputStream(),ExportService.MAX_IMAGE_BYTES)!=first.sizeBytes())
                        throw new IOException("Image size mismatch");
                }
                var artifact=new Artifact(first.assetId(),"card_image",first.mediaType(),first.sizeBytes(),first.storageKey(),
                        first.width(),first.height(),first.hasAlpha(),first.createdAt());
                if(!Boolean.TRUE.equals(tx.execute(t->store.complete(job,artifact,false,clock.instant()))))
                    tx.executeWithoutResult(t->store.fail(job,clock.instant()));
                return true;
            }
            temporary=Files.createTempFile("tovarika-export-",".tmp");
            String mediaType="zip".equals(value.packaging())?"application/zip":mediaType(value.format());
            try(var file=Files.newOutputStream(temporary);var bounded=new LimitedOutput(file,MAX_ARTIFACT_BYTES)) {
                if("zip".equals(value.packaging())) {
                    try(var zip=new ZipOutputStream(bounded,StandardCharsets.UTF_8)) {
                        zip.setLevel(Deflater.NO_COMPRESSION);
                        for(var item:value.items()) {
                            zip.putNextEntry(new ZipEntry(item.fileName()));
                            writeImage(item,value.format(),zip);
                            zip.closeEntry();
                        }
                    }
                } else writeImage(first,value.format(),bounded);
            }
            if("zip".equals(value.packaging())) {
                try(var zip=new ZipFile(temporary.toFile(),StandardCharsets.UTF_8)) {
                    if(zip.size()!=value.items().size()) throw new IOException("Incomplete ZIP");
                }
            }
            String assetId="asset_"+UUID.randomUUID().toString().replace("-","");
            key="exports/"+value.id()+"/"+job.attempt()+"/"+assetId;
            String uploadKey=key;
            tx.executeWithoutResult(t->store.reserveFile(uploadKey,value.id(),assetId,clock.instant()));
            long size=Files.size(temporary);
            try(var input=Files.newInputStream(temporary)) {
                // Keep the receipt until the upload finishes, even if deletion removes its project.
                // No project/card lock is held while transferring bytes.
                boolean uploaded=Boolean.TRUE.equals(tx.execute(t->{
                    if(!store.lockReservedFile(uploadKey)) return false;
                    storage.putStream(uploadKey,input,size,mediaType);
                    return true;
                }));
                if(!uploaded) throw new IOException("Export upload was discarded");
            }
            boolean single="single".equals(value.packaging());
            var artifact=new Artifact(assetId,"export_file",mediaType,size,key,single?first.width():null,
                    single?first.height():null,single&&"png".equals(value.format())?first.hasAlpha():null,clock.instant());
            if(!Boolean.TRUE.equals(tx.execute(t->store.complete(job,artifact,true,clock.instant())))) {
                storage.delete(key);
                tx.executeWithoutResult(t->store.fail(job,clock.instant()));
            }
        } catch(Exception failure) {
            // Storage keys and signed links never enter logs or failure payloads.
            if(key!=null) try {storage.delete(key);} catch(RuntimeException ignored) { /* cleanup retries */ }
            tx.executeWithoutResult(t->store.fail(job,clock.instant()));
        } finally {
            if(temporary!=null) try {Files.deleteIfExists(temporary);} catch(IOException ignored) { /* OS cleanup */ }
        }
        return true;
    }
    private void writeImage(ExportSnapshot item,String format,OutputStream out) throws IOException {
        try(var input=storage.open(item.storageKey())) {
            if("original".equals(format) || ExportStore.extension(item.mediaType()).equals(format)) {
                if(copyLimited(input,out,ExportService.MAX_IMAGE_BYTES)!=item.sizeBytes()) throw new IOException("Image size mismatch");
                return;
            }
            byte[] bytes=input.readNBytes((int)ExportService.MAX_IMAGE_BYTES+1);
            if(bytes.length!=item.sizeBytes() || bytes.length>ExportService.MAX_IMAGE_BYTES) throw new IOException("Invalid image size");
            // Saved card images are validated at creation. Bound decoding again for legacy assets.
            ImageIO.setUseCache(false);
            try(var decoded=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(decoded);
                if(!readers.hasNext()) throw new IOException("Unsupported image");
                var reader=readers.next();
                try {
                    reader.setInput(decoded);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1 || height<1 || width>4096 || height>4096 || (long)width*height>8388608) throw new IOException("Invalid image dimensions");
                    BufferedImage image=reader.read(0);
                    if("jpg".equals(format)) {
                        var flattened=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                        var graphics=flattened.createGraphics();
                        try {graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,width,height);graphics.drawImage(image,0,0,null);}
                        finally {graphics.dispose();}
                        image=flattened;
                    }
                    if(!ImageIO.write(image,format,out)) throw new IOException("Encoding unavailable");
                } finally {reader.dispose();}
            }
        }
    }
    private String mediaType(String format) {return "jpg".equals(format)?"image/jpeg":"image/png";}
    private long copyLimited(InputStream input,OutputStream out,long max) throws IOException {
        byte[] buffer=new byte[64*1024];long total=0;int count;
        while((count=input.read(buffer))!=-1) {total+=count;if(total>max) throw new IOException("Image limit exceeded");out.write(buffer,0,count);}
        return total;
    }
    public void cleanup() {
        for(String key:store.cleanupKeys(clock.instant())) {
            try {
                tx.executeWithoutResult(t->{
                    if(!store.lockCleanupFile(key,clock.instant())) return;
                    storage.delete(key);
                    store.forgetFile(key);
                });
            }
            catch(RuntimeException ignored) { /* Retain receipt for the next cleanup pass. */ }
        }
    }
    private static class LimitedOutput extends FilterOutputStream {
        private final long limit;private long total;
        LimitedOutput(OutputStream out,long limit) {super(out);this.limit=limit;}
        public void write(int value) throws IOException {if(++total>limit) throw new IOException("Export limit exceeded");out.write(value);}
        public void write(byte[] bytes,int offset,int length) throws IOException {
            total+=length;if(total>limit) throw new IOException("Export limit exceeded");out.write(bytes,offset,length);
        }
    }
}
