package com.tovarika.tech.cards.editing.application;

import com.tovarika.tech.cards.editing.domain.ImageEdit;
import com.tovarika.tech.products.application.ProductStorage;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class CardImageEditingWorker {
    private final ImageEditingStore store;
    private final ProductStorage storage;
    private final CardImageProcessor processor;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    public CardImageEditingWorker(ImageEditingStore store,ProductStorage storage,CardImageProcessor processor,
            TransactionTemplate transactions,ObjectMapper mapper,Clock clock) {
        this.store=store;this.storage=storage;this.processor=processor;this.transactions=transactions;this.mapper=mapper;this.clock=clock;
    }
    public boolean runOnce() {
        var claimed=transactions.execute(tx->store.claim(clock.instant()));
        if(claimed==null || claimed.isEmpty()) return false;
        var job=claimed.get();String key=null;
        try {
            if(job.attempt()>3) throw new IllegalStateException("Recovery attempts exhausted");
            var source=store.source(job);
            var edit=mapper.readValue(job.payload(),ImageEdit.class);
            var result=processor.process(storage.read(source.storageKey()),edit);
            String id="asset_"+UUID.randomUUID().toString().replace("-","");key="cards/"+id;
            storage.put(key,result.bytes(),"image/png");
            var output=new ImageEditingStore.Output(id,key,result.bytes().length,result.width(),result.height(),result.hasAlpha());
            if(!Boolean.TRUE.equals(transactions.execute(tx->store.complete(job,output,clock.instant())))) delete(key);
        } catch(RuntimeException failure) {
            if(key!=null) delete(key);
            String code=failure instanceof UnsupportedOperationException?"IMAGE_EDIT_UNAVAILABLE":"GENERATION_FAILED";
            transactions.executeWithoutResult(tx->store.fail(job,code,clock.instant()));
        }
        return true;
    }
    private void delete(String key) {
        try { storage.delete(key); } catch(RuntimeException ignored) { /* Durable object GC is a separate existing concern. */ }
    }
}
