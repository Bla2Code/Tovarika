package com.tovarika.tech.cards;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tovarika.tech.billing.application.*;
import com.tovarika.tech.billing.domain.Billing.*;
import com.tovarika.tech.cards.editing.application.*;
import com.tovarika.tech.cards.editing.domain.*;
import com.tovarika.tech.images.application.*;
import com.tovarika.tech.shared.application.ApiFailure;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.docker.compose.enabled=false","tovarika.storage.minio.initialize-bucket=false",
    "tovarika.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "tovarika.security.password.breached-check-enabled=false","tovarika.cards.worker-enabled=false",
    "tovarika.analysis.worker-enabled=false","tovarika.ai.openai.mode=stub",
    "tovarika.billing.ai-admission-enabled=true"
})
@org.springframework.context.annotation.Import(CardSeriesStorageConfiguration.class)
class CardBillingOperationsIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18-alpine");
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.tovarika.tech.auth.application.SessionService sessions;
    @Autowired com.tovarika.tech.auth.application.port.AuthenticationStore auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired CardGenerationService cards;
    @Autowired CardGenerationStore generations;
    @Autowired CardGenerationWorker worker;
    @Autowired CardImageEditingService edits;
    @Autowired ImageEditingStore editStore;
    @Autowired CardImageEditingWorker editWorker;
    @Autowired CardJobBilling jobs;
    @Autowired com.tovarika.tech.analyses.application.AnalysisService analyses;
    @Autowired com.tovarika.tech.analyses.application.AnalysisWorker analysisWorker;
    @Autowired BillingStore finance;
    @Autowired BillingService billing;
    @MockitoSpyBean TransactionTemplate tx;
    @Autowired com.tovarika.tech.billing.recovery.AiJobRecovery recovery;
    @Autowired com.tovarika.tech.billing.recovery.BillingReconciliation reconciliation;
    @Autowired CardSeriesStorageConfiguration.MemoryStorage storage;
    @MockitoSpyBean ImageGenerationService ai;
    @MockitoSpyBean com.tovarika.tech.billing.usage.JdbcAiUsageStore usageStore;
    final Owner owner=new Owner("user","usr_billingai");
    final ImageEdit entire=new ImageEdit("entire","Blue background",null,null,null,null);
    @BeforeEach void setup() {
        storage.data.clear();storage.failReads=false;
        jdbc.execute("truncate users,trial_sessions,products,projects,product_analyses,project_jobs,cards cascade");
        jdbc.execute("truncate billing_accounts,billing_entitlements cascade");
        jdbc.update("update billing_configuration set active_version='billing-2026-10-14-v1'");
        jdbc.update("insert into users(id,email,email_verified,status,created_at,updated_at) values(?, 'billing-ai@example.test',true,'ACTIVE',now(),now())",owner.id());
        for(int n=1;n<=2;n++) {
            String asset="asset_billing_source_"+n;
            jdbc.update("insert into assets(id,purpose,media_type,size_bytes,url,storage_key,created_at) values(?,'source_image','image/png',100,'',?,now()) on conflict do nothing",asset,"products/billing/"+n);
            jdbc.update("insert into products(id,name,owner_user_id,status,source_asset_id,created_at,updated_at) values(?,'Mug',?,'analysis_ready',?,now(),now())","prd_billing_"+n,owner.id(),asset);
            jdbc.update("insert into product_analyses(id,product_id,description,idea,generation_prompt,revision,created_at,updated_at) values(?,?,'White mug','idea','prompt',1,now(),now())","an_billing_"+n,"prd_billing_"+n);
            jdbc.update("insert into projects(id,product_id,name,default_aspect_ratio,created_at,updated_at) values(?,?,'Billing','1:1',now(),now())","prj_billing"+n,"prd_billing_"+n);
        }
    }
    @AfterEach void cleanup() { storage.failReads=false;jdbc.update("update billing_configuration set active_version='billing-2026-10-14-v1'"); }
    void fund(long amount) { tx.executeWithoutResult(t->finance.trialGrant(finance.lockWallet(owner),owner.id(),amount)); }
    CardGenerationJob start(int project,String key) { return cards.start("prj_billing"+project,key,owner.id(),null,null,"Generate mug","1:1",null,null); }
    CardView ready() { var j=start(1,"initial-generation");assertThat(worker.runOnce()).isTrue();return card(j.cardId()); }
    CardView card(String id) { return cards.getCard("prj_billing1",id,owner.id(),null); }
    String edit(CardView c,String key,ImageEdit e) { return edits.start(c.projectId(),c.id(),key,owner.id(),null,c.currentVersionId(),c.imageRevision(),e,false); }
    Wallet wallet() { return billing.wallet(owner); }
    String state(String job) { return jdbc.queryForObject("select o.state from billing_operations o join project_jobs q on q.billing_operation_id=o.id where q.id=?",String.class,job); }
    long debits() { return jdbc.queryForObject("select count(*) from billing_ledger where kind='ai_debit'",Long.class); }
    ImageEditJob claimEdit() { return tx.execute(t->editStore.claim(Instant.now()).orElseThrow()); }
    ImageEditingStore.Output output(String suffix) { return new ImageEditingStore.Output("asset_"+suffix,"cards/"+suffix,100,1024,1024,false); }

    @Test void tracksPaidGenerationFreeRepeatAndCpuWithoutLeakingTechnicalUsage() {
        fund(1000);var generation=start(1,"usage-generation");worker.runOnce();
        var first=jdbc.queryForMap("select * from billing_ai_attempts where job_id=?",generation.id());
        assertThat(first).containsEntry("provider_outcome","stub").containsEntry("job_outcome","completed").containsEntry("funding","wallet").containsEntry("is_stub",true);
        assertThat(first.get("operation_id")).isEqualTo(jobs.snapshot(generation.id()).getOperationId());
        var card=card(generation.cardId());String free=edit(card,"usage-free",entire);editWorker.runOnce();
        assertThat(jdbc.queryForObject("select funding from billing_ai_attempts where job_id=?",String.class,free)).isEqualTo("free_entitlement");
        card=card(card.id());String cpu=edit(card,"usage-cpu",new ImageEdit("resize",null,null,null,"4:5","fit_pad"));editWorker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_attempts where job_id=?",Integer.class,cpu)).isZero();
        assertThat(jobs.snapshot(generation.id()).toString()).doesNotContain("usage","actualModel","providerOutcome");
    }
    @Test void providerSuccessAndFailedArtifactAreIndependentOutcomes() {
        fund(600);var generation=start(1,"usage-storage-failure");storage.failWrites=true;
        try { worker.runOnce(); } finally { storage.failWrites=false; }
        var row=jdbc.queryForMap("select provider_outcome,job_outcome,funding from billing_ai_attempts where job_id=?",generation.id());
        assertThat(row).containsEntry("provider_outcome","stub").containsEntry("job_outcome","failed").containsEntry("funding","wallet");
        assertThat(state(generation.id())).isEqualTo("released");assertThat(debits()).isZero();
    }
    @Test void staleWorkerResponseDoesNotBecomeSuccessfulFinancialOutcome() {
        fund(600);var generation=start(1,"usage-stale");
        doAnswer(invocation->{
            var result=invocation.callRealMethod();
            jdbc.update("update project_jobs set lease_until=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(10)),generation.id());
            tx.execute(t->generations.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow());
            return result;
        }).when(ai).generate(eq("chatgpt"),any(GenerationRequest.class),any(com.tovarika.tech.billing.usage.AiCallContext.class));
        worker.runOnce();
        assertThat(jdbc.queryForObject("select job_outcome from billing_ai_attempts where job_id=?",String.class,generation.id())).isEqualTo("stale");
        assertThat(state(generation.id())).isEqualTo("reserved");assertThat(debits()).isZero();
    }
    @Test void versionConflictIsFailedRatherThanStaleWhenSameAttemptFinished() {
        fund(600);var generation=start(1,"usage-version-conflict");
        doAnswer(invocation->{var result=invocation.callRealMethod();
            jdbc.update("update cards set image_revision=image_revision+1 where id=?",generation.cardId());return result;
        }).when(ai).generate(eq("chatgpt"),any(GenerationRequest.class),any(com.tovarika.tech.billing.usage.AiCallContext.class));
        worker.runOnce();
        assertThat(jdbc.queryForObject("select job_outcome from billing_ai_attempts where job_id=?",String.class,generation.id())).isEqualTo("failed");
        assertThat(state(generation.id())).isEqualTo("released");assertThat(debits()).isZero();
    }
    @Test void usageDatabaseFailureDoesNotRetryAiOrUndoFinancialSuccess() {
        fund(600);var generation=start(1,"usage-db-failure");
        doThrow(new IllegalStateException("fake analytics outage")).when(usageStore).save(any(),any(),any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(state(generation.id())).isEqualTo("captured");assertThat(debits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from project_jobs where id=?",String.class,generation.id())).isEqualTo("completed");
        assertThat(worker.runOnce()).isFalse();
        verify(ai,times(1)).generate(eq("chatgpt"),any(GenerationRequest.class),any(com.tovarika.tech.billing.usage.AiCallContext.class));
        verify(usageStore,times(3)).save(any(),any(),eq("completed"));
    }
    @Test void concurrentTwoProjectsShareAvailableAndInsufficientFundsCreateNothing() throws Exception {
        fund(600);var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->admissionRace(latch,1,"parallel-project-a"));
            var b=pool.submit(()->admissionRace(latch,2,"parallel-project-b"));latch.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("queued","WALLET_INSUFFICIENT_FUNDS");
        }
        assertThat(wallet().reservedUnits()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from cards",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from project_jobs",Integer.class)).isEqualTo(1);
        verifyNoInteractions(ai);
    }
    String admissionRace(CountDownLatch latch,int project,String key) throws Exception {
        latch.await();try { start(project,key);return "queued"; } catch(BillingFailure failure) { return failure.code(); }
    }
    @Test void concurrentSameKeyCreatesSingleOperationAndPlaceholder() throws Exception {
        fund(600);var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{latch.await();return start(1,"same-generation-key").id();});
            var b=pool.submit(()->{latch.await();return start(1,"same-generation-key").id();});latch.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from billing_operations",Integer.class)).isEqualTo(1);
        assertThat(wallet().reservedUnits()).isEqualTo(400);
    }
    @Test void generationReplayKeepsSnapshotAfterPriceAndFundsChangeAndRejectsChangedPayload() {
        fund(600);var first=start(1,"generation-replay");var snapshot=jobs.snapshot(first.id());
        publishPrice();
        assertThat(start(1,"generation-replay").id()).isEqualTo(first.id());
        assertThat(jobs.snapshot(first.id())).isEqualTo(snapshot);
        assertThatThrownBy(()->cards.start("prj_billing1","generation-replay",owner.id(),null,null,"Other prompt","1:1",null,null))
                .isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->start(2,"generation-replay")).isInstanceOf(ApiFailure.class);
        assertThat(worker.runOnce()).isTrue();
        assertThat(wallet().totalUnits()).isEqualTo(200);assertThat(debits()).isEqualTo(1);
    }
    void publishPrice() {
        String version="ai-test-"+UUID.randomUUID();
        tx.executeWithoutResult(t->{
            jdbc.update("insert into billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason) values(?,600,1,now(),'integration test')",version);
            jdbc.update("insert into billing_prices select ?,operation_code,case when operation_code in ('image_generation','image_entire_edit') then 500 else amount_units end from billing_prices where version_id='billing-2026-10-14-v1'",version);
            jdbc.update("update billing_price_versions set published_at=now() where id=?",version);
            jdbc.update("update billing_configuration set active_version=?",version);
        });
    }
    @Test void failureReleasesMoneyAndRetryReservesConsumesSharedFreeRight() {
        fund(600);var first=start(1,"generation-failure");storage.failReads=true;worker.runOnce();storage.failReads=false;
        assertThat(state(first.id())).isEqualTo("released");assertThat(wallet().totalUnits()).isEqualTo(600);
        var retry=cards.retry(first.projectId(),first.cardId(),"retry-generation",owner.id(),null);
        assertThat(jobs.snapshot(retry.id()).getFunding().getValue()).isEqualTo("free_entitlement");
        assertThat(cards.retry(first.projectId(),first.cardId(),"retry-generation",owner.id(),null).id()).isEqualTo(retry.id());
        assertThatThrownBy(()->start(1,"retry-generation")).isInstanceOf(ApiFailure.class);
        assertThat(worker.runOnce()).isTrue();assertThat(state(retry.id())).isEqualTo("captured");
        assertThat(debits()).isZero();
        String next=edit(card(first.cardId()),"entire-after-retry",entire);
        assertThat(jobs.snapshot(next).getAmountUnits()).isEqualTo(400);
        editWorker.runOnce();assertThat(debits()).isEqualTo(1);
    }
    @Test void failedFreeEditDoesNotConsumeRightAndSuccessThenUsesGenerationPrice() {
        fund(2000);var c=ready();String first=edit(c,"free-edit-failure",entire);
        assertThat(jdbc.queryForObject("select reserved_by from billing_entitlements where subject=?",String.class,c.id())).isNotNull();
        storage.failReads=true;editWorker.runOnce();storage.failReads=false;
        assertThat(state(first)).isEqualTo("released");assertThat(card(c.id()).status()).isEqualTo("ready");
        assertThat(jdbc.queryForObject("select consumed_by from billing_entitlements where subject=?",String.class,c.id())).isNull();
        String second=edit(card(c.id()),"free-edit-success",entire);editWorker.runOnce();
        assertThat(state(second)).isEqualTo("captured");assertThat(debits()).isEqualTo(1);
        String third=edit(card(c.id()),"paid-entire-edit",entire);
        assertThat(jobs.snapshot(third).getAmountUnits()).isEqualTo(400);editWorker.runOnce();
        assertThat(debits()).isEqualTo(2);assertThat(wallet().usedUnits()).isEqualTo(800);
    }
    @Test void changedEditPayloadConflictsAndReplayPrecedesChangedPriceAndCurrentVersion() {
        fund(600);var c=ready();String id=edit(c,"edit-replay-key",entire);var snapshot=jobs.snapshot(id);
        editWorker.runOnce();publishPrice();
        assertThat(edit(c,"edit-replay-key",entire)).isEqualTo(id);assertThat(jobs.snapshot(id)).isEqualTo(snapshot);
        assertThatThrownBy(()->edit(c,"edit-replay-key",new ImageEdit("entire","Red background",null,null,null,null)))
                .isInstanceOf(ApiFailure.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void freeVersionConflictReleasesEntitlementWithoutTrustingReadyStatus() {
        fund(600);var c=ready();String id=edit(c,"edit-version-conflict",entire);var job=claimEdit();
        jdbc.update("update cards set image_revision=image_revision+1 where id=?",c.id());
        assertThat(tx.<Boolean>execute(t->editStore.complete(job,output("conflict_free"),Instant.now()))).isFalse();
        assertThat(state(id)).isEqualTo("released");assertThat(debits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select consumed_by from billing_entitlements where subject=?",String.class,c.id())).isNull();
        assertThat(edit(card(c.id()),"free-after-conflict",entire)).isNotNull();
    }
    @Test void paidVersionConflictReleasesReservation() {
        fund(2000);var c=ready();String id=edit(c,"paid-version-conflict",new ImageEdit("region","Change",new ImageEdit.Rect(0,0,.5,.5),null,null,null));
        var job=claimEdit();jdbc.update("update cards set image_revision=image_revision+1 where id=?",c.id());
        assertThat(tx.<Boolean>execute(t->editStore.complete(job,output("conflict_paid"),Instant.now()))).isFalse();
        assertThat(state(id)).isEqualTo("released");assertThat(wallet().reservedUnits()).isZero();assertThat(debits()).isEqualTo(1);
    }
    @Test void staleEditAttemptCannotReleaseCaptureOrConsumeCurrentReservation() {
        fund(600);var c=ready();String id=edit(c,"edit-stale-attempt",entire);var stale=claimEdit();
        jdbc.update("update project_jobs set lease_until=now()-interval '1 second' where id=?",id);var recovered=claimEdit();
        assertThat(tx.<Boolean>execute(t->editStore.complete(stale,output("stale_edit"),Instant.now()))).isFalse();
        assertThat(tx.<Boolean>execute(t->editStore.fail(stale,"GENERATION_FAILED",Instant.now()))).isFalse();
        assertThat(state(id)).isEqualTo("reserved");
        assertThat(tx.<Boolean>execute(t->editStore.complete(recovered,output("recovered_edit"),Instant.now()))).isTrue();
        assertThat(state(id)).isEqualTo("captured");assertThat(debits()).isEqualTo(1);
    }
    @Test void staleGenerationAttemptAndVersionConflictNeverDebit() {
        fund(600);var j=start(1,"generation-stale");
        var stale=tx.execute(t->generations.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow());
        jdbc.update("update project_jobs set lease_until=now()-interval '1 second' where id=?",j.id());
        var recovered=tx.execute(t->generations.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow());
        var output=new CardGenerationStore.GeneratedAsset("asset_stale_gen","cards/stale-gen","image/png",100,1024,1024);
        assertThat(tx.<Boolean>execute(t->generations.complete(stale,output,Instant.now()))).isFalse();
        assertThat(tx.<Boolean>execute(t->generations.fail(stale,Instant.now()))).isFalse();assertThat(state(j.id())).isEqualTo("reserved");
        jdbc.update("update cards set image_revision=image_revision+1 where id=?",j.cardId());
        assertThat(tx.<Boolean>execute(t->generations.complete(recovered,output,Instant.now()))).isFalse();
        assertThat(state(j.id())).isEqualTo("released");assertThat(wallet().totalUnits()).isEqualTo(600);assertThat(debits()).isZero();
    }
    @Test void financialAndSavedResultRollbackTogetherOnDatabaseFailure() {
        fund(2000);var c=ready();String id=edit(c,"rollback-edit-key",entire);var job=claimEdit();
        assertThatThrownBy(()->tx.executeWithoutResult(t->{
            assertThat(editStore.complete(job,output("rolled_back"),Instant.now())).isTrue();
            jdbc.execute("select 1/0");
        })).isInstanceOf(RuntimeException.class);
        assertThat(state(id)).isEqualTo("reserved");assertThat(card(c.id()).currentVersionId()).isEqualTo(c.currentVersionId());
        assertThat(jdbc.queryForObject("select count(*) from assets where id='asset_rolled_back'",Integer.class)).isZero();
        tx.executeWithoutResult(t->editStore.fail(job,"GENERATION_FAILED",Instant.now()));
        assertThat(state(id)).isEqualTo("released");assertThat(debits()).isEqualTo(1);
    }
    @Test void storageWriteFailureReleasesPaidReserve() {
        fund(2000);var c=ready();String id=edit(c,"storage-failure-key",new ImageEdit("region","Change",new ImageEdit.Rect(0,0,.5,.5),null,null,null));
        storage.failWrites=true;try { editWorker.runOnce(); } finally { storage.failWrites=false; }
        assertThat(state(id)).isEqualTo("released");assertThat(card(c.id()).currentVersionId()).isEqualTo(c.currentVersionId());
        assertThat(wallet().reservedUnits()).isZero();assertThat(debits()).isEqualTo(1);
    }
    @Test void localResizeAndOutpaintWithoutMarginsNeverCallAiOrDebit() {
        fund(600);var c=ready();clearInvocations(ai);
        for(String mode:List.of("crop","fit_pad","outpaint")) {
            c=card(c.id());String id=edit(c,"local-resize-"+mode,new ImageEdit("resize",null,null,null,"1:1",mode));
            assertThat(jobs.snapshot(id).getAmountUnits()).isZero();editWorker.runOnce();
            assertThat(state(id)).isEqualTo("released");
        }
        verifyNoInteractions(ai);assertThat(debits()).isEqualTo(1);assertThat(wallet().totalUnits()).isEqualTo(200);
        var last=card(c.id());edits.undo(last.projectId(),last.id(),"free-undo-resize",owner.id(),null,last.currentVersionId(),last.imageRevision());
        last=card(c.id());edits.redo(last.projectId(),last.id(),"free-redo-resize",owner.id(),null,last.currentVersionId(),last.imageRevision());
        assertThat(debits()).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from billing_operations",Integer.class)).isEqualTo(4);
    }
    @Test void aiOutpaintIsServerClassifiedAndChargedOnlyAtSuccess() {
        fund(2000);var c=ready();clearInvocations(ai);
        String id=edit(c,"paid-outpaint-key",new ImageEdit("resize",null,null,null,"16:9","outpaint"));
        assertThat(jobs.snapshot(id).getOperationCode().getValue()).isEqualTo("image_outpaint");
        assertThat(wallet().reservedUnits()).isEqualTo(400);assertThat(debits()).isEqualTo(1);
        editWorker.runOnce();assertThat(state(id)).isEqualTo("captured");assertThat(wallet().usedUnits()).isEqualTo(800);
        verify(ai).edit(eq("chatgpt"),any(ImageEditInput.class),any(com.tovarika.tech.billing.usage.AiCallContext.class));
    }
    @Test void noAiFinalizationReturnsReservedMoneyEvenAfterPaidPreflight() {
        fund(2000);var c=ready();String id=edit(c,"no-ai-finalization",new ImageEdit("resize",null,null,null,"16:9","outpaint"));
        var job=claimEdit();assertThat(tx.<Boolean>execute(t->editStore.complete(job,output("no_ai"),Instant.now(),false))).isTrue();
        assertThat(state(id)).isEqualTo("released");assertThat(wallet().reservedUnits()).isZero();assertThat(debits()).isEqualTo(1);
    }
    @Test void allLocalAiActionsUseFixedPricesAndEntireDoesNotGuessFromPrompt() {
        fund(4000);var c=ready();clearInvocations(ai);
        var actions=List.of(new ImageEdit("region","Resize without AI",new ImageEdit.Rect(0,0,.5,.5),null,null,null),
                new ImageEdit("erase",null,null,null,null,null,new ImageEdit.BrushMask("brush",List.of(new ImageEdit.Stroke(.1,List.of(new ImageEdit.Point(.5,.5)))))),
                new ImageEdit("remove_background",null,null,"preserve_graphics",null,null));
        for(int i=0;i<actions.size();i++) {
            String id=edit(card(c.id()),"local-ai-action-"+i,actions.get(i));assertThat(jobs.snapshot(id).getAmountUnits()).isEqualTo(200);
            editWorker.runOnce();assertThat(state(id)).isEqualTo("captured");
        }
        assertThat(wallet().usedUnits()).isEqualTo(1000);assertThat(debits()).isEqualTo(4);
        verify(ai,times(3)).edit(eq("chatgpt"),any(ImageEditInput.class),any(com.tovarika.tech.billing.usage.AiCallContext.class));
    }
    @Test void grandfatheredGenerationAndEditingFinishWithoutFinancialBackfill() {
        fund(600);
        var now=Instant.now();
        var legacy=new CardGenerationJob("job_legacy_billing","prj_billing1","card_legacy_billing","queued",null,0,now,null,null);
        tx.executeWithoutResult(t->generations.enqueue(legacy,1,null,null,"1:1","Generate mug",null,null,null,null,null,1,"Mug",owner.id(),"legacy-generate-key",now));
        assertThat(jobs.snapshot(legacy.id())).isNull();assertThat(worker.runOnce()).isTrue();
        var c=card(legacy.cardId());
        tx.executeWithoutResult(t->editStore.enqueue("job_legacy_edit",c.projectId(),c.id(),"card_image_edit",owner.id(),"legacy-edit-key","a".repeat(64),
                c.currentVersionId(),c.imageRevision(),"{\"kind\":\"entire\",\"prompt\":\"Blue\"}",now));
        assertThat(editWorker.runOnce()).isTrue();assertThat(jobs.snapshot("job_legacy_edit")).isNull();
        assertThat(wallet().totalUnits()).isEqualTo(600);assertThat(debits()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from billing_operations",Integer.class)).isZero();
    }
    @Test void noAiFreeFinalizationDoesNotConsumeEntitlement() {
        fund(600);var c=ready();String id=edit(c,"no-ai-free-edit",entire);var job=claimEdit();
        assertThat(tx.<Boolean>execute(t->editStore.complete(job,output("no_ai_free"),Instant.now(),false))).isTrue();
        assertThat(state(id)).isEqualTo("released");
        assertThat(jdbc.queryForObject("select consumed_by from billing_entitlements where subject=?",String.class,c.id())).isNull();
        assertThat(jobs.snapshot(edit(card(c.id()),"free-after-no-ai",entire)).getFunding().getValue()).isEqualTo("free_entitlement");
    }
    @Test void paidStorageAndFreeRetryFailureReleaseWithoutSpendingRight() {
        fund(600);var first=start(1,"generation-storage-failure");
        storage.failWrites=true;try { worker.runOnce(); } finally { storage.failWrites=false; }
        assertThat(state(first.id())).isEqualTo("released");assertThat(wallet().reservedUnits()).isZero();
        var retry=cards.retry(first.projectId(),first.cardId(),"retry-failure-key",owner.id(),null);
        storage.failReads=true;worker.runOnce();storage.failReads=false;
        assertThat(state(retry.id())).isEqualTo("released");
        var again=cards.retry(first.projectId(),first.cardId(),"retry-after-failure",owner.id(),null);
        assertThat(jobs.snapshot(again.id()).getFunding().getValue()).isEqualTo("free_entitlement");
        assertThat(worker.runOnce()).isTrue();assertThat(debits()).isZero();assertThat(wallet().totalUnits()).isEqualTo(600);
    }
    @Test void paidDatabaseResultAndDebitRollbackTogetherThenWorkerFailureReleases() {
        fund(600);var j=start(1,"generation-db-rollback");
        var claimed=tx.execute(t->generations.claim(Instant.now(),Instant.now().plusSeconds(300)).orElseThrow());
        var output=new CardGenerationStore.GeneratedAsset("asset_rollback_gen","cards/rollback-gen","image/png",100,1024,1024);
        assertThatThrownBy(()->tx.executeWithoutResult(t->{generations.complete(claimed,output,Instant.now());jdbc.execute("select 1/0");})).isInstanceOf(RuntimeException.class);
        assertThat(state(j.id())).isEqualTo("reserved");assertThat(debits()).isZero();assertThat(wallet().reservedUnits()).isEqualTo(400);
        assertThat(card(j.cardId()).currentVersionId()).isNull();
        tx.executeWithoutResult(t->generations.fail(claimed,Instant.now()));assertThat(state(j.id())).isEqualTo("released");
        assertThat(wallet().totalUnits()).isEqualTo(600);
    }
    @Test void httpAdmissionAndPollingExposeSameImmutableReceipt() throws Exception {
        fund(600);
        var token=sessions.create(auth.findUserById(owner.id()).orElseThrow(),
                new com.tovarika.tech.auth.application.RequestMetadata("Test Browser","192.0.2.xxx")).accessToken();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/projects/prj_billing1/cards")
                .header("Authorization","Bearer "+token).header("Idempotency-Key","http-billing-generation")
                .contentType("application/json").content("{\"prompt\":\"Generate mug\",\"aspectRatio\":\"1:1\"}");
        var response=mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var mapper=new tools.jackson.databind.ObjectMapper();var accepted=mapper.readTree(response);
        String id=accepted.path("jobId").asText();var receipt=accepted.path("billing");
        assertThat(receipt.path("amountUnits").asLong()).isEqualTo(400);
        assertThat(receipt.path("funding").asText()).isEqualTo("wallet");
        worker.runOnce();publishPrice();
        var poll=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/"+id)
                .header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(poll).path("billing")).isEqualTo(receipt);
        assertThat(mapper.readTree(poll).path("status").asText()).isEqualTo("completed");
        var replay=mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay).path("billing")).isEqualTo(receipt);
    }
    @Test void trialIdentityGetsSameAdmissionPolicyAndReplayWithoutAnotherGrant() {
        String trial="trial_billing_ai";
        jdbc.update("insert into trial_sessions(id,token_hash,generation_limit,generations_used,created_at,expires_at) values(?, 'test-billing-cookie-hash',3,0,now(),now()+interval '1 day')",trial);
        jdbc.update("update products set owner_user_id=null,owner_trial_session_id=? where id='prd_billing_1'",trial);
        var first=cards.start("prj_billing1","trial-generation-key",null,trial,null,"Mug","1:1",null,null);
        assertThat(jobs.snapshot(first.id()).getAmountUnits()).isEqualTo(400);
        worker.runOnce();
        assertThat(cards.start("prj_billing1","trial-generation-key",null,trial,null,"Mug","1:1",null,null).id()).isEqualTo(first.id());
        assertThat(billing.wallet(new Owner("trial",trial)).totalUnits()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Integer.class)).isEqualTo(1);
    }
    @Test void newAdmissionAttachesLegacyConvertedTrialBeforeGrantWithoutMintingAgain() {
        String trial="trial_legacybilling";
        jdbc.update("insert into trial_sessions(id,token_hash,generation_limit,generations_used,owner_user_id,converted_at,created_at,expires_at) values(?,'legacy-billing-hash',3,0,?,now(),now(),now()+interval '1 day')",trial,owner.id());
        var oldOwner=new Owner("trial",trial);
        tx.executeWithoutResult(t->finance.trialGrant(finance.lockWallet(oldOwner),trial,600));
        var j=start(1,"legacy-converted-admission");assertThat(worker.runOnce()).isTrue();
        assertThat(wallet().totalUnits()).isEqualTo(200);
        assertThat(billing.wallet(oldOwner).id()).isEqualTo(wallet().id());
        assertThat(jdbc.queryForObject("select count(*) from billing_trial_grants",Integer.class)).isEqualTo(1);
        assertThat(state(j.id())).isEqualTo("captured");
    }
    @Test void coreSupportsMultipleOperationsForSameLogicalProjectWithoutDomainBusyGuard() throws Exception {
        fund(600);var latch=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<2;i++) { int n=i;futures.add(pool.submit(()->{latch.await();try {
                tx.execute(t->billing.admit(owner,new Command("logical-project","same-project-key-"+n,1,Map.of(1,"a".repeat(64)),Code.image_generation,null,"logical-project","same-project-job-"+n)));
                return "reserved";
            } catch(BillingFailure e) { return e.code(); }})); }
            latch.countDown();assertThat(List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("reserved","WALLET_INSUFFICIENT_FUNDS");
        }
        assertThat(wallet().reservedUnits()).isEqualTo(400);assertThat(jdbc.queryForObject("select count(*) from project_jobs",Integer.class)).isZero();
    }
    @Test void heartbeatPreventsReclaimAndRejectsStaleRenewal() {
        fund(600);var job=start(1,"heartbeat");
        var claimed=tx.execute(t->generations.claim(Instant.now(),Instant.now().minusSeconds(1)).orElseThrow());
        assertThat(recovery.renew("project_jobs",job.id(),claimed.attempt())).isTrue();
        assertThat(recovery.renew("project_jobs",job.id(),claimed.attempt()-1)).isFalse();
        assertThat(tx.<Optional<CardGenerationJob>>execute(t->generations.claim(Instant.now(),Instant.now().plusSeconds(600)))).isEmpty();
        assertThat(wallet().reservedUnits()).isEqualTo(400);
        tx.executeWithoutResult(t->generations.fail(claimed,Instant.now()));
        assertThat(recovery.renew("project_jobs",job.id(),claimed.attempt())).isFalse();
        assertThat(tx.<Boolean>execute(t->generations.complete(claimed,new CardGenerationStore.GeneratedAsset("asset_late","cards/late","image/png",100,10,10),Instant.now()))).isFalse();
        assertThat(debits()).isZero();assertThat(wallet().reservedUnits()).isZero();
    }
    @Test void queuedAgeAndAttemptLimitFailWithoutAnotherProviderCall() {
        fund(600);var job=start(1,"aged-queue");
        jdbc.update("update project_jobs set created_at=now()-interval '25 hours' where id=?",job.id());
        worker.runOnce();assertThat(state(job.id())).isEqualTo("released");
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_attempts where job_id=?",Long.class,job.id())).isZero();
        var next=start(2,"exhausted-attempt");
        jdbc.update("update project_jobs set status='processing',attempt=3,lease_until=now()-interval '1 minute' where id=?",next.id());
        worker.runOnce();assertThat(state(next.id())).isEqualTo("released");
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_attempts where job_id=?",Long.class,next.id())).isZero();
    }
    @Test void reconciliationReleasesOrphansButNeverActiveCallsOrQueuesByTtl() {
        fund(1600);var active=start(1,"active-lease");var queued=start(2,"queued-job");
        var claimed=tx.execute(t->generations.claim(Instant.now(),Instant.now().minusSeconds(1)).orElseThrow());
        var orphan=tx.execute(t->billing.admit(owner,new Command("card-image-command","orphan",1,Map.of(1,"a".repeat(64)),Code.image_generation,null,"project_jobs","deleted-job")).operation());
        reconciliation.reconcile();
        assertThat(finance.operation(orphan.snapshot().operationId()).state()).isEqualTo("released");
        assertThat(wallet().reservedUnits()).isEqualTo(800);
        assertThat(state(active.id())).isEqualTo("reserved");assertThat(state(queued.id())).isEqualTo("reserved");
        // Simulate an inconsistent terminal record; release is conservative, never a fabricated debit.
        jdbc.update("update project_jobs set status='failed' where id=?",active.id());
        reconciliation.reconcile();reconciliation.reconcile();
        assertThat(state(active.id())).isEqualTo("released");assertThat(wallet().reservedUnits()).isEqualTo(400);
        assertThat(tx.<Boolean>execute(t->generations.complete(claimed,new CardGenerationStore.GeneratedAsset("asset_orphan_late","cards/late","image/png",100,10,10),Instant.now()))).isFalse();
        assertThat(debits()).isZero();
    }
    @Test void unknownGenerationCommitOutcomePreservesCommittedArtifactAndSingleDebit() {
        fund(600);var job=start(1,"ambiguous-generation-commit");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            if(calls.incrementAndGet()==2) throw new IllegalStateException("Commit acknowledgement lost");
            return result;
        }).when(tx).execute(any(org.springframework.transaction.support.TransactionCallback.class));
        try { worker.runOnce(); } finally { reset(tx); }
        assertThat(state(job.id())).isEqualTo("captured");assertThat(debits()).isEqualTo(1);
        String key=jdbc.queryForObject("select a.storage_key from cards c join assets a on a.id=c.image_asset_id where c.id=?",String.class,job.cardId());
        assertThat(storage.data).containsKey(key);
        assertThat(worker.runOnce()).isFalse();assertThat(wallet().totalUnits()).isEqualTo(200);
    }
    @Test void unknownEditCommitOutcomePreservesCommittedArtifactAndConsumedEntitlement() {
        fund(1000);var card=ready();String job=edit(card,"ambiguous-edit-commit",entire);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            if(calls.incrementAndGet()==2) throw new IllegalStateException("Commit acknowledgement lost");
            return result;
        }).when(tx).execute(any(org.springframework.transaction.support.TransactionCallback.class));
        try { editWorker.runOnce(); } finally { reset(tx); }
        assertThat(state(job)).isEqualTo("captured");assertThat(debits()).isEqualTo(1);
        String key=jdbc.queryForObject("select a.storage_key from cards c join assets a on a.id=c.image_asset_id where c.id=?",String.class,card.id());
        assertThat(storage.data).containsKey(key);
        assertThat(jobs.snapshot(edit(card(card.id()),"after-ambiguous",entire)).getFunding().getValue()).isEqualTo("wallet");
    }
    @Test void disablingAdmissionKeepsPendingFinalizationAndGrandfathersNewCommands() {
        fund(1000);var paid=start(1,"accepted-before-disable");
        var field=org.springframework.util.ReflectionUtils.findField(CardJobBilling.class,"enabled");
        org.springframework.util.ReflectionUtils.makeAccessible(field);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
        org.springframework.util.ReflectionUtils.setField(field,target,false);
        try {
            var legacy=start(2,"accepted-after-disable");
            assertThat(jobs.snapshot(legacy.id())).isNull();
            worker.runOnce();worker.runOnce();
            assertThat(state(paid.id())).isEqualTo("captured");
            assertThat(wallet().totalUnits()).isEqualTo(600);assertThat(wallet().reservedUnits()).isZero();
            assertThat(debits()).isEqualTo(1);
        } finally { org.springframework.util.ReflectionUtils.setField(field,target,true); }
    }

    @Test void deletedProjectRetainsHistoryNetSummaryAndUsage() {
        fund(1000);var job=start(1,"delete-after-success");worker.runOnce();
        String debit=jdbc.queryForObject("select id from billing_ledger where kind='ai_debit'",String.class);
        tx.executeWithoutResult(t->billing.compensate(wallet().id(),debit,"hidden-full-refund",400));
        jdbc.update("delete from projects where id=?",job.projectId());
        var history=tx.execute(t->billing.history(owner,new HistoryQuery(null,null,"newest",100,null)));
        assertThat(history.items()).hasSize(2);
        assertThat(history.items()).extracting(Entry::kind).containsExactly("ai_debit","trial_grant");
        assertThat(wallet().totalUnits()).isEqualTo(1000);assertThat(wallet().usedUnits()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from billing_ai_attempts where job_id=?",Long.class,job.id())).isEqualTo(1);
    }

    @Test void pausedAiLaunchesKeepReplayAndPendingSuccessFailureAndCpuAvailable() {
        fund(1400);var card=ready();var accepted=start(2,"accepted-before-pause");
        var field=org.springframework.util.ReflectionUtils.findField(CardJobBilling.class,"launchesEnabled");
        org.springframework.util.ReflectionUtils.makeAccessible(field);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
        org.springframework.util.ReflectionUtils.setField(field,target,false);
        try {
            assertThat(start(2,"accepted-before-pause").id()).isEqualTo(accepted.id());
            worker.runOnce();assertThat(state(accepted.id())).isEqualTo("captured");
            assertThatThrownBy(()->start(2,"new-paused-generation")).isInstanceOfSatisfying(ApiFailure.class,e->{
                assertThat(e.status()).isEqualTo(503);assertThat(e.code()).isEqualTo("BILLING_AI_PAUSED");
            });
            assertThatThrownBy(()->edit(card,"new-paused-entire",entire)).isInstanceOf(ApiFailure.class);
            String cpu=edit(card,"cpu-while-paused",new ImageEdit("resize",null,null,null,"4:5","fit_pad"));
            editWorker.runOnce();assertThat(state(cpu)).isEqualTo("released");
            assertThat(wallet().reservedUnits()).isZero();assertThat(debits()).isEqualTo(2);
        } finally { org.springframework.util.ReflectionUtils.setField(field,target,true); }
    }

    History history() { return tx.execute(t->billing.history(owner,new HistoryQuery(null,null,"newest",100,null))); }
    @Test void freeRepeatAnalysisAndResizeAreVisibleOnceWithoutChangingMoney() {
        fund(1400);var card=ready();String free=edit(card,"visible-free-repeat",entire);editWorker.runOnce();
        card=card(card.id());String cpu=edit(card,"visible-cpu-resize",new ImageEdit("resize",null,null,null,"4:5","fit_pad"));editWorker.runOnce();
        var analysis=analyses.start("prd_billing_2","visible-analysis",owner.id(),null);analysisWorker.runOnce();
        var before=wallet();var history=history();
        assertThat(history.items()).hasSize(5);
        assertThat(history.items()).filteredOn(e->e.kind().equals("free_operation"))
            .extracting(Entry::operationCode).containsExactlyInAnyOrder(Code.image_entire_edit,Code.cpu_only,Code.product_analysis);
        assertThat(history.items()).filteredOn(e->e.kind().equals("free_operation")).extracting(Entry::amountUnits).containsOnly(0L);
        assertThat(wallet().totalUnits()).isEqualTo(before.totalUnits());assertThat(wallet().usedUnits()).isEqualTo(400);
        assertThat(history().items()).isEqualTo(history.items());
        assertThat(jdbc.queryForObject("select count(*) from billing_activity_outbox where posting_id is null",Long.class)).isZero();
        assertThat(state(free)).isEqualTo("captured");assertThat(state(cpu)).isEqualTo("released");
        assertThat(analyses.getJob(analysis.id(),owner.id(),null).status()).isEqualTo("completed");
        assertThatThrownBy(()->jdbc.update("update billing_ledger set amount_units=1 where kind='free_operation'"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void failedFreeOperationHasNoSuccessReceiptOrZeroHistoryRow() {
        fund(1000);var card=ready();String free=edit(card,"failed-free-no-history",entire);
        storage.failReads=true;try { editWorker.runOnce(); } finally { storage.failReads=false; }
        assertThat(state(free)).isEqualTo("released");
        assertThat(history().items()).extracting(Entry::kind).containsExactly("ai_debit","trial_grant");
        assertThat(jdbc.queryForObject("select count(*) from billing_activity_outbox where reference=?",Long.class,"project_jobs:"+free)).isZero();
    }
    @Test void unbilledLegacySuccessAndBackfillAreVisibleWithOriginalTimeButNeverDebit() throws Exception {
        fund(600);
        var field=org.springframework.util.ReflectionUtils.findField(CardJobBilling.class,"enabled");
        org.springframework.util.ReflectionUtils.makeAccessible(field);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
        org.springframework.util.ReflectionUtils.setField(field,target,false);
        String job;
        try { job=start(1,"unbilled-history-generation").id();worker.runOnce(); }
        finally { org.springframework.util.ReflectionUtils.setField(field,target,true); }
        assertThat(jobs.snapshot(job)).isNull();assertThat(wallet().totalUnits()).isEqualTo(600);assertThat(debits()).isZero();
        // Simulate a completed pre-migration job, without erasing any domain result or financial posting.
        jdbc.execute("truncate billing_activity_outbox");
        var completedAt=java.sql.Timestamp.from(Instant.parse("2026-10-10T10:00:00Z"));
        jdbc.update("update project_jobs set finished_at=? where id=?",completedAt,job);
        String sql=new String(getClass().getResourceAsStream("/db/changelog/changes/022-billing-free-history.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(sql.substring(sql.indexOf("-- Backfill")));
        var free=history().items().stream().filter(e->e.kind().equals("free_operation")).findFirst().orElseThrow();
        assertThat(free.occurredAt()).isEqualTo(completedAt.toInstant());assertThat(free.amountUnits()).isZero();
        assertThat(wallet().totalUnits()).isEqualTo(600);assertThat(wallet().usedUnits()).isZero();assertThat(debits()).isZero();
        jdbc.update("delete from projects where id='prj_billing1'");
        assertThat(history().items()).anySatisfy(e->assertThat(e.id()).isEqualTo(free.id()));
    }
    @Test void pendingFreeHistorySurvivesTrialConversionAndRetainsOneResult() {
        String trial="trial_history_conversion";
        jdbc.update("insert into trial_sessions(id,token_hash,generation_limit,generations_used,created_at,expires_at) values(?,'history-test-hash',3,0,now(),now()+interval '1 day')",trial);
        jdbc.update("update products set owner_user_id=null,owner_trial_session_id=? where id='prd_billing_1'",trial);
        var generation=cards.start("prj_billing1","trial-history-generation",null,trial,null,"Mug","1:1",null,null);worker.runOnce();
        var card=cards.getCard("prj_billing1",generation.cardId(),null,trial);
        String free=edits.start(card.projectId(),card.id(),"trial-history-entire",null,trial,card.currentVersionId(),card.imageRevision(),entire,false);
        editWorker.runOnce();
        tx.executeWithoutResult(t->{billing.convertOwner(new Owner("trial",trial),owner);
            jdbc.update("update products set owner_trial_session_id=null,owner_user_id=? where id='prd_billing_1'",owner.id());});
        assertThat(history().items()).filteredOn(e->e.kind().equals("free_operation")).hasSize(1);
        assertThat(history().items()).filteredOn(e->e.kind().equals("ai_debit")).hasSize(1);
        assertThat(wallet().totalUnits()).isEqualTo(200);assertThat(wallet().usedUnits()).isEqualTo(400);
        assertThat(state(free)).isEqualTo("captured");
    }
    @Test void freeHistoryProjectionRollsBackAndSnapshotExcludesLateOlderActivity() {
        fund(1000);var card=ready();String free=edit(card,"cursor-free-entire",entire);editWorker.runOnce();
        assertThatThrownBy(()->tx.execute(t->{billing.history(owner,new HistoryQuery(null,null,"newest",1,null));throw new IllegalStateException("Read commit failed");}))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where kind='free_operation'",Long.class)).isZero();
        var page=tx.execute(t->billing.history(owner,new HistoryQuery(null,null,"newest",1,null)));
        assertThat(page.items().getFirst().kind()).isEqualTo("free_operation");
        // A late projected older completion must not enter an already started cursor snapshot.
        jdbc.update("insert into billing_activity_outbox(reference,owner_kind,owner_subject,operation_code,occurred_at) values('late-older-activity','user',?,'product_analysis',now()-interval '2 days')",owner.id());
        var second=tx.execute(t->billing.history(owner,new HistoryQuery(null,null,"newest",1,page.nextCursor())));
        var third=tx.execute(t->billing.history(owner,new HistoryQuery(null,null,"newest",1,second.nextCursor())));
        assertThat(second.items()).extracting(Entry::kind).containsExactly("ai_debit");
        assertThat(third.items()).extracting(Entry::kind).containsExactly("trial_grant");assertThat(third.hasMore()).isFalse();
        assertThat(history().items()).hasSize(4);
    }

}
