package dev.paymentwallet.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "wallet.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate db;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper json;

    public OutboxPublisher(JdbcTemplate db, KafkaTemplate<String,String> kafka, ObjectMapper json) {
        this.db=db;
        this.kafka=kafka;
        this.json=json;
    }

    @Scheduled(fixedDelay=500) @Transactional public void publish() {
        var events=db.query(
            "SELECT id,aggregate_id,event_type,payload::text FROM outbox WHERE published=FALSE ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED",
            (row, n) -> new Event(row.getLong(1), row.getString(2), row.getString(3), row.getString(4))
        );
        for (var event : events) {
            try {
                var payload=json.readTree(event.payload());
                var message=json.writeValueAsString(new WalletEvent(event.id(),event.type(),event.aggregateId(),payload));
                kafka.send("wallet-events",event.aggregateId(),message).get();
                db.update("UPDATE outbox SET published=TRUE WHERE id=?",event.id());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                log.warn("outbox_publish_failed eventId={}",event.id(),failure);
                break;
            }
        }
    }

    private record Event(long id,String aggregateId,String type,String payload) {}
    private record WalletEvent(long eventId,String type,String aggregateId,JsonNode payload) {}
}
