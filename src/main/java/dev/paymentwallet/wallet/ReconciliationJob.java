package dev.paymentwallet.wallet;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private final JdbcTemplate db;
    public ReconciliationJob(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void reconcile() {
        var unbalanced = db.query(
            "SELECT t.id, COALESCE(SUM(CASE WHEN l.direction='CREDIT' THEN l.amount_minor ELSE -l.amount_minor END),0) " +
                "FROM transfers t LEFT JOIN ledger_entries l ON l.transfer_id=t.id " +
                "WHERE t.status='COMPLETED' GROUP BY t.id " +
                "HAVING COALESCE(SUM(CASE WHEN l.direction='CREDIT' THEN l.amount_minor ELSE -l.amount_minor END),0)<>0",
            (row, index) -> row.getString(1) + " net=" + row.getLong(2)
        );
        unbalanced.forEach(transfer -> log.error("transfer_ledger_imbalance {}", transfer));

        var mismatches = db.query(
            "SELECT w.id, w.balance_minor, " +
                "COALESCE(SUM(CASE WHEN l.direction='CREDIT' THEN l.amount_minor ELSE -l.amount_minor END),0) " +
                "FROM wallets w LEFT JOIN ledger_entries l ON l.wallet_id=w.id GROUP BY w.id " +
                "HAVING w.balance_minor<>COALESCE(SUM(CASE WHEN l.direction='CREDIT' THEN l.amount_minor ELSE -l.amount_minor END),0)",
            (row, index) -> row.getString(1) + " balance=" + row.getLong(2) + " ledger=" + row.getLong(3)
        );
        mismatches.forEach(wallet -> log.error("wallet_reconciliation_mismatch {}", wallet));
    }
}
