package com.tovarika.tech.billing.api;

import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.api.publicapi.BillingApi;
import com.tovarika.api.publicapi.model.*;
import com.tovarika.tech.billing.application.BillingService;
import com.tovarika.tech.billing.domain.Billing.*;
import com.tovarika.tech.trial.api.WorkspaceIdentityResolver;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Maps only the additive credit API; legacy BillingApi methods remain unimplemented. */
@RestController
@RequestMapping("${openapi.tovarika.base-path:/api/v1}")
public class BillingCreditsController {
    private final BillingService billing;
    private final WorkspaceIdentityResolver identities;
    private final AuthenticationStore auth;
    public BillingCreditsController(BillingService billing, WorkspaceIdentityResolver identities,
            AuthenticationStore auth) {
        this.billing = billing; this.identities = identities; this.auth = auth;
    }
    @Transactional
    @GetMapping(BillingApi.PATH_GET_CREDIT_WALLET)
    public ResponseEntity<CreditWalletDto> getCreditWallet() {
        return response(wallet(billing.wallet(owner())));
    }
    @Transactional
    @GetMapping(BillingApi.PATH_GET_CREDIT_SUMMARY)
    public ResponseEntity<CreditSummaryDto> getCreditSummary() {
        var summary = billing.summary(owner());
        return response(new CreditSummaryDto(wallet(summary.wallet()), summary.wallet().usedUnits(),
                summary.asOf().atOffset(ZoneOffset.UTC)));
    }
    @Transactional
    @GetMapping(BillingApi.PATH_LIST_CREDIT_HISTORY)
    public ResponseEntity<CreditHistoryPageDto> listCreditHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") Integer limit) {
        var history = billing.history(owner(), new HistoryQuery(fromDate, toDate, sort, limit, cursor));
        var rows = history.items().stream().map(entry -> {
            var dto = new CreditHistoryEntryDto(entry.id(), CreditHistoryKindDto.fromValue(entry.kind()),
                    entry.amountUnits(), entry.occurredAt().atOffset(ZoneOffset.UTC));
            if(!entry.kind().equals("free_operation")) dto.balanceAfterUnits(entry.balanceAfterUnits());
            if (entry.operationCode() != null) dto.operationCode(BillingOperationCodeDto.fromValue(entry.operationCode().name()));
            return dto;
        }).toList();
        var dto = new CreditHistoryPageDto(TovarikaUnitDto.TOVARIKA_CENT, rows, history.hasMore());
        if (history.nextCursor() != null) dto.nextCursor(history.nextCursor());
        return response(dto);
    }
    @GetMapping(BillingApi.PATH_GET_BILLING_PRICES)
    public ResponseEntity<BillingPriceCatalogDto> getBillingPrices() {
        var catalog = billing.prices();
        var prices = Arrays.stream(Code.values()).map(code -> new BillingPriceDto(
                BillingOperationCodeDto.fromValue(code.name()), catalog.prices().get(code))).toList();
        return response(new BillingPriceCatalogDto(catalog.version(), TovarikaUnitDto.TOVARIKA_CENT,
                catalog.trialAllowanceUnits(), BillingPriceCatalogDto.FreeEntireSuccessesPerCardEnum.fromValue(1),
                prices, catalog.effectiveFrom().atOffset(ZoneOffset.UTC)));
    }
    private Owner owner() {
        var identity = identities.resolve();
        if (identity.userId() != null) auth.initializeBilling(identity.userId());
        return identity.userId() != null ? new Owner("user", identity.userId()) : new Owner("trial", identity.trialSessionId());
    }
    private CreditWalletDto wallet(Wallet wallet) {
        return new CreditWalletDto(wallet.id(), TovarikaUnitDto.TOVARIKA_CENT, wallet.totalUnits(), wallet.availableUnits(),
                wallet.updatedAt().atOffset(ZoneOffset.UTC));
    }
    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
