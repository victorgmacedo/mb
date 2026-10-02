package br.com.mb.ledger.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommandEntity, Long> {

    Optional<ProcessedCommandEntity> findByCommandTypeAndClientOrderId(String commandType, String clientOrderId);
}
