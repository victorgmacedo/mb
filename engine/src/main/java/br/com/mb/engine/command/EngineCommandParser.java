package br.com.mb.engine.command;

import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.FixMessageType;
import br.com.mb.shared.fix.InvalidFixMessageException;

public final class EngineCommandParser {

    public EngineCommand parse(FixMessage message) {
        return switch (message.messageType()) {
            case NEW_ORDER_SINGLE -> parseNewOrderSingle(message);
            case ORDER_CANCEL_REQUEST -> parseCancelOrder(message);
            case FUNDING_CREDIT -> parseFundingCredit(message);
            case LEDGER_TRADE_SETTLEMENT, LEDGER_RELEASE, EXECUTION_REPORT, BUSINESS_MESSAGE_REJECT ->
                throw new InvalidFixMessageException("Unsupported inbound FIX MsgType(35): " + message.messageType().tagValue());
        };
    }

    private static NewOrderSingleCommand parseNewOrderSingle(FixMessage message) {
        return new NewOrderSingleCommand(
            required(message, 1, "Account"),
            required(message, 11, "ClOrdID"),
            required(message, 55, "Symbol"),
            OrderSide.fromFixValue(required(message, 54, "Side")),
            parseLong(required(message, 44, "Price"), "Price(44)"),
            parseLong(required(message, 38, "OrderQty"), "OrderQty(38)")
        );
    }

    private static CancelOrderCommand parseCancelOrder(FixMessage message) {
        return new CancelOrderCommand(
            message.field(1).orElseGet(message::kafkaKey),
            required(message, 11, "ClOrdID"),
            required(message, 41, "OrigClOrdID")
        );
    }

    private static FundingCreditCommand parseFundingCredit(FixMessage message) {
        return new FundingCreditCommand(
            required(message, 1, "Account"),
            required(message, 11, "ClOrdID"),
            required(message, 55, "Asset"),
            parseLong(required(message, 38, "Amount"), "Amount(38)")
        );
    }

    private static String required(FixMessage message, int tag, String name) {
        return message.field(tag)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new InvalidFixMessageException("FIX message requires " + name + "(" + tag + ")"));
    }

    private static long parseLong(String value, String name) {
        try {
            var parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new InvalidFixMessageException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidFixMessageException("Invalid " + name + ": " + value, exception);
        }
    }
}
