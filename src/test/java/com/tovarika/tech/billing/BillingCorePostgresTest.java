package com.tovarika.tech.billing;

import static org.assertj.core.api.Assertions.*;
import com.tovarika.tech.billing.application.*;
import com.tovarika.tech.billing.domain.Billing;
import com.tovarika.tech.billing.domain.Billing.*;
import com.tovarika.tech.billing.infrastructure.JdbcBillingStore;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class BillingCorePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static JdbcBillingStore store;
    static BillingService service;

    @BeforeAll static void migrate() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcBillingStore(jdbc); service = new BillingService(store);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), db)) {
                liquibase.update(new Contexts(), new LabelExpression());
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc.execute("create table billing_test_results(operation_id varchar(40) primary key, result text not null)");
    }
    @BeforeEach void resetActiveVersion() {
        jdbc.update("update billing_configuration set active_version='billing-2026-10-14-v1'");
    }
    static <T> T tx(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    static Owner owner() { return new Owner("user", Billing.id("usr_")); }
    static Command command(Code code, String subject) {
        return command("key_" + UUID.randomUUID(), code, subject);
    }
    static Command command(String key, Code code, String subject) {
        return new Command("cards", key, 1, Map.of(1, "a".repeat(64)), code, subject, "cards.generate", Billing.id("job_"));
    }
    static Wallet fund(Owner owner, long amount) {
        return tx(() -> { var wallet = store.lockWallet(owner); store.trialGrant(wallet, owner.id(), amount); return store.lockWallet(wallet.id()); });
    }
    static Operation admit(Owner owner, Command command) { return tx(() -> service.admit(owner, command).operation()); }
    static Wallet read(Owner owner) { return tx(() -> service.wallet(owner)); }
    static String debit(Operation operation) {
        return jdbc.queryForObject("select id from billing_ledger where operation_id=?", String.class, operation.snapshot().operationId());
    }
    static List<Boolean> race(int count, java.util.function.IntFunction<Boolean> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i=0;i<count;i++) { int n=i; futures.add(executor.submit(() -> { start.await(); return action.apply(n); })); }
            start.countDown();
            var results = new ArrayList<Boolean>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void concurrentReserveAndReplayCannotOverspend() throws Exception {
        var owner = owner(); fund(owner, 600);
        var commands = java.util.stream.IntStream.range(0,12).mapToObj(i -> command(Code.image_generation, null)).toList();
        var successes = race(12, i -> {
            try { admit(owner, commands.get(i)); return true; }
            catch (BillingFailure f) { assertThat(f.status()).isEqualTo(402); return false; }
        });
        assertThat(successes).containsOnlyOnce(true);
        assertThat(read(owner).availableUnits()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from billing_operations where wallet_id=?", Long.class, read(owner).id())).isEqualTo(1);
        var op = tx(() -> store.receipt(read(owner).id(), "cards", commands.get(successes.indexOf(true)).key()).orElseThrow());
        var original = commands.get(successes.indexOf(true));
        assertThat(tx(() -> service.admit(owner, original)).replay()).isTrue();
        var conflict = new Command(original.namespace(), original.key(), 1, Map.of(1,"b".repeat(64)), original.code(), null, original.jobNamespace(), original.jobId());
        assertThatThrownBy(() -> admit(owner, conflict)).isInstanceOf(BillingFailure.class);
        assertThat(tx(() -> service.finish(op.snapshot().operationId(), 0, Outcome.captured))).isTrue();
        assertThat(tx(() -> service.finish(op.snapshot().operationId(), 0, Outcome.captured))).isTrue();
        assertThat(read(owner).totalUnits()).isEqualTo(200);
        assertThat(read(owner).usedUnits()).isEqualTo(400);
        assertThat(read(owner).reservedUnits()).isZero();
    }
    @Test void sameKeyConcurrentAdmissionAndTerminalEffectsAreExactlyOnce() throws Exception {
        var owner = owner(); fund(owner, 600);
        var command = command(Code.image_generation, null);
        var results = race(10, i -> tx(() -> !service.admit(owner, command).replay()));
        assertThat(results).containsOnlyOnce(true);
        var operation = admit(owner, command);
        assertThat(race(8, i -> tx(() -> service.finish(operation.snapshot().operationId(),0,Outcome.captured)))).containsOnly(true);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where operation_id=?", Long.class, operation.snapshot().operationId())).isEqualTo(1);
        assertThat(read(owner).usedUnits()).isEqualTo(400);
    }
    @Test void refundIsBoundedIdempotentHiddenAndNeverRewritesDebit() throws Exception {
        var owner = owner(); fund(owner,600);
        var op = admit(owner, command(Code.image_generation, null));
        tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        String debit = debit(op), reference = Billing.id("refund_");
        assertThat(race(8, i -> { tx(() -> service.compensate(op.walletId(),debit,reference,250)); return true; })).containsOnly(true);
        assertThat(read(owner).totalUnits()).isEqualTo(450);
        assertThat(read(owner).usedUnits()).isEqualTo(150);
        assertThatThrownBy(() -> tx(() -> service.compensate(op.walletId(),debit,Billing.id("refund_"),151))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(() -> tx(() -> service.compensate(op.walletId(),debit,reference,251))).isInstanceOf(BillingFailure.class);
        tx(() -> service.compensate(op.walletId(),debit,Billing.id("refund_"),150));
        var history = tx(() -> service.history(owner,new HistoryQuery(null,null,"newest",10,null)));
        assertThat(history.items()).extracting(Entry::kind).containsExactly("ai_debit","trial_grant");
        assertThat(history.items().getFirst().balanceAfterUnits()).isEqualTo(200);
        assertThat(read(owner).usedUnits()).isZero();
        assertThatThrownBy(() -> jdbc.update("update billing_ledger set balance_after_units=0 where id=?",debit)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from billing_ledger where id=?",debit)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void entitlementRaceFailureReleaseConsumeAndPaidFallback() throws Exception {
        var owner = owner(); fund(owner,600); String subject = Billing.id("opaque_");
        var commands = java.util.stream.IntStream.range(0,8).mapToObj(i -> command(Code.image_entire_edit,subject)).toList();
        var successes = race(8, i -> {
            try { assertThat(admit(owner,commands.get(i)).snapshot().funding()).isEqualTo(Funding.free_entitlement); return true; }
            catch (BillingFailure f) { assertThat(f.code()).isEqualTo("BILLING_ENTITLEMENT_BUSY"); return false; }
        });
        assertThat(successes).containsOnlyOnce(true);
        var op = admit(owner,commands.get(successes.indexOf(true)));
        tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.released));
        assertThat(tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.released))).isTrue();
        assertThat(tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured))).isFalse();
        var retry = admit(owner,command(Code.image_entire_edit,subject));
        tx(() -> service.finish(retry.snapshot().operationId(),0,Outcome.captured));
        assertThat(read(owner).totalUnits()).isEqualTo(600);
        var paid = admit(owner,command(Code.image_entire_edit,subject));
        assertThat(paid.snapshot().amountUnits()).isEqualTo(400);
        tx(() -> service.finish(paid.snapshot().operationId(),0,Outcome.captured));
        tx(() -> service.compensate(paid.walletId(),debit(paid),Billing.id("refund_"),400));
        assertThat(admit(owner,command(Code.image_entire_edit,subject)).snapshot().funding()).isEqualTo(Funding.wallet);
    }
    @Test void staleWorkerAndCallerRollbackCannotSpendOrSaveResult() {
        var owner = owner(); fund(owner,600);
        var op = admit(owner, command(Code.image_generation,null));
        long fence = tx(() -> service.claim(op.snapshot().operationId(),0));
        long newerFence = tx(() -> service.claim(op.snapshot().operationId(),fence));
        assertThat(tx(() -> service.finish(op.snapshot().operationId(),fence,Outcome.captured))).isFalse();
        assertThatThrownBy(() -> tx(() -> {
            assertThat(service.finish(op.snapshot().operationId(),newerFence,Outcome.captured)).isTrue();
            jdbc.update("insert into billing_test_results values(?,?)", op.snapshot().operationId(), "valid result");
            throw new IllegalStateException("Domain commit failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(read(owner).reservedUnits()).isEqualTo(400);
        assertThat(read(owner).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from billing_test_results",Long.class)).isZero();
        tx(() -> service.finish(op.snapshot().operationId(),newerFence,Outcome.released));
        assertThat(read(owner).availableUnits()).isEqualTo(600);
        assertThat(read(owner).usedUnits()).isZero();
    }
    @Test void freeCpuNoAiOutcomeAndAnalysisCreateNoFinancialPosting() {
        var owner = owner();
        for (Code code : List.of(Code.cpu_only,Code.product_analysis)) {
            var op=admit(owner,command(code,null));
            assertThat(op.snapshot().amountUnits()).isZero();
            tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        }
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where wallet_id=?",Long.class,read(owner).id())).isZero();
        fund(owner,600);
        var op=admit(owner,command(Code.image_outpaint,null));
        tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.released));
        assertThat(read(owner).availableUnits()).isEqualTo(600);
    }
    @Test void grantReferenceAndSafeIntegerScaleAreEnforced() throws Exception {
        var owner = owner();
        assertThat(race(8,i -> { tx(() -> service.grantTrial(owner,owner.id())); return true; })).containsOnly(true);
        assertThat(read(owner).totalUnits()).isEqualTo(600);
        var other=owner();
        assertThatThrownBy(() -> tx(() -> service.grantTrial(other,owner.id()))).isInstanceOf(BillingFailure.class);
        var maxOwner = owner(); fund(maxOwner,Billing.MAX_UNITS);
        assertThatThrownBy(() -> Billing.add(read(maxOwner).totalUnits(),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("update billing_wallets set total_units=9007199254740992 where id=?",read(maxOwner).id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update billing_wallets set total_units=-1 where id=?",read(owner).id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update billing_wallets set reserved_units=601 where id=?",read(owner).id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var cents=owner(); fund(cents,601);
        var op=admit(cents,command(Code.image_generation,null)); tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        tx(() -> service.compensate(op.walletId(),debit(op),Billing.id("refund_"),1));
        assertThat(read(cents).totalUnits()).isEqualTo(202);
        assertThat(read(cents).usedUnits()).isEqualTo(399);
    }
    @Test void newPriceAppliesWithoutRestartAndReplayKeepsOriginalSnapshot() {
        var owner=owner(); fund(owner,600); var cmd=command(Code.image_generation,null); var op=admit(owner,cmd);
        String version=Billing.id("price_");
        tx(() -> {
            jdbc.update("insert into billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason) values(?,800,1,clock_timestamp(),'test revision')",version);
            jdbc.update("insert into billing_prices select ?,operation_code,case when operation_code in ('image_generation','image_entire_edit') then 500 else amount_units end from billing_prices where version_id='billing-2026-10-14-v1'",version);
            jdbc.update("update billing_price_versions set published_at=clock_timestamp() where id=?",version);
            jdbc.update("update billing_configuration set active_version=?",version);
            return null;
        });
        assertThat(service.prices().prices().get(Code.image_generation)).isEqualTo(500);
        assertThat(admit(owner,cmd).snapshot()).isEqualTo(op.snapshot());
        tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.released));
        assertThat(admit(owner,command(Code.image_generation,null)).snapshot().amountUnits()).isEqualTo(500);
        assertThatThrownBy(() -> jdbc.update("update billing_prices set amount_units=1 where version_id=?",version)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update billing_operations set amount_units=500 where id=?",op.snapshot().operationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        String incomplete=Billing.id("price_");
        jdbc.update("insert into billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason) values(?,600,1,clock_timestamp(),'invalid')",incomplete);
        assertThatThrownBy(() -> jdbc.update("update billing_configuration set active_version=?",incomplete)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void cursorFiltersSnapshotDateBoundsOrderingAndWalletBinding() {
        var owner=owner(); fund(owner,2000);
        for (int i=0;i<4;i++) { var op=admit(owner,command(Code.image_region_edit,null)); tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
            tx(() -> service.compensate(op.walletId(),debit(op),Billing.id("refund_"),1)); }
        var first=tx(() -> service.history(owner,new HistoryQuery(null,null,"oldest",2,null)));
        assertThat(first.hasMore()).isTrue();
        var another=admit(owner,command(Code.image_region_edit,null)); tx(() -> service.finish(another.snapshot().operationId(),0,Outcome.captured));
        var second=tx(() -> service.history(owner,new HistoryQuery(null,null,"oldest",2,first.nextCursor())));
        var third=tx(() -> service.history(owner,new HistoryQuery(null,null,"oldest",2,second.nextCursor())));
        assertThat(second.items()).hasSize(2); assertThat(third.items()).hasSize(1);
        assertThat(third.hasMore()).isFalse(); assertThat(third.nextCursor()).isNull();
        assertThat(first.items().getLast().sequence()).isLessThan(second.items().getFirst().sequence());
        assertThat(third.items().getFirst().id()).isNotEqualTo(debit(another));
        assertThatThrownBy(() -> tx(() -> service.history(owner,new HistoryQuery(null,null,"newest",2,first.nextCursor())))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(() -> tx(() -> service.history(owner(),new HistoryQuery(null,null,"oldest",2,first.nextCursor())))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(() -> tx(() -> service.history(owner,new HistoryQuery(null,null,"oldest",3,first.nextCursor())))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(() -> tx(() -> service.history(owner,new HistoryQuery(null,null,"oldest",2,"bad")))).isInstanceOf(BillingFailure.class);
        assertThatThrownBy(() -> tx(() -> service.history(owner,new HistoryQuery(LocalDate.of(2026,1,2),LocalDate.of(2026,1,1),"newest",10,null)))).isInstanceOf(BillingFailure.class);
        var day=LocalDate.now(ZoneId.of("Europe/Moscow"));
        assertThat(tx(() -> service.history(owner,new HistoryQuery(day,day,"newest",100,null))).items()).hasSize(6);
        assertThat(tx(() -> service.history(owner,new HistoryQuery(day.minusDays(1),day.minusDays(1),"newest",10,null))).items()).isEmpty();
    }
    @Test void ownershipAliasKeepsWalletReserveAndReplayStable() {
        var trial = new Owner("trial",Billing.id("trial_")); fund(trial,600);
        var cmd=command(Code.image_generation,null); var op=admit(trial,cmd); var user=owner();
        tx(() -> { service.attachOwner(op.walletId(),user); return null; });
        assertThat(read(user).id()).isEqualTo(op.walletId());
        assertThat(read(user).reservedUnits()).isEqualTo(400);
        assertThat(admit(user,cmd).snapshot()).isEqualTo(op.snapshot());
        tx(() -> service.grantTrial(user,user.id()));
        assertThat(read(user).totalUnits()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where wallet_id=?",Long.class,op.walletId())).isEqualTo(1);
    }
    @Test void timestampsAtMoscowDayBoundariesAndTiesFollowWalletSequence() {
        var owner=owner(); fund(owner,2000); String wallet=read(owner).id();
        // Controlled future posting clock avoids dependence on the runner's local date/time.
        for (String instant:List.of("2030-10-13T20:59:59.999999Z","2030-10-13T21:00:00Z",
                "2030-10-14T20:59:59.999999Z","2030-10-14T21:00:00Z")) {
            jdbc.update("update billing_wallets set last_posted_at=? where id=?",java.sql.Timestamp.from(Instant.parse(instant)),wallet);
            var op=admit(owner,command(Code.image_region_edit,null)); tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        }
        var date=LocalDate.of(2030,10,14);
        var selected=tx(() -> service.history(owner,new HistoryQuery(date,date,"oldest",10,null)));
        assertThat(selected.items()).hasSize(2);
        assertThat(selected.items().getFirst().occurredAt()).isEqualTo(Instant.parse("2030-10-13T21:00:00Z"));
        assertThat(selected.items().getLast().occurredAt()).isEqualTo(Instant.parse("2030-10-14T20:59:59.999999Z"));
        // Same immutable occurredAt is ordered by the internal wallet sequence.
        var op=admit(owner,command(Code.image_region_edit,null)); tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        var tied=tx(() -> service.history(owner,new HistoryQuery(date.plusDays(1),date.plusDays(1),"newest",1,null)));
        var next=tx(() -> service.history(owner,new HistoryQuery(date.plusDays(1),date.plusDays(1),"newest",1,tied.nextCursor())));
        assertThat(tied.items().getFirst().occurredAt()).isEqualTo(next.items().getFirst().occurredAt());
        assertThat(tied.items().getFirst().sequence()).isGreaterThan(next.items().getFirst().sequence());
    }
    @Test void zeroTrialAllowanceStillCreatesAnImmutableGrantReceipt() {
        var owner=owner();
        tx(() -> store.trialGrant(store.lockWallet(owner),owner.id(),0));
        tx(() -> store.trialGrant(store.lockWallet(owner),owner.id(),600));
        assertThat(read(owner).totalUnits()).isZero();
        assertThat(tx(() -> service.history(owner,new HistoryQuery(null,null,"newest",10,null))).items()).isEmpty();
    }
    @Test void differentRefundReferencesRaceAgainstTheSameOriginalDebit() throws Exception {
        var owner=owner(); fund(owner,600);
        var op=admit(owner,command(Code.image_generation,null)); tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        String debit=debit(op);
        var results=race(8,i -> {
            try { tx(() -> service.compensate(op.walletId(),debit,Billing.id("refund_"),200)); return true; }
            catch(BillingFailure failure) { assertThat(failure.status()).isEqualTo(400); return false; }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(2);
        assertThat(read(owner).totalUnits()).isEqualTo(600); assertThat(read(owner).usedUnits()).isZero();
    }
    @Test void entitlementGuardSerializesDifferentWalletsForOneStableSubject() throws Exception {
        var owners=java.util.stream.IntStream.range(0,8).mapToObj(i -> owner()).toList();
        String subject=Billing.id("opaque_");
        var results=race(8,i -> {
            try { admit(owners.get(i),command(Code.image_entire_edit,subject)); return true; }
            catch(BillingFailure failure) { assertThat(failure.code()).isEqualTo("BILLING_ENTITLEMENT_BUSY"); return false; }
        });
        assertThat(results).containsOnlyOnce(true);
    }
    @Test void mergeAndTerminalWorkerRaceRetainsMoneyAndExactlyOneDebit() throws Exception {
        var trial = new Owner("trial",Billing.id("trial_")); var user=owner();
        fund(trial,600);fund(user,600);
        var cmd=command(Code.image_generation,null);var op=admit(trial,cmd);
        assertThat(race(2,i -> tx(() -> {
            if(i==0) service.convertOwner(trial,user);
            else service.finish(op.snapshot().operationId(),0,Outcome.captured);
            return true;
        }))).containsOnly(true);
        assertThat(read(user).totalUnits()).isEqualTo(800);
        assertThat(read(user).usedUnits()).isEqualTo(400);
        assertThat(read(user).reservedUnits()).isZero();
        assertThat(admit(user,cmd).snapshot()).isEqualTo(op.snapshot());
        assertThat(tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from billing_ledger where operation_id=?",Long.class,op.snapshot().operationId())).isEqualTo(1);
        tx(() -> service.compensate(op.walletId(),debit(op),Billing.id("refund_"),400));
        assertThat(read(user).totalUnits()).isEqualTo(1200);
        assertThat(read(user).usedUnits()).isZero();
    }
    @Test void mergedStreamsKeepIndependentConflictingReplayKeysAndExplicitLineage() {
        var trial=new Owner("trial",Billing.id("trial_"));var user=owner();fund(trial,600);fund(user,600);
        var cmd=command("same-key",Code.image_region_edit,null);
        var secondCmd=new Command(cmd.namespace(),cmd.key(),1,Map.of(1,"b".repeat(64)),cmd.code(),null,cmd.jobNamespace(),Billing.id("job_"));
        var first=admit(trial,cmd);var second=admit(user,secondCmd);
        tx(() -> {service.convertOwner(trial,user);return null;});
        assertThat(admit(user,cmd).snapshot()).isEqualTo(first.snapshot());
        assertThat(admit(user,secondCmd).snapshot()).isEqualTo(second.snapshot());
        // Identical digests in separate prior streams require a trusted explicit wallet lineage.
        var a=command("ambiguous",Code.image_region_edit,null);
        var b=new Command(a.namespace(),a.key(),1,a.digests(),a.code(),null,a.jobNamespace(),Billing.id("job_"));
        var otherTrial=new Owner("trial",Billing.id("trial_"));fund(otherTrial,600);
        var left=admit(user,a);var right=admit(otherTrial,b);
        tx(() -> {service.convertOwner(otherTrial,user);return null;});
        assertThatThrownBy(() -> admit(user,a)).isInstanceOf(BillingFailure.class);
        var scoped=new Command(a.namespace(),a.key(),1,a.digests(),a.code(),null,a.jobNamespace(),a.jobId(),right.walletId());
        assertThat(admit(user,scoped).snapshot()).isEqualTo(right.snapshot());
        var outside=new Command(a.namespace(),a.key(),1,a.digests(),a.code(),null,a.jobNamespace(),a.jobId(),read(owner()).id());
        assertThatThrownBy(() -> admit(user,outside)).isInstanceOf(BillingFailure.class);
        assertThat(jdbc.queryForObject("select count(*) from billing_operations where id in (?,?)",Long.class,
                left.snapshot().operationId(),right.snapshot().operationId())).isEqualTo(2);
    }
    @Test void mergeHistoryKeepsImmutableBalancesAndCreatesConsistentNewPaginationSnapshot() {
        var trial=new Owner("trial",Billing.id("trial_"));var user=owner();fund(trial,600);fund(user,600);
        for(var owner:List.of(trial,user)) {
            var op=admit(owner,command(Code.image_region_edit,null));tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        }
        var oldCursor=tx(() -> service.history(user,new HistoryQuery(null,null,"oldest",1,null)));
        var immutable=jdbc.queryForList("select * from billing_ledger where wallet_id in (?,?) order by id",read(trial).id(),read(user).id());
        tx(() -> {service.convertOwner(trial,user);return null;});
        assertThatThrownBy(() -> tx(() -> service.history(user,new HistoryQuery(null,null,"oldest",1,oldCursor.nextCursor())))).isInstanceOf(BillingFailure.class);
        var first=tx(() -> service.history(user,new HistoryQuery(null,null,"oldest",2,null)));
        var op=admit(user,command(Code.image_region_edit,null));tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured));
        var second=tx(() -> service.history(user,new HistoryQuery(null,null,"oldest",2,first.nextCursor())));
        assertThat(first.items()).hasSize(2);assertThat(second.items()).hasSize(2);assertThat(second.hasMore()).isFalse();
        assertThat(second.items()).extracting(Entry::id).doesNotContain(debit(op));
        assertThat(jdbc.queryForList("select * from billing_ledger where id in (select posting_id from billing_ledger_order where wallet_id=? and sequence<=4) order by id",read(user).id())).isEqualTo(immutable);
    }
    @Test void conversionFailureRollsBackBalancesAliasesAndPendingOperations() {
        var trial=new Owner("trial",Billing.id("trial_"));var user=owner();fund(trial,600);fund(user,600);
        var cmd=command(Code.image_generation,null);var op=admit(trial,cmd);
        String trialWallet=read(trial).id(),userWallet=read(user).id();
        assertThatThrownBy(() -> tx(() -> {service.convertOwner(trial,user);throw new IllegalStateException("Workspace transfer failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(read(trial).id()).isEqualTo(trialWallet);assertThat(read(user).id()).isEqualTo(userWallet);
        assertThat(read(trial).reservedUnits()).isEqualTo(400);assertThat(read(user).reservedUnits()).isZero();
        assertThat(admit(trial,cmd).snapshot()).isEqualTo(op.snapshot());
        assertThat(read(trial).totalUnits()).isEqualTo(600);assertThat(read(user).totalUnits()).isEqualTo(600);
    }

    @Test void conversionMustAcquireTopologyGuardBeforeAnyWalletRead() {
        var trial=new Owner("trial",Billing.id("trial_"));var user=owner();
        fund(trial,600);fund(user,600);
        assertThatThrownBy(() -> tx(() -> {
            service.wallet(user);
            service.convertOwner(trial,user);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Conversion must precede");
        assertThat(read(trial).totalUnits()).isEqualTo(600);
        assertThat(read(user).totalUnits()).isEqualTo(600);
        // Transaction-local guard bookkeeping is cleared after rollback.
        tx(() -> {service.convertOwner(trial,user);return null;});
        assertThat(read(user).totalUnits()).isEqualTo(1200);
    }

    @Test void concurrentPricePublicationAndAdmissionsAlwaysUseOneCompleteSnapshot() throws Exception {
        var owner=owner();fund(owner,5000);
        String version=Billing.id("price_");
        var operations=new java.util.concurrent.ConcurrentLinkedQueue<Operation>();
        assertThat(race(9,i -> {
            if(i==0) tx(() -> {
                jdbc.update("insert into billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason) values(?,600,1,clock_timestamp(),'concurrent publication test')",version);
                jdbc.update("insert into billing_prices select ?,operation_code,case when operation_code='image_region_edit' then 300 else amount_units end from billing_prices where version_id='billing-2026-10-14-v1'",version);
                jdbc.update("update billing_price_versions set published_at=clock_timestamp() where id=?",version);
                jdbc.update("update billing_configuration set active_version=?",version);return null;
            });
            else operations.add(admit(owner,command(Code.image_region_edit,null)));
            return true;
        })).containsOnly(true);
        assertThat(operations).hasSize(8);
        long expected=0;
        for(var op:operations) {
            long price=op.snapshot().priceVersion().equals(version)?300:200;
            assertThat(op.snapshot().amountUnits()).isEqualTo(price);expected+=price;
            assertThat(tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured))).isTrue();
            assertThat(tx(() -> service.finish(op.snapshot().operationId(),0,Outcome.captured))).isTrue();
        }
        assertThat(read(owner).totalUnits()).isEqualTo(5000-expected);
        assertThat(read(owner).reservedUnits()).isZero();assertThat(read(owner).usedUnits()).isEqualTo(expected);
    }

}
