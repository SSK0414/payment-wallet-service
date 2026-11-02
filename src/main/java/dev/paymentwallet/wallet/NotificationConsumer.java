package dev.paymentwallet.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public NotificationConsumer(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @KafkaListener(topics = "wallet-events", groupId = "wallet-notifications")
    public void consume(String message) throws Exception {
        JsonNode event = json.readTree(message);
        db.update(
            "INSERT INTO notifications(event_id,aggregate_id,event_type,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING",
            event.get("eventId").asLong(),
            event.get("aggregateId").asText(),
            event.get("type").asText(),
            event.get("payload").toString()
        );
    }
}
