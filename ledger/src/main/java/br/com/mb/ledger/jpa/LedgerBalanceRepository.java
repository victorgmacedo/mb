package br.com.mb.ledger.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerBalanceRepository extends JpaRepository<LedgerBalanceEntity, Long> {

    Optional<LedgerBalanceEntity> findByAccountIdAndAssetSymbol(String accountId, String assetSymbol);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select balance
        from LedgerBalanceEntity balance
        where balance.accountId = :accountId
          and balance.assetSymbol = :assetSymbol
        """)
    Optional<LedgerBalanceEntity> findForUpdate(
        @Param("accountId") String accountId,
        @Param("assetSymbol") String assetSymbol
    );
}
