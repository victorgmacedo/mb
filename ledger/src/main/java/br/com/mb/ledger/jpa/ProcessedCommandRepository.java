package br.com.mb.ledger.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommandEntity, Long> {

    Optional<ProcessedCommandEntity> findByCommandTypeAndClientOrderId(String commandType, String clientOrderId);

    @Modifying
    @Query(
        value = """
            insert into processed_commands (command_type, client_order_id, account_id, asset_symbol, amount, payload_hash)
            values (:commandType, :clientOrderId, :accountId, :assetSymbol, :amount, :payloadHash)
            on conflict (command_type, client_order_id) do nothing
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("commandType") String commandType,
        @Param("clientOrderId") String clientOrderId,
        @Param("accountId") String accountId,
        @Param("assetSymbol") String assetSymbol,
        @Param("amount") long amount,
        @Param("payloadHash") String payloadHash
    );
}
