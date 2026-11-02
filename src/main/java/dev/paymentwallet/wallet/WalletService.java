package dev.paymentwallet.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class WalletService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public WalletService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    private UUID actor() { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }

    public record Wallet(UUID id, UUID ownerId, String currency, long balanceMinor) {}
    public record Transfer(UUID id, UUID fromWallet, UUID toWallet, long amountMinor, String currency, String status) {}
    public record Entry(long id, UUID transferId, String direction, long amountMinor, String createdAt) {}

    @Transactional
    public Wallet create(String currency) {
        String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
        UUID owner = actor();
        UUID id = db.queryForObject(
            "INSERT INTO wallets(owner_id,currency) VALUES (?,?) RETURNING id",
            UUID.class,
            owner,
            normalizedCurrency
        );
        audit(owner, "wallet.created", id, Map.of("currency", normalizedCurrency));
        return get(id);
    }

    public Wallet get(UUID id) {
        return getOwned(id, actor());
    }

    @Transactional
    public Wallet deposit(String key, UUID walletId, long amount) {
        validateKey(key);
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountMinor must be positive");
        }

        get(walletId);
        String currency = db.queryForObject(
            "SELECT currency FROM wallets WHERE id=?", String.class, walletId
        ).trim();
        var receipt = new Transfer(UUID.randomUUID(), walletId, walletId, amount, currency, "COMPLETED");
        if (insertOrGetExisting(receipt, key) != null) return get(walletId);

        db.update("UPDATE wallets SET balance_minor=balance_minor+? WHERE id=?", amount, walletId);
        db.update(
            "INSERT INTO ledger_entries(transfer_id,wallet_id,account_code,direction,amount_minor) VALUES (?,?,NULL,'CREDIT',?),(?,NULL,'CASH_CLEARING','DEBIT',?)",
            receipt.id(), walletId, amount, receipt.id(), amount
        );
        outbox(receipt.id(), "transfer.completed", Map.of(
            "transferId", receipt.id(),
            "walletId", walletId,
            "type", "deposit",
            "amountMinor", amount,
            "currency", currency
        ));
        audit(actor(), "wallet.deposit", walletId, Map.of("amountMinor", amount));
        return get(walletId);
    }
    @Transactional
    public Transfer transfer(String key, UUID from, UUID to, long amount) {
        validateKey(key);
        if (from.equals(to) || amount <= 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Distinct wallets and a positive amount are required"
            );
        }

        UUID owner = actor();
        getOwned(from, owner);
        var previous = db.query(
            "SELECT id,from_wallet,to_wallet,amount_minor,currency,status FROM transfers WHERE idempotency_key=?",
            this::transferRow,
            key
        );
        if (!previous.isEmpty()) {
            var transfer = previous.getFirst();
            if (!transfer.fromWallet().equals(from)
                || !transfer.toWallet().equals(to)
                || transfer.amountMinor() != amount) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Idempotency key reused with different request"
                );
            }
            return transfer;
        }

        // PostgreSQL sorts the rows before locking them, so opposite transfers use the same lock order.
        var wallets = db.query(
            "SELECT id,owner_id,currency,balance_minor FROM wallets WHERE id IN (?,?) ORDER BY id FOR UPDATE",
            this::walletRow,
            from,
            to
        );
        if (wallets.size() != 2) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        }

        var byId = wallets.stream().collect(Collectors.toMap(Wallet::id, wallet -> wallet));
        var source = byId.get(from);
        var destination = byId.get(to);
        if (!owner.equals(source.ownerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        }
        if (!source.currency().equals(destination.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet currencies must match");
        }

        if (source.balanceMinor() < amount) {
            var failed = new Transfer(UUID.randomUUID(), from, to, amount, source.currency(), "FAILED");
            var previousTransfer = insertOrGetExisting(failed, key);
            if (previousTransfer != null) return previousTransfer;
            outbox(failed.id(), "transfer.failed", Map.of(
                "transferId", failed.id(), "reason", "INSUFFICIENT_FUNDS", "amountMinor", amount
            ));
            audit(owner, "transfer.failed", failed.id(), Map.of("reason", "INSUFFICIENT_FUNDS"));
            return failed;
        }

        var result = new Transfer(UUID.randomUUID(), from, to, amount, source.currency(), "COMPLETED");
        var previousTransfer = insertOrGetExisting(result, key);
        if (previousTransfer != null) return previousTransfer;

        db.update("UPDATE wallets SET balance_minor=balance_minor-? WHERE id=?", amount, from);
        db.update("UPDATE wallets SET balance_minor=balance_minor+? WHERE id=?", amount, to);
        db.update(
            "INSERT INTO ledger_entries(transfer_id,wallet_id,direction,amount_minor) VALUES (?,?,'DEBIT',?),(?,?,'CREDIT',?)",
            result.id(), from, amount, result.id(), to, amount
        );
        outbox(result.id(), "transfer.completed", result);
        audit(owner, "transfer.completed", result.id(), Map.of("amountMinor", amount, "currency", source.currency()));
        return result;
    }
    public Transfer transferById(UUID id) {
        UUID owner = actor();
        var rows = db.query(
            "SELECT id,from_wallet,to_wallet,amount_minor,currency,status FROM transfers WHERE id=? AND (from_wallet IN (SELECT id FROM wallets WHERE owner_id=?) OR to_wallet IN (SELECT id FROM wallets WHERE owner_id=?))",
            this::transferRow,
            id,
            owner,
            owner
        );
        return rows.stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public List<Entry> ledger(UUID walletId, int limit, long before) {
        get(walletId);
        return db.query(
            "SELECT id,transfer_id,direction,amount_minor,created_at::text FROM ledger_entries WHERE wallet_id=? AND id<? ORDER BY id DESC LIMIT ?",
            (row, index) -> new Entry(
                row.getLong(1),
                row.getObject(2, UUID.class),
                row.getString(3),
                row.getLong(4),
                row.getString(5)
            ),
            walletId,
            before,
            Math.min(Math.max(limit, 1), 100)
        );
    }

    private Wallet getOwned(UUID id, UUID owner) {
        return db.query(
            "SELECT id,owner_id,currency,balance_minor FROM wallets WHERE id=? AND owner_id=?",
            this::walletRow,
            id,
            owner
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Wallet walletRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Wallet(
            row.getObject(1, UUID.class),
            row.getObject(2, UUID.class),
            row.getString(3).trim(),
            row.getLong(4)
        );
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1-128 characters"
            );
        }
    }

    private Transfer insertOrGetExisting(Transfer transfer, String key) {
        int inserted = db.update(
            "INSERT INTO transfers(id,idempotency_key,from_wallet,to_wallet,amount_minor,currency,status) VALUES (?,?,?,?,?,?,?) ON CONFLICT (idempotency_key) DO NOTHING",
            transfer.id(),
            key,
            transfer.fromWallet(),
            transfer.toWallet(),
            transfer.amountMinor(),
            transfer.currency(),
            transfer.status()
        );
        if (inserted == 1) return null;

        var existing = db.queryForObject(
            "SELECT id,from_wallet,to_wallet,amount_minor,currency,status FROM transfers WHERE idempotency_key=?",
            this::transferRow,
            key
        );
        if (!existing.fromWallet().equals(transfer.fromWallet())
            || !existing.toWallet().equals(transfer.toWallet())
            || existing.amountMinor() != transfer.amountMinor()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Idempotency key reused with different request"
            );
        }
        return existing;
    }

    private Transfer transferRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Transfer(
            row.getObject(1, UUID.class),
            row.getObject(2, UUID.class),
            row.getObject(3, UUID.class),
            row.getLong(4),
            row.getString(5).trim(),
            row.getString(6)
        );
    }

    private void audit(UUID owner, String action, UUID target, Map<String, ?> details) {
        db.update(
            "INSERT INTO audit_log(actor_id,action,target_id,details) VALUES (?,?,?,?::jsonb)",
            owner,
            action,
            target,
            write(details)
        );
    }

    private void outbox(UUID id, String type, Object payload) {
        db.update(
            "INSERT INTO outbox(aggregate_id,event_type,payload) VALUES (?,?,?::jsonb)",
            id,
            type,
            write(payload)
        );
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
