package com.tovarika.tech.cards.editing.application;

import com.tovarika.tech.billing.usage.AiUsageService;

import com.tovarika.tech.cards.editing.domain.ImageEdit;
import com.tovarika.tech.products.application.ProductStorage;
import com.tovarika.tech.images.infrastructure.OpenAiResponsesClient.ImageEditFailure;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class CardImageEditingWorker {
    private static final Logger log = LoggerFactory.getLogger(CardImageEditingWorker.class);
    private final ImageEditingStore store;
    private final ProductStorage storage;
    private final CardImageProcessor processor;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AiUsageService usage;
    private final com.tovarika.tech.billing.recovery.AiJobRecovery recovery;
    public CardImageEditingWorker(ImageEditingStore store,ProductStorage storage,CardImageProcessor processor,
            TransactionTemplate transactions,ObjectMapper mapper,Clock clock,AiUsageService usage, com.tovarika.tech.billing.recovery.AiJobRecovery recovery) {
        this.store=store;this.storage=storage;this.processor=processor;this.transactions=transactions;this.mapper=mapper;this.clock=clock;this.usage=usage;this.recovery=recovery;
    }
    public boolean runOnce() {
        var claimed=transactions.execute(tx->store.claim(clock.instant()));
        if(claimed==null || claimed.isEmpty()) return false;
        var job=claimed.get();String key=null;
        var context=usage.context("project_jobs",job.id(),job.attempt());
        String outcome="unknown";
        try (var lease=recovery.start("project_jobs",job.id(),job.attempt())) {
            recovery.check(job.attempt(),job.createdAt());
            var source=store.source(job);
            var edit=mapper.readValue(job.payload(),ImageEdit.class);
            var result=processor.process(storage.read(source.storageKey()),edit,context);
            String id="asset_"+UUID.randomUUID().toString().replace("-","");key="cards/"+id;
            storage.put(key,result.bytes(),"image/png");
            var output=new ImageEditingStore.Output(id,key,result.bytes().length,result.width(),result.height(),result.hasAlpha());
            boolean applied=Boolean.TRUE.equals(transactions.execute(tx->store.complete(job,output,clock.instant(),result.aiCalled())));
            if(!applied) delete(key);
            outcome=applied?"completed":"stale";
        } catch(RuntimeException failure) {
            if (failure instanceof ImageEditFailure providerFailure)
                log.warn("Card image edit failed: job={}, reason={}, providerStatus={}",
                        job.id(), providerFailure.reason(), providerFailure.httpStatus());
            else log.warn("Card image edit failed: job={}, exceptionType={}", job.id(), failure.getClass().getSimpleName());
            String code=failure instanceof UnsupportedOperationException?"IMAGE_EDIT_UNAVAILABLE":"GENERATION_FAILED";
            boolean applied=Boolean.TRUE.equals(transactions.execute(tx->store.fail(job,code,clock.instant())));
            if(key!=null) delete(key);
            outcome=applied?"failed":"stale";
        } finally { usage.finish(context,outcome); }
        return true;
    }
    private void delete(String key) {
        recovery.deleteUnreferenced(storage,key);
    }
}
