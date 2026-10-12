package com.tovarika.tech.cards.editing.application;

import com.tovarika.tech.cards.editing.domain.ImageEdit;
import com.tovarika.tech.cards.editing.domain.ImageEditJob;
import com.tovarika.tech.shared.application.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CardImageEditingService {
    private final ImageEditingStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    public CardImageEditingService(ImageEditingStore store,ObjectMapper mapper,Clock clock) {
        this.store=store;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public String start(String projectId,String cardId,String key,String userId,String trialId,
            String base,Long revision,ImageEdit edit,boolean legacy) {
        validateKey(key);
        try { edit.validate(); } catch(IllegalArgumentException invalid) { throw new ApiFailure(422,"VALIDATION_ERROR",invalid.getMessage()); }
        if((base==null)!=(revision==null) || (!legacy && base==null)) throw new ApiFailure(422,"VALIDATION_ERROR","Both version fields are required");
        // Keep the six-field payload (and idempotency digest) of pre-mask commands unchanged.
        var parameters=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(edit);
        if(edit.mask()==null) parameters.remove("mask");
        String payload=mapper.writeValueAsString(parameters);
        String digest=digest(legacy?"region":"image-edit",projectId,cardId,base,revision,payload);
        store.lockOwnerProject(projectId,userId,trialId);
        var current=store.current(projectId,cardId);
        String scope=userId==null?trialId:userId;
        var replay=store.replay(scope,key,digest,false);
        if(replay.isPresent()) return replay.get();
        store.requireIdle(projectId);
        if(current.versionId()==null || !"ready".equals(current.status()))
            throw new ApiFailure(409,"RESULT_NOT_READY","A successful image is required");
        requireVersion(current,base==null?current.versionId():base,revision==null?current.revision():revision);
        String id="job_"+UUID.randomUUID().toString().replace("-","");
        store.enqueue(id,projectId,cardId,legacy?"card_region_edit":"card_image_edit",scope,key,digest,
                current.versionId(),current.revision(),payload,clock.instant());
        return id;
    }
    @Transactional
    public void undo(String projectId,String cardId,String key,String userId,String trialId,String base,long revision) {
        validateKey(key);
        String digest=digest("undo",projectId,cardId,base,revision,"");
        store.lockOwnerProject(projectId,userId,trialId);
        var current=store.current(projectId,cardId);
        String scope=userId==null?trialId:userId;
        if(store.replay(scope,key,digest,true).isPresent()) return;
        store.requireIdle(projectId);
        requireVersion(current,base,revision);
        if(current.previousVersionId()==null) throw new ApiFailure(409,"UNDO_NOT_AVAILABLE","No previous successful image");
        store.undo(projectId,cardId,scope,key,digest,current,clock.instant());
    }
    @Transactional
    public void redo(String projectId,String cardId,String key,String userId,String trialId,String base,long revision) {
        validateKey(key);
        String digest=digest("redo",projectId,cardId,base,revision,"");
        store.lockOwnerProject(projectId,userId,trialId);
        var current=store.current(projectId,cardId);
        String scope=userId==null?trialId:userId;
        if(store.replay(scope,key,digest,true).isPresent()) return;
        store.requireIdle(projectId);
        requireVersion(current,base,revision);
        if(current.redoVersionId()==null) throw new ApiFailure(409,"REDO_NOT_AVAILABLE","No undone image to restore");
        store.redo(projectId,cardId,scope,key,digest,current,clock.instant());
    }
    @Transactional(readOnly=true)
    public Optional<ImageEditJob> findJob(String id,String userId,String trialId) { return store.findJob(id,userId,trialId); }
    private void requireVersion(ImageEditingStore.Current current,String base,long revision) {
        if(base==null || revision<1 || !base.equals(current.versionId()) || revision!=current.revision())
            throw new ApiFailure(409,"CARD_VERSION_CONFLICT","Current image version has changed");
    }
    private void validateKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw new ApiFailure(400,"VALIDATION_ERROR","Invalid idempotency key");
    }
    private String digest(String command,String project,String card,String base,Long revision,String payload) {
        String canonical=mapper.writeValueAsString(List.of(command,project,card,base==null?"":base,revision==null?0:revision,payload));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
