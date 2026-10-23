package com.tovarika.tech.billing.payments;

import com.tovarika.tech.auth.api.RequestAuthenticationContext;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing")
public class CreditPurchasesController {
    private final CreditPurchases purchases;
    private final RobokassaGateway gateway;
    private final RequestAuthenticationContext identity;
    private final AuthenticationStore auth;
    public CreditPurchasesController(CreditPurchases purchases,RobokassaGateway gateway,
            RequestAuthenticationContext identity,AuthenticationStore auth) {
        this.purchases=purchases;this.gateway=gateway;this.identity=identity;this.auth=auth;
    }
    @GetMapping("/credit-packages")
    public ResponseEntity<CreditPurchases.Catalog> catalog() {
        var principal=identity.optionalPrincipal();
        return response(200,purchases.catalog(principal==null?null:principal.userId()));
    }
    @PostMapping("/credit-purchases")
    @Transactional
    public ResponseEntity<CreditPurchases.Checkout> create(@RequestBody CreatePurchase body,
            @RequestHeader("Idempotency-Key") String key) {
        String userId=identity.principal().userId();
        auth.initializeBilling(userId);
        return response(201,purchases.create(userId,body.packageId(),key));
    }
    @GetMapping("/credit-purchases/{id}")
    public ResponseEntity<CreditPurchases.Purchase> get(@PathVariable String id) {
        return response(200,purchases.get(identity.principal().userId(),id));
    }
    @GetMapping("/credit-lots")
    public ResponseEntity<CreditPurchases.Lots> lots() {
        return response(200,purchases.lots(identity.principal().userId()));
    }
    @PostMapping(value="/robokassa/result",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> result(@RequestParam MultiValueMap<String,String> parameters) {
        return response(200,purchases.accept(gateway.verify(parameters)));
    }
    @GetMapping("/robokassa/success")
    public ResponseEntity<Void> success(@RequestParam(name="Shp_purchaseId",required=false) String id) { return redirect(id,"success"); }
    @GetMapping("/robokassa/fail")
    public ResponseEntity<Void> fail(@RequestParam(name="Shp_purchaseId",required=false) String id) { return redirect(id,"fail"); }
    private ResponseEntity<Void> redirect(String id,String outcome) {
        // Browser returns never change payment state. The UI must poll its own authenticated purchase.
        String path="/billing";
        if(id!=null && id.matches("pay_[0-9a-f]{32}")) path+="?payment="+id+"&paymentReturn="+outcome;
        return ResponseEntity.status(303).location(URI.create(path)).header("Cache-Control","no-store")
                .header("Referrer-Policy","no-referrer").build();
    }
    private <T> ResponseEntity<T> response(int status,T body) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").body(body);
    }
    public record CreatePurchase(String packageId) {}
}
