package dev.paymentwallet.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties={"spring.kafka.listener.auto-startup=false", "wallet.outbox.enabled=false"})
class TransferConcurrencyTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired WalletService service;
    @Autowired JdbcTemplate db;

    @Test void fiftyConcurrentRetriesCreateOneBalancedTransfer() {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        var source=service.create("USD").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other.toString(),null,List.of()));
        var target=service.create("USD").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        service.deposit("deposit-"+owner,source,5_000);
        try(var pool=Executors.newFixedThreadPool(20)) {
            var futures=java.util.stream.IntStream.range(0,50).mapToObj(i->CompletableFuture.supplyAsync(()->{
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
                try{return service.transfer("same-request",source,target,500);} finally {SecurityContextHolder.clearContext();}
            },pool)).toList();
            var results=futures.stream().map(CompletableFuture::join).toList();
            assertEquals(1,results.stream().map(WalletService.Transfer::id).distinct().count());
        }
        assertEquals(4_500,service.get(source).balanceMinor());
        assertEquals(500,db.queryForObject("SELECT balance_minor FROM wallets WHERE id=?",Long.class,target));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM transfers WHERE idempotency_key='same-request'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries WHERE transfer_id=(SELECT id FROM transfers WHERE idempotency_key='same-request')",Long.class));
    }

    @Test void thousandParallelTransfersConserveMoneyAndNeverOverdraw() {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        var source=service.create("INR").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other.toString(),null,List.of()));
        var target=service.create("INR").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        service.deposit("deposit-"+owner,source,1_000);
        try(var pool=Executors.newFixedThreadPool(32)) {
            var futures=java.util.stream.IntStream.range(0,1_000).mapToObj(i->CompletableFuture.runAsync(()->{
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
                try { service.transfer("parallel-"+i,source,target,1); } finally { SecurityContextHolder.clearContext(); }
            },pool)).toList();
            futures.forEach(CompletableFuture::join);
        }
        assertEquals(0,service.get(source).balanceMinor());
        assertEquals(1_000,db.queryForObject("SELECT balance_minor FROM wallets WHERE id=?",Long.class,target));
        assertEquals(0,db.queryForObject("SELECT SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries WHERE transfer_id IN (SELECT id FROM transfers WHERE idempotency_key LIKE 'parallel-%')",Long.class));
    }

    @Test void insufficientFundsIsAnIdempotentFailureWithAnOutboxEvent() {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        var source=service.create("EUR").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other.toString(),null,List.of()));
        var target=service.create("EUR").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        var failed=service.transfer("no-funds-"+owner,source,target,100);
        assertEquals("FAILED",failed.status());
        assertEquals(failed,service.transfer("no-funds-"+owner,source,target,100));
        assertEquals(0,service.get(source).balanceMinor());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transfer_id=?",Integer.class,failed.id()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id=? AND event_type='transfer.failed'",Integer.class,failed.id()));
    }

    @Test void repeatedDepositKeyFundsTheWalletOnlyOnce() {
        UUID owner=UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        var wallet=service.create("CAD").id();

        assertEquals(750,service.deposit("same-deposit",wallet,750).balanceMinor());
        assertEquals(750,service.deposit("same-deposit",wallet,750).balanceMinor());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM transfers WHERE idempotency_key='same-deposit'",Integer.class));
        assertThrows(ResponseStatusException.class,()->service.deposit("same-deposit",wallet,800));
        assertEquals(750,service.get(wallet).balanceMinor());
    }

    @Test void databaseFailureAfterWalletUpdatesRollsBackTheWholeTransfer() {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        var source=service.create("GBP").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other.toString(),null,List.of()));
        var target=service.create("GBP").id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(),null,List.of()));
        service.deposit("deposit-"+owner,source,1_000);
        db.execute("CREATE OR REPLACE FUNCTION reject_ledger_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected ledger failure'; END $$");
        db.execute("CREATE TRIGGER reject_ledger_test BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION reject_ledger_test()");
        try {
            assertThrows(DataAccessException.class,()->service.transfer("rollback-"+owner,source,target,250));
        } finally {
            db.execute("DROP TRIGGER IF EXISTS reject_ledger_test ON ledger_entries");
            db.execute("DROP FUNCTION IF EXISTS reject_ledger_test()");
        }
        assertEquals(1_000,service.get(source).balanceMinor());
        assertEquals(0,db.queryForObject("SELECT balance_minor FROM wallets WHERE id=?",Long.class,target));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM transfers WHERE idempotency_key=?",Integer.class,"rollback-"+owner));
    }
}
