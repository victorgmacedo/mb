package br.com.mb.ledger.settlement;

import br.com.mb.ledger.domain.AccountId;
import br.com.mb.ledger.domain.SettlementSide;
import br.com.mb.shared.fix.FixMessage;
import br.com.mb.shared.fix.FixMessageType;
import br.com.mb.shared.fix.InvalidFixMessageException;
import br.com.mb.shared.model.Asset;

public final class LedgerSettlementParser {

    public LedgerSettlementCommand parse(FixMessage message) {
        return switch (message.messageType()) {
            case LEDGER_TRADE_SETTLEMENT -> parseTradeSettlement(message);
            case LEDGER_RELEASE -> parseRelease(message);
            case NEW_ORDER_SINGLE, ORDER_CANCEL_REQUEST, FUNDING_CREDIT, BOOK_ORDER_ACCEPTED, BOOK_ORDER_CANCELLED, EXECUTION_REPORT, BUSINESS_MESSAGE_REJECT ->
                throw new InvalidFixMessageException("Unsupported ledger settlement MsgType(35): " + message.messageType().tagValue());
        };
    }

    private static TradeSettlementCommand parseTradeSettlement(FixMessage message) {
        var assets = splitInstrument(required(message, 55, "Symbol"));
        return new TradeSettlementCommand(
            required(message, 17, "ExecID"),
            new AccountId(required(message, 10001, "MakerAccount")),
            new AccountId(required(message, 10002, "TakerAccount")),
            settlementSide(required(message, 54, "MakerSide")),
            new Asset(assets.baseAsset()),
            new Asset(assets.quoteAsset()),
            parseLong(required(message, 44, "Price"), "Price(44)"),
            parseLong(required(message, 38, "Quantity"), "Quantity(38)")
        );
    }

    private static ReleaseCommand parseRelease(FixMessage message) {
        return new ReleaseCommand(
            required(message, 17, "ExecID"),
            new AccountId(required(message, 1, "Account")),
            new Asset(required(message, 55, "Asset")),
            parseLong(required(message, 38, "Amount"), "Amount(38)")
        );
    }

    private static InstrumentAssets splitInstrument(String symbol) {
        var separator = symbol.indexOf('/');
        if (separator <= 0 || separator == symbol.length() - 1) {
            throw new InvalidFixMessageException("Invalid Symbol(55): " + symbol);
        }
        return new InstrumentAssets(symbol.substring(0, separator), symbol.substring(separator + 1));
    }

    private static SettlementSide settlementSide(String value) {
        return switch (value) {
            case "1" -> SettlementSide.BUY;
            case "2" -> SettlementSide.SELL;
            default -> throw new InvalidFixMessageException("Invalid MakerSide(54): " + value);
        };
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

    private record InstrumentAssets(String baseAsset, String quoteAsset) {
    }
}
