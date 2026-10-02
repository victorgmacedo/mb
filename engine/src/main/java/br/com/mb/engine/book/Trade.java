package br.com.mb.engine.book;

import br.com.mb.engine.domain.AccountId;
import br.com.mb.engine.domain.ClientOrderId;
import br.com.mb.engine.domain.Side;

public record Trade(
    AccountId makerAccountId,
    ClientOrderId makerClientOrderId,
    Side makerSide,
    AccountId takerAccountId,
    ClientOrderId takerClientOrderId,
    long price,
    long quantity,
    long makerLeavesQuantity,
    long takerLeavesQuantity
) {
}
