package com.tovarika.tech.billing.application;

import com.tovarika.tech.billing.domain.Billing.*;
import java.time.Instant;
import java.util.Optional;

/** Calls participate in the caller's PostgreSQL transaction; mutations require wallet locks. */
public interface BillingStore {
    Wallet lockWallet(Owner owner);
    Wallet lockWallet(String id);
    Catalog catalog();
    Optional<Operation> receipt(String walletId, String namespace, String key);
    Optional<Operation> receipt(String walletId, Command command);
    Operation operation(String operationId);
    Admission admit(Wallet wallet, Command command);
    long claim(Operation operation, long expectedFence);
    boolean finish(Wallet wallet, Operation operation, long fence, Outcome outcome);
    String trialGrant(Wallet wallet, String originalIdentity, long amount);
    String compensate(Wallet wallet, String debitId, String reference, long amount);
    History history(Wallet wallet, HistoryQuery query);
    void attachOwner(Wallet wallet, Owner owner);
    void prepareOwners(java.util.List<Owner> owners);
    long trialAllowance(String walletId);
    void convertOwner(Owner trial, Owner user);
    Instant now();
}
