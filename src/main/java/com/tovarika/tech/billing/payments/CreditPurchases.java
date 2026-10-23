package com.tovarika.tech.billing.payments;

import com.tovarika.tech.billing.application.BillingFailure;
import com.tovarika.tech.billing.domain.Billing;
import com.tovarika.tech.billing.domain.Billing.Owner;
import com.tovarika.tech.billing.infrastructure.JdbcBillingStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreditPurchases {
    private final JdbcTemplate jdbc;
    private final JdbcBillingStore wallets;
    private final RobokassaGateway gateway;
    private final RobokassaSettings settings;
    public CreditPurchases(JdbcTemplate jdbc,JdbcBillingStore wallets,RobokassaGateway gateway,RobokassaSettings settings) {
        this.jdbc=jdbc;this.wallets=wallets;this.gateway=gateway;this.settings=settings;
    }
    public Catalog catalog(String userId) {
        var packages=jdbc.query("select * from billing_credit_packages order by price_kopecks",(r,n) ->
                new CreditPackage(r.getString("id"),r.getLong("price_kopecks"),r.getLong("credit_units"),r.getInt("validity_months")));
        return new Catalog(packages,settings.availableFor(userId),true);
    }
    @Transactional
    public Checkout create(String userId,String packageId,String idempotencyKey) {
        if(!settings.availableFor(userId)) throw RobokassaGateway.unavailable();
        if(packageId==null || !packageId.matches("credits_[0-9]{1,4}_month") || idempotencyKey==null ||
                !idempotencyKey.matches("[A-Za-z0-9_-]{8,200}")) throw BillingFailure.validation();
        var wallet=wallets.lockWallet(new Owner("user",userId));
        var existing=jdbc.query("select * from billing_credit_purchases where user_id=? and idempotency_key=?",
                (r,n)->row(r),userId,idempotencyKey);
        PurchaseRow purchase;
        if(!existing.isEmpty()) {
            purchase=existing.getFirst();
            if(!purchase.purchase.packageId.equals(packageId)) throw BillingFailure.conflict();
        } else {
            var packages=catalog(userId).items.stream().filter(p -> p.id.equals(packageId)).toList();
            if(packages.isEmpty()) throw BillingFailure.validation();
            var selected=packages.getFirst();
            purchase=jdbc.queryForObject("""
                    insert into billing_credit_purchases(id,user_id,wallet_id,idempotency_key,package_id,
                    price_kopecks,credit_units,test_mode) values(?,?,?,?,?,?,?,true) returning *
                    """,(r,n)->row(r),Billing.id("pay_"),userId,wallet.id(),idempotencyKey,
                    selected.id,selected.priceKopecks,selected.creditUnits);
        }
        return new Checkout(purchase.purchase,gateway.confirmationUrl(purchase.purchase.id,
                purchase.invoiceId,purchase.purchase.priceKopecks,purchase.purchase.creditUnits));
    }
    public Purchase get(String userId,String purchaseId) {
        if(purchaseId==null || !purchaseId.matches("pay_[0-9a-f]{32}")) throw missing();
        var rows=jdbc.query("select * from billing_credit_purchases where id=? and user_id=?",(r,n)->row(r),purchaseId,userId);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst().purchase;
    }
    @Transactional
    public String accept(RobokassaGateway.Notification notification) {
        var rows=jdbc.query("select * from billing_credit_purchases where id=? and invoice_id=?",
                (r,n)->row(r),notification.purchaseId(),notification.invoiceId());
        if(rows.isEmpty()) throw BillingFailure.validation();
        var initial=rows.getFirst();
        // Match snapshot before taking locks or changing the wallet.
        if(initial.purchase.priceKopecks!=notification.priceKopecks() || !initial.purchase.testMode) throw BillingFailure.validation();
        var wallet=wallets.lockWallet(initial.walletId);
        var purchase=jdbc.queryForObject("select * from billing_credit_purchases where id=? for update",(r,n)->row(r),notification.purchaseId());
        if(purchase.purchase.status.equals("pending")) {
            Instant paidAt=wallets.now();
            Instant expiresAt=PackageLifetime.expiresAt(paidAt);
            wallets.grantPackage(wallet,purchase.purchase.id,purchase.purchase.creditUnits,paidAt,expiresAt);
            jdbc.update("update billing_credit_purchases set status='succeeded',paid_at=?,expires_at=? where id=?",
                    Timestamp.from(paidAt),Timestamp.from(expiresAt),purchase.purchase.id);
        }
        return "OK"+notification.invoiceId();
    }
    @Transactional
    public Lots lots(String userId) {
        var wallet=wallets.lockWallet(new Owner("user",userId));
        Instant cutoff=wallets.now();
        return new Lots(jdbc.query("""
                select c.* from billing_credit_lots c join billing_wallet_links l on l.wallet_id=c.wallet_id
                where l.canonical_wallet_id=? and c.reference like 'purchase:%' and c.remaining_units>0
                order by c.expires_at,c.id
                """,(r,n) -> new CreditLot(r.getString("id"),r.getLong("remaining_units"),
                r.getTimestamp("expires_at").toInstant().isAfter(cutoff) ?
                        r.getLong("remaining_units")-r.getLong("reserved_units") : 0,
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant()),wallet.id()));
    }
    private PurchaseRow row(ResultSet r) throws SQLException {
        return new PurchaseRow(new Purchase(r.getString("id"),r.getString("package_id"),r.getLong("price_kopecks"),
                r.getLong("credit_units"),r.getString("status"),r.getBoolean("test_mode"),
                r.getTimestamp("created_at").toInstant(),instant(r,"paid_at"),instant(r,"expires_at")),
                r.getLong("invoice_id"),r.getString("wallet_id"));
    }
    private Instant instant(ResultSet r,String name) throws SQLException { var value=r.getTimestamp(name);return value==null?null:value.toInstant(); }
    private static BillingFailure missing() { return new BillingFailure(404,"VALIDATION_ERROR","Payment not found"); }
    private record PurchaseRow(Purchase purchase,long invoiceId,String walletId) {}
    public record CreditPackage(String id,long priceKopecks,long creditUnits,int validityMonths) {}
    public record Catalog(List<CreditPackage> items,boolean checkoutAvailable,boolean testMode) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Purchase(String id,String packageId,long priceKopecks,long creditUnits,String status,boolean testMode,
            Instant createdAt,Instant paidAt,Instant expiresAt) {}
    public record Checkout(Purchase purchase,String confirmationUrl) {}
    public record CreditLot(String id,long remainingUnits,long availableUnits,Instant purchasedAt,Instant expiresAt) {}
    public record Lots(List<CreditLot> items) {}
}
