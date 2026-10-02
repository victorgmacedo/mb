package br.com.mb.engine.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.mb.engine.command.NewOrderSingleCommand;
import br.com.mb.engine.command.OrderSide;
import org.junit.jupiter.api.Test;

class OrderIntakeTest {

    private final OrderIntake intake = new OrderIntake(InstrumentCatalog.defaultCatalog());

    @Test
    void acceptsValidNewOrder() {
        var order = intake.accept(new NewOrderSingleCommand(
            "account-A",
            "order-1",
            "BTC/BRL",
            OrderSide.BUY,
            50_000_000L,
            100_000_000L
        ));

        assertEquals(new AccountId("account-A"), order.accountId());
        assertEquals(new ClientOrderId("order-1"), order.clientOrderId());
        assertEquals(new Instrument("BTC/BRL"), order.instrument());
        assertEquals(Side.BUY, order.side());
        assertEquals(50_000_000L, order.price());
        assertEquals(100_000_000L, order.quantity());
        assertEquals(OrderStatus.ACCEPTED, order.status());
    }

    @Test
    void rejectsUnknownInstrument() {
        var command = new NewOrderSingleCommand(
            "account-A",
            "order-1",
            "DOGE/BRL",
            OrderSide.BUY,
            50_000_000L,
            100_000_000L
        );

        var exception = assertThrows(InvalidOrderException.class, () -> intake.accept(command));

        assertEquals("unknown instrument: DOGE/BRL", exception.getMessage());
    }

    @Test
    void rejectsZeroPrice() {
        var command = new NewOrderSingleCommand(
            "account-A",
            "order-1",
            "BTC/BRL",
            OrderSide.BUY,
            0L,
            100_000_000L
        );

        var exception = assertThrows(InvalidOrderException.class, () -> intake.accept(command));

        assertEquals("price must be positive", exception.getMessage());
    }

    @Test
    void rejectsZeroQuantity() {
        var command = new NewOrderSingleCommand(
            "account-A",
            "order-1",
            "BTC/BRL",
            OrderSide.BUY,
            50_000_000L,
            0L
        );

        var exception = assertThrows(InvalidOrderException.class, () -> intake.accept(command));

        assertEquals("quantity must be positive", exception.getMessage());
    }
}
