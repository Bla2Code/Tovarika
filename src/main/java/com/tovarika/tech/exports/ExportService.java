package com.tovarika.tech.exports;

import com.tovarika.tech.shared.application.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.tovarika.tech.exports.ExportSnapshot.*;

@Service
public class ExportService {
    public static final long MAX_IMAGE_BYTES=10L*1024*1024;
    public static final long MAX_TOTAL_BYTES=100L*1024*1024;
    private final ExportStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    public ExportService(ExportStore store,ObjectMapper mapper,Clock clock) { this.store=store;this.mapper=mapper;this.clock=clock; }
    @Transactional
    public Export start(String projectId,String key,String userId,Request request) {
        requireUser(userId);
        validate(request,key);
        store.lockOwnerProject(projectId,userId);
        String digest=digest(projectId,request);
        var previous=store.replay(userId,key,digest);
        if(previous.isPresent()) return previous.get();
        store.requireCapacity(userId);
        var expected=new HashMap<String,Expectation>();
        if(request.expectedImages()!=null) for(var e:request.expectedImages()) expected.put(e.cardId(),e);
        var items=new ArrayList<ExportSnapshot>();
        long total=0;
        // Project lock also serializes edit/Undo/Redo; all selected versions form one snapshot.
        for(String id:request.cardIds()) {
            var item=store.snapshot(projectId,id,request.format());
            var e=expected.get(id);
            if(e!=null && (!item.versionId().equals(e.versionId()) || item.imageRevision()!=e.imageRevision()))
                throw new ApiFailure(409,"CARD_VERSION_CONFLICT","Selected image changed");
            if(item.sizeBytes()<1 || item.sizeBytes()>MAX_IMAGE_BYTES) throw invalid();
            total+=item.sizeBytes();items.add(item);
        }
        if(total>MAX_TOTAL_BYTES) throw invalid();
        items.sort(Comparator.comparing(ExportSnapshot::fileName));
        String id=id("exp_");Instant now=clock.instant();
        var value=new Export(id,projectId,id("job_"),request.format(),request.packaging(),
                "single".equals(request.packaging())?items.getFirst().fileName():"tovarika-"+id.substring(4,16)+".zip",
                List.copyOf(items),null,"queued",null,now,now.plusSeconds(86400));
        store.enqueue(value,userId,key,digest);
        return value;
    }
    public Export get(String id,String userId) {
        requireUser(userId);
        return available(store.owned(id,userId).orElseThrow(()->new ApiFailure(404,"EXPORT_NOT_FOUND","Export not found")));
    }
    public Export download(String id) {
        var value=available(store.find(id).orElseThrow(()->new ApiFailure(404,"EXPORT_NOT_FOUND","Export not found")));
        if(!"completed".equals(value.status()) || value.artifactId()==null)
            throw new ApiFailure(409,"RESULT_NOT_READY","Export is not ready");
        return value;
    }
    private Export available(Export value) {
        if(!value.expiresAt().isAfter(clock.instant())) throw new ApiFailure(410,"EXPORT_EXPIRED","Export expired");
        return value;
    }
    public Optional<Job> findJob(String id,String userId) { return userId==null?Optional.empty():store.ownedJob(id,userId); }
    private void requireUser(String userId) { if(userId==null) throw new ApiFailure(403,"REGISTRATION_REQUIRED","Registration is required to download"); }
    private void validate(Request r,String key) {
        if(key==null || key.isBlank() || key.length()>128 || r==null || r.cardIds()==null || r.cardIds().isEmpty()
                || r.cardIds().size()>10 || r.cardIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(r.cardIds()).size()!=r.cardIds().size()
                || r.format()==null || r.packaging()==null
                || !List.of("original","png","jpg").contains(r.format()) || !List.of("single","zip").contains(r.packaging())) throw invalid();
        if("single".equals(r.packaging()) && r.cardIds().size()!=1) throw invalid();
        if("original".equals(r.format()) && "zip".equals(r.packaging()) && r.cardIds().size()<2) throw invalid();
        if(r.expectedImages()!=null) {
            var ids=new HashSet<String>();
            for(var e:r.expectedImages()) if(e==null || e.cardId()==null || e.versionId()==null || e.versionId().isBlank()
                    || e.imageRevision()==null || e.imageRevision()<1 || !ids.add(e.cardId())) throw invalid();
            if(!ids.equals(new HashSet<>(r.cardIds()))) throw invalid();
        }
    }
    private ApiFailure invalid() { return new ApiFailure(422,"VALIDATION_ERROR","Invalid export selection"); }
    private String digest(String projectId,Request r) {
        var expected=r.expectedImages()==null?List.of():r.expectedImages().stream().sorted(Comparator.comparing(Expectation::cardId)).toList();
        String canonical=mapper.writeValueAsString(List.of("export",projectId,r.cardIds().stream().sorted().toList(),r.format(),r.packaging(),expected));
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private String id(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
}
