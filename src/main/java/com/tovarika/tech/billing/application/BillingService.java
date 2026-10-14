package com.tovarika.tech.billing.application;

import com.tovarika.tech.billing.domain.Billing.*;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    private final BillingStore store;
    public BillingService(BillingStore store) { this.store = store; }

    @Transactional public Wallet wallet(Owner owner) { return store.lockWallet(owner); }
    @Transactional public Summary summary(Owner owner) {
        Wallet wallet = store.lockWallet(owner);
        var now = store.now();
        return new Summary(wallet, now.isBefore(wallet.updatedAt()) ? wallet.updatedAt() : now);
    }
    @Transactional public History history(Owner owner, HistoryQuery query) {
        return store.history(store.lockWallet(owner), query);
    }
    @Transactional(readOnly = true) public Catalog prices() { return store.catalog(); }

    /** Caller creates job/placeholder after this call in the SAME transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Admission admit(Owner owner, Command command) {
        return store.admit(store.lockWallet(owner), command);
    }
    /** Invoke before price/funds/domain busy validation; no new command may bypass admit(). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Operation> replay(Owner owner, Command command) {
        var wallet = store.lockWallet(owner);
        return store.receipt(wallet.id(), command).map(operation -> {
            if (operation.snapshot().operationCode() != command.code() ||
                    !operation.digest().equals(command.digests().get(operation.digestVersion())))
                throw BillingFailure.conflict();
            return operation;
        });
    }
    /** Claim/reclaim updates a financial fence before acquiring domain job locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long claim(String operationId, long expectedFence) {
        var operation = store.operation(operationId);
        store.lockWallet(operation.walletId());
        return store.claim(store.operation(operationId), expectedFence);
    }
    /** Caller must save a validated result and job terminal state in this same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finish(String operationId, long fence, Outcome outcome) {
        var operation = store.operation(operationId);
        var wallet = store.lockWallet(operation.walletId());
        return store.finish(wallet, store.operation(operationId), fence, outcome);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public String grantTrial(Owner owner, String originalIdentity) {
        return store.trialGrant(store.lockWallet(owner), originalIdentity, store.catalog().trialAllowanceUnits());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public String compensate(String walletId, String debitId, String reference, long amount) {
        return store.compensate(store.lockWallet(walletId), debitId, reference, amount);
    }
    /** Ownership aliases keep receipts and active operations stable. No grant is issued. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachOwner(String walletId, Owner owner) {
        store.prepareOwners(java.util.List.of(owner));
        store.attachOwner(store.lockWallet(walletId), owner);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareOwners(java.util.List<Owner> owners) { store.prepareOwners(owners); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void convertOwner(Owner trial, Owner user) { store.convertOwner(trial, user); }
    @Transactional(propagation = Propagation.MANDATORY)
    public long trialAllowance(String walletId) { return store.trialAllowance(walletId); }
    public record Summary(Wallet wallet, java.time.Instant asOf) {}
}
