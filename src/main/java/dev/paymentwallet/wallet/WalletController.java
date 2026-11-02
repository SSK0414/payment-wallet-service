package dev.paymentwallet.wallet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class WalletController {
    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    public record CreateWallet(@NotNull @Pattern(regexp="[A-Za-z]{3}") String currency) {}
    public record Money(@Positive long amountMinor) {}

    public record TransferRequest(@NotNull UUID fromWallet, @NotNull UUID toWallet, @Positive long amountMinor) {}

    @PostMapping("/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    public WalletService.Wallet create(@Valid @RequestBody CreateWallet body) {
        return service.create(body.currency());
    }

    @GetMapping("/wallets/{id}")
    public WalletService.Wallet get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/wallets/{id}/deposit")
    public WalletService.Wallet deposit(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Money body
    ) {
        return service.deposit(key, id, body.amountMinor());
    }

    @PostMapping("/transfers")
    public ResponseEntity<WalletService.Transfer> transfer(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody TransferRequest body
    ) {
        var result = service.transfer(key, body.fromWallet(), body.toWallet(), body.amountMinor());
        HttpStatus status = result.status().equals("FAILED")
            ? HttpStatus.UNPROCESSABLE_ENTITY
            : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/transfers/{id}")
    public WalletService.Transfer transfer(@PathVariable UUID id) {
        return service.transferById(id);
    }

    @GetMapping("/wallets/{id}/ledger")
    public List<WalletService.Entry> ledger(
        @PathVariable UUID id,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="9223372036854775807") long before
    ) {
        return service.ledger(id, limit, before);
    }
}
