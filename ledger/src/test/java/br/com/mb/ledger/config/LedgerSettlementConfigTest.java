package br.com.mb.ledger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LedgerSettlementConfigTest {

    @Test
    void readsConfigurationFromEnvironment() {
        var config = LedgerSettlementConfig.fromEnvironment(Map.of(
            "KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
            "KAFKA_SETTLEMENTS_TOPIC", "custom-settlements",
            "LEDGER_SETTLEMENT_CONSUMER_GROUP_ID", "ledger-test"
        ));

        assertEquals("kafka:19092", config.bootstrapServers());
        assertEquals("custom-settlements", config.settlementsTopic());
        assertEquals("ledger-test", config.consumerGroupId());
    }

    @Test
    void usesLocalDefaults() {
        var config = LedgerSettlementConfig.fromEnvironment(Map.of());

        assertEquals("localhost:9092", config.bootstrapServers());
        assertEquals("settlements", config.settlementsTopic());
        assertEquals("mb-ledger-settlements", config.consumerGroupId());
    }
}
