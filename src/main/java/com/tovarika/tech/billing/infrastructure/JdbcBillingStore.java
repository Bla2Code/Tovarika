package com.tovarika.tech.billing.infrastructure;

import com.tovarika.tech.billing.application.BillingFailure;
import com.tovarika.tech.billing.application.BillingStore;
import com.tovarika.tech.billing.domain.Billing;
import com.tovarika.tech.billing.domain.Billing.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

@Repository
public class JdbcBillingStore implements BillingStore {
    private static final ZoneId HISTORY_ZONE = ZoneId.of("Europe/Moscow");
    private final JdbcTemplate jdbc;
    public JdbcBillingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Billing requires caller transaction");
    }
    /** Identity lock precedes every wallet lock; conversion locks all identities in lexical order. */
    private void lockOwner(Owner owner) {
        transaction();
        topology(false);
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 7202))", owner.kind() + ":" + owner.id());
    }
    public Wallet lockWallet(Owner owner) { return lockWallet(walletId(owner)); }
    private String walletId(Owner owner) {
        lockOwner(owner);
        var walletIds = jdbc.queryForList("""
                select w.id from billing_wallets w join billing_owners o on o.account_id=w.account_id
                where o.kind=? and o.subject=?
                """, String.class, owner.kind(), owner.id());
        if (walletIds.isEmpty()) {
            String accountId = Billing.id("bac_");
            String walletId = Billing.id("wal_");
            jdbc.update("insert into billing_accounts(id) values(?)", accountId);
            jdbc.update("insert into billing_wallets(id,account_id) values(?,?)", walletId, accountId);
            jdbc.update("insert into billing_wallet_links values(?,?)", walletId, walletId);
            jdbc.update("insert into billing_owners(kind,subject,account_id) values(?,?,?)", owner.kind(), owner.id(), accountId);
            return walletId;
        }
        return walletIds.getFirst();
    }
    public Wallet lockWallet(String id) {
        transaction();
        topology(false);
        String root = canonical(id);
        // Lock every retained financial stream in stable order, including the root.
        jdbc.queryForList("""
                select w.id from billing_wallets w join billing_wallet_links l on l.wallet_id=w.id
                where l.canonical_wallet_id=? order by w.id for update of w
                """, root);
        return jdbc.queryForObject("select * from billing_wallets where id=?", (r,n) -> wallet(r), root);
    }
    public Instant now() { return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant(); }
    public Catalog catalog() {
        // One SQL statement: active version and all prices share a PostgreSQL snapshot.
        var rows = jdbc.queryForList("""
                select v.*, p.operation_code, p.amount_units from billing_configuration c
                join billing_price_versions v on v.id=c.active_version
                join billing_prices p on p.version_id=v.id where c.singleton=true
                """);
        if (rows.isEmpty()) throw new IllegalStateException("Billing configuration missing");
        var first = rows.getFirst();
        var prices = new EnumMap<Code, Long>(Code.class);
        rows.forEach(row -> prices.put(Code.valueOf((String) row.get("operation_code")), ((Number) row.get("amount_units")).longValue()));
        return new Catalog((String) first.get("id"), ((Number) first.get("trial_allowance_units")).longValue(),
                ((Number) first.get("free_entire_successes")).intValue(), prices,
                ((Timestamp) first.get("effective_from")).toInstant());
    }
    public Optional<Operation> receipt(String walletId, String namespace, String key) {
        var receipts = jdbc.query("""
                select o.* from billing_operations o join billing_wallet_links l on l.wallet_id=o.wallet_id
                where l.canonical_wallet_id=? and o.namespace=? and o.idempotency_key=?
                """, (r,n) -> operation(r), canonical(walletId), namespace, key);
        if (receipts.size() > 1) throw BillingFailure.conflict();
        return receipts.stream().findFirst();
    }
    public Optional<Operation> receipt(String walletId, Command command) {
        if (command.originWalletId() != null && !canonical(command.originWalletId()).equals(walletId))
            throw BillingFailure.conflict();
        var rows = jdbc.query("""
                select o.* from billing_operations o join billing_wallet_links l on l.wallet_id=o.wallet_id
                where l.canonical_wallet_id=? and o.namespace=? and o.idempotency_key=?
                """, (r,n) -> operation(r), walletId, command.namespace(), command.key());
        if (command.originWalletId() != null) {
            rows = rows.stream().filter(o -> o.walletId().equals(command.originWalletId())).toList();
            // Scoped lineage is for replay only: never create a replacement in a different stream.
            if (rows.isEmpty()) throw BillingFailure.conflict();
        }
        if (rows.size() > 1) {
            var matching = rows.stream().filter(o -> o.snapshot().operationCode() == command.code() &&
                    o.digest().equals(command.digests().get(o.digestVersion()))).toList();
            if (matching.size() != 1) throw BillingFailure.conflict();
            return Optional.of(matching.getFirst());
        }
        return rows.stream().findFirst();
    }
    public Operation operation(String id) {
        return jdbc.queryForObject("select * from billing_operations where id=?", (r,n) -> operation(r), id);
    }
    public Admission admit(Wallet wallet, Command command) {
        transaction();
        var prior = receipt(wallet.id(), command);
        if (prior.isPresent()) {
            var operation = prior.get();
            if (operation.snapshot().operationCode() != command.code() ||
                    !operation.digest().equals(command.digests().get(operation.digestVersion())))
                throw BillingFailure.conflict();
            return new Admission(operation, true);
        }
        var catalog = catalog();
        Funding funding = Funding.free_operation;
        long amount = catalog.prices().get(command.code());
        String subject = null;
        if (command.entitlementSubject() != null) {
            jdbc.update("insert into billing_entitlements(code,subject) values('free_entire',?) on conflict do nothing",
                    command.entitlementSubject());
            var right = jdbc.queryForMap("select * from billing_entitlements where code='free_entire' and subject=? for update",
                    command.entitlementSubject());
            if (right.get("reserved_by") != null)
                throw new BillingFailure(409, "BILLING_ENTITLEMENT_BUSY", "Free entire result is reserved");
            if (right.get("consumed_by") == null) {
                funding = Funding.free_entitlement;
                subject = command.entitlementSubject();
                amount = 0;
            }
        }
        if (amount > 0) {
            funding = Funding.wallet;
            if (wallet.availableUnits() < amount) throw new BillingFailure(402, "WALLET_INSUFFICIENT_FUNDS",
                    "Недостаточно Товариков", Map.of("unit", "tovarika_cent", "requiredUnits", amount,
                    "availableUnits", wallet.availableUnits(), "operationCode", command.code().name(), "priceVersion", catalog.version()));
        }
        String id = Billing.id("bop_");
        jdbc.update("""
                insert into billing_operations(id,wallet_id,namespace,idempotency_key,digest_version,digest,
                operation_code,price_version,amount_units,funding,entitlement_subject,job_namespace,job_id)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, wallet.id(), command.namespace(), command.key(), command.digestVersion(),
                command.digests().get(command.digestVersion()), command.code().name(), catalog.version(), amount,
                funding.name(), subject, command.jobNamespace(), command.jobId());
        if (funding == Funding.wallet) {
            jdbc.update("insert into billing_reservations(operation_id,wallet_id,amount_units,state) values(?,?,?,'reserved')",
                    id, wallet.id(), amount);
            jdbc.update("update billing_wallets set reserved_units=reserved_units+?,updated_at=greatest(updated_at,clock_timestamp()) where id=?",
                    amount, wallet.id());
        } else if (funding == Funding.free_entitlement) {
            jdbc.update("update billing_entitlements set reserved_by=? where code='free_entire' and subject=?", id, subject);
        }
        return new Admission(operation(id), false);
    }
    public long claim(Operation operation, long expectedFence) {
        transaction();
        int changed = jdbc.update("""
                update billing_operations set fence=fence+1 where id=? and state='reserved' and fence=?
                """, operation.snapshot().operationId(), expectedFence);
        if (changed != 1) throw new IllegalStateException("Stale billing claim");
        return Math.addExact(expectedFence, 1);
    }
    public boolean finish(Wallet wallet, Operation operation, long fence, Outcome outcome) {
        transaction();
        // Caller holds wallet lock, so the refreshed state/fence cannot change concurrently.
        if (operation.fence() != fence) return false;
        if (!operation.state().equals("reserved")) return operation.state().equals(outcome.name());
        var snapshot = operation.snapshot();
        if (snapshot.funding() == Funding.free_entitlement) {
            var right = jdbc.queryForMap("select * from billing_entitlements where code='free_entire' and subject=? for update",
                    operation.subject());
            if (!snapshot.operationId().equals(right.get("reserved_by"))) throw new IllegalStateException("Entitlement invariant failed");
            jdbc.update("update billing_entitlements set reserved_by=null,consumed_by=? where code='free_entire' and subject=?",
                    outcome == Outcome.captured ? snapshot.operationId() : null, operation.subject());
        } else if (snapshot.funding() == Funding.wallet) {
            long amount = snapshot.amountUnits();
            if (outcome == Outcome.captured) {
                long total = Billing.units(wallet.totalUnits() - amount);
                long used = Billing.add(wallet.usedUnits(), amount);
                // Remove reserve first, debit posting then adjusts total in the same transaction.
                jdbc.update("update billing_wallets set reserved_units=reserved_units-?,used_units=? where id=?", amount, used, wallet.id());
                post(wallet, "ai_debit", amount, total, "debit:" + snapshot.operationId(), snapshot.operationId(),
                        snapshot.operationCode(), null);
            } else {
                jdbc.update("update billing_wallets set reserved_units=reserved_units-?,updated_at=greatest(updated_at,clock_timestamp()) where id=?",
                        amount, wallet.id());
            }
            jdbc.update("update billing_reservations set state=? where operation_id=?", outcome.name(), snapshot.operationId());
        }
        jdbc.update("update billing_operations set state=?,finished_at=clock_timestamp() where id=?", outcome.name(), snapshot.operationId());
        return true;
    }
    public String trialGrant(Wallet wallet, String originalIdentity, long amount) {
        transaction();
        if (originalIdentity == null || originalIdentity.isBlank() || originalIdentity.length() > 200) throw BillingFailure.validation();
        Billing.units(amount);
        String reference = "trial:" + originalIdentity;
        // Global reference guard prevents a duplicate grant even on another wallet/alias.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,7203))", reference);
        var prior = jdbc.queryForList("select * from billing_trial_grants where reference=?", reference).stream().findFirst();
        if (prior.isPresent()) {
            var row = prior.get();
            if (!canonical((String) row.get("wallet_id")).equals(wallet.id()))
                throw BillingFailure.conflict();
            return (String) row.get("posting_id"); // Replay ignores subsequently changed trial configuration.
        }
        var accountGrant = jdbc.queryForList("""
                select g.posting_id from billing_trial_grants g join billing_wallet_links l on l.wallet_id=g.wallet_id
                where l.canonical_wallet_id=?
                """, wallet.id());
        if (!accountGrant.isEmpty()) return (String)accountGrant.getFirst().get("posting_id");
        String postingId = amount == 0 ? null : post(wallet, "trial_grant", amount, Billing.add(wallet.totalUnits(), amount), reference, null, null, null);
        jdbc.update("insert into billing_trial_grants(reference,wallet_id,amount_units,posting_id) values(?,?,?,?)", reference, wallet.id(), amount, postingId);
        return postingId;
    }
    public String compensate(Wallet wallet, String debitId, String reference, long amount) {
        transaction();
        if (reference == null || reference.isBlank() || reference.length() > 200 || amount <= 0) throw BillingFailure.validation();
        Billing.units(amount);
        String uniqueReference = "compensation:" + reference;
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,7203))", uniqueReference);
        var prior = posting(uniqueReference);
        if (prior.isPresent()) {
            var row = prior.get();
            if (!canonical((String) row.get("wallet_id")).equals(wallet.id()) || !debitId.equals(row.get("original_debit_id")) ||
                    ((Number) row.get("amount_units")).longValue() != amount) throw BillingFailure.conflict();
            return (String) row.get("id");
        }
        var debits = jdbc.queryForList("""
                select d.amount_units from billing_ledger d join billing_wallet_links l on l.wallet_id=d.wallet_id
                where d.id=? and l.canonical_wallet_id=? and d.kind='ai_debit'
                """, debitId, wallet.id());
        if (debits.isEmpty()) throw BillingFailure.validation();
        long debit = ((Number) debits.getFirst().get("amount_units")).longValue();
        long compensated = jdbc.queryForObject("select coalesce(sum(amount_units),0) from billing_ledger where original_debit_id=?",
                Long.class, debitId);
        if (amount > debit - compensated) throw BillingFailure.validation();
        long total = Billing.add(wallet.totalUnits(), amount);
        jdbc.update("update billing_wallets set used_units=used_units-? where id=?", amount, wallet.id());
        return post(wallet, "compensation", amount, total, uniqueReference, null, null, debitId);
    }
    private Optional<Map<String, Object>> posting(String reference) {
        return jdbc.queryForList("select * from billing_ledger where reference=?", reference).stream().findFirst();
    }
    private String post(Wallet wallet, String kind, long amount, long total, String reference,
                        String operationId, Code code, String originalDebit) {
        return post(wallet,kind,amount,total,reference,operationId,code,originalDebit,null);
    }
    private String post(Wallet wallet,String kind,long amount,long total,String reference,String operationId,Code code,String originalDebit,Timestamp activityAt) {
        String id = Billing.id("wtx_");
        var update = jdbc.queryForMap("""
                update billing_wallets set total_units=?,ledger_sequence=ledger_sequence+1,
                last_posted_at=greatest(coalesce(last_posted_at,'-infinity'::timestamptz),clock_timestamp()),
                updated_at=greatest(updated_at,clock_timestamp()) where id=? returning ledger_sequence,last_posted_at
                """, total, wallet.id());
        String stream = operationId != null ? operation(operationId).walletId() :
                originalDebit != null ? jdbc.queryForObject("select wallet_id from billing_ledger where id=?", String.class, originalDebit) : wallet.id();
        long streamSequence = stream.equals(wallet.id()) ? ((Number) update.get("ledger_sequence")).longValue() :
                jdbc.queryForObject("select coalesce(max(sequence),0)+1 from billing_ledger where wallet_id=?", Long.class, stream);
        jdbc.update("""
                insert into billing_ledger(id,wallet_id,sequence,kind,amount_units,balance_after_units,
                reference,operation_id,operation_code,original_debit_id,occurred_at) values(?,?,?,?,?,?,?,?,?,?,?)
                """, id, stream, streamSequence, kind, amount, total, reference, operationId,
                code == null ? null : code.name(), originalDebit, activityAt==null?update.get("last_posted_at"):activityAt);
        jdbc.update("insert into billing_ledger_order values(?,?,?)", id, wallet.id(), update.get("ledger_sequence"));
        return id;
    }
    private void materializeActivities(Wallet wallet) {
        var rows=jdbc.queryForList("""
                select a.* from billing_activity_outbox a
                join billing_owners o on o.kind=a.owner_kind and o.subject=a.owner_subject
                join billing_wallets w on w.account_id=o.account_id
                join billing_wallet_links l on l.wallet_id=w.id
                where l.canonical_wallet_id=? and a.posting_id is null
                order by a.occurred_at,a.reference for update of a
                """,wallet.id());
        for(var row:rows) {
            String operation=(String)row.get("billing_operation_id");
            var debits=operation==null?List.<String>of():jdbc.queryForList("select id from billing_ledger where operation_id=? and kind='ai_debit'",String.class,operation);
            String posting;
            if(!debits.isEmpty()) posting=debits.getFirst();
            else {
                posting=post(wallet,"free_operation",0,wallet.totalUnits(),"free:"+row.get("reference"),null,
                        Code.valueOf((String)row.get("operation_code")),null,(Timestamp)row.get("occurred_at"));
            }
            jdbc.update("update billing_activity_outbox set posting_id=? where reference=?",posting,row.get("reference"));
        }
    }
    public History history(Wallet wallet, HistoryQuery q) {
        transaction();
        if (q.limit() < 1 || q.limit() > 100 || !List.of("newest", "oldest").contains(q.sort()) ||
                (q.from() != null && q.to() != null && q.from().isAfter(q.to()))) throw BillingFailure.validation();
        materializeActivities(wallet);
        wallet=lockWallet(wallet.id());
        long upper = wallet.sequence();
        long last = q.sort().equals("newest") ? Math.addExact(upper, 1) : 0;
        Timestamp lastTime=null;
        if (q.cursor() != null) {
            if (!q.cursor().matches("bcur_[0-9a-f]{32}")) throw BillingFailure.validation();
            var cursors = jdbc.queryForList("select * from billing_history_cursors where id=? and expires_at>clock_timestamp()", q.cursor());
            if (cursors.isEmpty()) throw BillingFailure.validation();
            var c = cursors.getFirst();
            if (!wallet.id().equals(c.get("wallet_id")) || !q.sort().equals(c.get("sort")) ||
                    q.limit() != ((Number)c.get("page_limit")).intValue() ||
                    !Objects.equals(q.from(), date(c.get("from_date"))) || !Objects.equals(q.to(), date(c.get("to_date"))))
                throw BillingFailure.validation();
            upper = ((Number)c.get("upper_sequence")).longValue();
            last = ((Number)c.get("last_sequence")).longValue();
            lastTime=jdbc.queryForObject("select d.occurred_at from billing_ledger_order h join billing_ledger d on d.id=h.posting_id where h.wallet_id=? and h.sequence=?",Timestamp.class,wallet.id(),last);
        }
        String order = q.sort().equals("newest") ? "desc" : "asc";
        String comparator = q.sort().equals("newest") ? "<" : ">";
        var args = new ArrayList<Object>(List.of(wallet.id(), upper));
        String sql = "select d.*, h.sequence as history_sequence from billing_ledger d join billing_ledger_order h on h.posting_id=d.id where h.wallet_id=? and kind in ('trial_grant','ai_debit','free_operation') and h.sequence<=?";
        if(lastTime!=null) { sql+=" and (d.occurred_at,h.sequence)"+comparator+"(?,?)";args.add(lastTime);args.add(last); }
        try {
            if (q.from() != null) { sql += " and occurred_at>=?"; args.add(Timestamp.from(q.from().atStartOfDay(HISTORY_ZONE).toInstant())); }
            if (q.to() != null) { sql += " and occurred_at<?"; args.add(Timestamp.from(q.to().plusDays(1).atStartOfDay(HISTORY_ZONE).toInstant())); }
        } catch (java.time.DateTimeException | IllegalArgumentException invalid) { throw BillingFailure.validation(); }
        sql += " order by d.occurred_at "+order+", h.sequence " + order + " limit ?";
        args.add(q.limit() + 1);
        var rows = jdbc.query(sql, (r,n) -> new Entry(r.getString("id"), r.getString("kind"),
                r.getString("operation_code") == null ? null : Code.valueOf(r.getString("operation_code")),
                r.getLong("amount_units"), r.getLong("balance_after_units"), r.getTimestamp("occurred_at").toInstant(), r.getLong("history_sequence")), args.toArray());
        boolean more = rows.size() > q.limit();
        var items = List.copyOf(rows.subList(0, Math.min(q.limit(), rows.size())));
        String next = null;
        if (more) {
            next = Billing.id("bcur_");
            jdbc.update("""
                    insert into billing_history_cursors(id,wallet_id,from_date,to_date,sort,page_limit,last_sequence,upper_sequence)
                    values(?,?,?,?,?,?,?,?)
                    """, next, wallet.id(), q.from(), q.to(), q.sort(), q.limit(), items.getLast().sequence(), upper);
        }
        return new History(items, more, next);
    }
    private java.time.LocalDate date(Object value) { return value == null ? null : ((java.sql.Date)value).toLocalDate(); }
    public void attachOwner(Wallet wallet, Owner owner) {
        // Identity locks must already be held before calling lockWallet() in conversion.
        // Use prepareOwners() before taking any wallet locks.
        transaction();
        var account = jdbc.queryForObject("select account_id from billing_wallets where id=?", String.class, wallet.id());
        jdbc.update("insert into billing_owners(kind,subject,account_id) values(?,?,?) on conflict do nothing", owner.kind(), owner.id(), account);
        String attached = jdbc.queryForObject("select account_id from billing_owners where kind=? and subject=?", String.class, owner.kind(), owner.id());
        if (!account.equals(attached)) throw new IllegalStateException("Owner has a different financial account; use conversion merge");
    }
    public void prepareOwners(List<Owner> owners) {
        owners.stream().distinct().sorted(Comparator.comparing(o -> o.kind() + ":" + o.id())).forEach(this::lockOwner);
    }
    private void topology(boolean exclusive) {
        transaction();
        Boolean held = (Boolean) TransactionSynchronizationManager.getResource(this);
        if (held != null) {
            if (exclusive && !held) throw new IllegalStateException("Conversion must precede other billing calls");
            return;
        }
        // Shared by normal transitions; exclusive only during rare ownership merges.
        jdbc.queryForList(exclusive ? "select pg_advisory_xact_lock(7204, 1)" :
                "select pg_advisory_xact_lock_shared(7204, 1)");
        TransactionSynchronizationManager.bindResource(this, exclusive);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(JdbcBillingStore.this);
            }
        });
    }
    private String canonical(String id) {
        return jdbc.queryForObject("select canonical_wallet_id from billing_wallet_links where wallet_id=?", String.class, id);
    }
    public long trialAllowance(String walletId) {
        return jdbc.queryForObject("""
                select coalesce(sum(g.amount_units),0) from billing_trial_grants g
                join billing_wallet_links l on l.wallet_id=g.wallet_id where l.canonical_wallet_id=?
                """, Long.class, canonical(walletId));
    }
    public void convertOwner(Owner trial, Owner user) {
        topology(true); // Must be the first billing call in this transaction: no shared-lock upgrade.
        prepareOwners(List.of(trial, user));
        // Exclusive topology access drains all normal transitions before touching either wallet.
        String sourceId = walletId(trial);
        var targetIds = jdbc.queryForList("""
                select w.id from billing_wallets w join billing_owners o on o.account_id=w.account_id
                where o.kind=? and o.subject=?
                """, String.class, user.kind(), user.id());
        if (targetIds.isEmpty()) {
            var source = lockWallet(sourceId);
            trialGrant(source, trial.kind() + ":" + trial.id(), catalog().trialAllowanceUnits());
            attachOwner(source, user);
            return;
        }
        jdbc.queryForList("""
                select w.id from billing_wallets w join billing_wallet_links l on l.wallet_id=w.id
                where l.canonical_wallet_id in (?,?) order by w.id for update of w
                """, canonical(sourceId), canonical(targetIds.getFirst()));
        var source = lockWallet(sourceId);
        var target = lockWallet(targetIds.getFirst());
        if (source.id().equals(target.id())) return;
        // No mint during merge if either stream already received its trial, including a zero grant.
        boolean granted = jdbc.queryForObject("""
                select exists(select 1 from billing_trial_grants g join billing_wallet_links l on l.wallet_id=g.wallet_id
                where l.canonical_wallet_id in (?,?))
                """, Boolean.class, source.id(), target.id());
        if (!granted) {
            trialGrant(source, trial.kind() + ":" + trial.id(), catalog().trialAllowanceUnits());
            source = lockWallet(source.id());
        }
        long total = Billing.add(source.totalUnits(), target.totalUnits());
        long reserved = Billing.add(source.reservedUnits(), target.reservedUnits());
        long used = Billing.add(source.usedUnits(), target.usedUnits());
        jdbc.update("""
                update billing_wallets set total_units=?,reserved_units=?,used_units=?,
                ledger_sequence=?,updated_at=greatest(updated_at,clock_timestamp()),
                last_posted_at=greatest(last_posted_at,(select last_posted_at from billing_wallets where id=?)) where id=?
                """, total, reserved, used, Math.addExact(source.sequence(),target.sequence()), source.id(), target.id());
        jdbc.update("update billing_wallets set total_units=0,reserved_units=0,used_units=0 where id=?", source.id());
        // Existing ledger rows/snapshots/grants/reservations remain immutable in their original streams.
        jdbc.update("update billing_wallet_links set canonical_wallet_id=? where canonical_wallet_id=?", target.id(), source.id());
        jdbc.update("""
                with ordered as (
                    select h.posting_id,row_number() over(order by d.occurred_at,d.wallet_id,d.sequence,d.id) as sequence
                    from billing_ledger_order h join billing_ledger d on d.id=h.posting_id
                    where h.wallet_id in (?,?)
                ) update billing_ledger_order h set wallet_id=?,sequence=o.sequence from ordered o where h.posting_id=o.posting_id
                """, source.id(), target.id(), target.id());
        // A cursor predating a merge binds a different stream snapshot; reject it explicitly.
        jdbc.update("delete from billing_history_cursors where wallet_id in (?,?)", source.id(), target.id());
    }
    private Wallet wallet(ResultSet r) throws SQLException {
        return new Wallet(r.getString("id"), r.getLong("total_units"), r.getLong("reserved_units"), r.getLong("used_units"),
                r.getLong("ledger_sequence"), r.getTimestamp("updated_at").toInstant());
    }
    private Operation operation(ResultSet r) throws SQLException {
        return new Operation(new Snapshot(r.getString("id"), Code.valueOf(r.getString("operation_code")), r.getString("price_version"),
                r.getLong("amount_units"), Funding.valueOf(r.getString("funding"))), r.getString("wallet_id"), r.getString("namespace"),
                r.getString("idempotency_key"), r.getInt("digest_version"), r.getString("digest"), r.getString("entitlement_subject"),
                r.getString("job_namespace"), r.getString("job_id"), r.getString("state"), r.getLong("fence"));
    }
}
