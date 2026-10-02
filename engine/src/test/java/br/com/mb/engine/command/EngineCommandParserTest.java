package br.com.mb.engine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.InvalidFixMessageException;
import org.junit.jupiter.api.Test;

class EngineCommandParserTest {

    private final EngineCommandParser parser = new EngineCommandParser();

    @Test
    void mapsNewOrderSingleFields() {
        var command = parser.parse(FixMessage.parse(
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=50000000|38=100000000|"
        ));

        var newOrder = assertInstanceOf(NewOrderSingleCommand.class, command);
        assertEquals("account-A", newOrder.accountId());
        assertEquals("order-1", newOrder.clientOrderId());
        assertEquals("BTC/BRL", newOrder.instrument());
        assertEquals(OrderSide.BUY, newOrder.side());
        assertEquals(50_000_000L, newOrder.price());
        assertEquals(100_000_000L, newOrder.quantity());
    }

    @Test
    void mapsCancelOrderFields() {
        var command = parser.parse(FixMessage.parse(
            "8=FIX.4.4|35=F|49=gateway|56=engine|1=account-A|11=cancel-1|41=order-1|"
        ));

        var cancelOrder = assertInstanceOf(CancelOrderCommand.class, command);
        assertEquals("account-A", cancelOrder.accountId());
        assertEquals("cancel-1", cancelOrder.clientOrderId());
        assertEquals("order-1", cancelOrder.originalClientOrderId());
    }

    @Test
    void rejectsNewOrderWithoutRequiredPrice() {
        var message = FixMessage.parse(
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|38=100000000|"
        );

        var exception = assertThrows(InvalidFixMessageException.class, () -> parser.parse(message));

        assertEquals("FIX message requires Price(44)", exception.getMessage());
    }

    @Test
    void rejectsNonPositivePrice() {
        var message = FixMessage.parse(
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=0|38=100000000|"
        );

        var exception = assertThrows(InvalidFixMessageException.class, () -> parser.parse(message));

        assertEquals("Price(44) must be positive", exception.getMessage());
    }

    @Test
    void rejectsNonPositiveQuantity() {
        var message = FixMessage.parse(
            "8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|55=BTC/BRL|54=1|44=50000000|38=0|"
        );

        var exception = assertThrows(InvalidFixMessageException.class, () -> parser.parse(message));

        assertEquals("OrderQty(38) must be positive", exception.getMessage());
    }
}
