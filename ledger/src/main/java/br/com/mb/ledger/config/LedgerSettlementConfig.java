package br.com.mb.ledger.config;

import java.util.Map;

public record LedgerSettlementConfig(String bootstrapServers, String settlementsTopic, String consumerGroupId) {

    public static LedgerSettlementConfig fromEnvironment(Map<String, String> environment) {
        return new LedgerSettlementConfig(
            environment.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            environment.getOrDefault("KAFKA_SETTLEMENTS_TOPIC", "settlements"),
            environment.getOrDefault("LEDGER_SETTLEMENT_CONSUMER_GROUP_ID", "mb-ledger-settlements")
        );
    }
}
