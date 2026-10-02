package br.com.mb.ledger.jpa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.LedgerException;
import br.com.mb.shared.model.Asset;
import org.junit.jupiter.api.Test;

class ProcessedCommandEntityTest {

    @Test
    void matchesSameFundingCreditPayload() {
        var command = new ProcessedCommandEntity(
            ProcessedCommandEntity.FUNDING_CREDIT,
            "funding-1",
            "account-A",
            "BRL",
            1_000
        );

        assertTrue(command.matchesFundingCredit(new AccountId("account-A"), new Asset("BRL"), 1_000));
    }

    @Test
    void doesNotMatchDifferentFundingCreditPayload() {
        var command = new ProcessedCommandEntity(
            ProcessedCommandEntity.FUNDING_CREDIT,
            "funding-1",
            "account-A",
            "BRL",
            1_000
        );

        assertFalse(command.matchesFundingCredit(new AccountId("account-A"), new Asset("BRL"), 2_000));
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(LedgerException.class, () -> new ProcessedCommandEntity(
            ProcessedCommandEntity.FUNDING_CREDIT,
            "funding-1",
            "account-A",
            "BRL",
            0
        ));
    }
}
