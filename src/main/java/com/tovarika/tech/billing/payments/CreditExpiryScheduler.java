package com.tovarika.tech.billing.payments;

import com.tovarika.tech.billing.infrastructure.JdbcBillingStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CreditExpiryScheduler {
    private final JdbcTemplate jdbc;
    private final JdbcBillingStore wallets;
    private final TransactionTemplate transactions;
    public CreditExpiryScheduler(JdbcTemplate jdbc,JdbcBillingStore wallets,org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.wallets=wallets;this.transactions=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${tovarika.billing.credit-expiry-delay-ms:60000}")
    public void expire() {
        var ids=jdbc.queryForList("""
                select distinct wallet_id from billing_credit_lots
                where expires_at<=clock_timestamp() and remaining_units>reserved_units limit 100
                """,String.class);
        for(String id:ids) transactions.executeWithoutResult(status -> wallets.lockWallet(id));
    }
}
