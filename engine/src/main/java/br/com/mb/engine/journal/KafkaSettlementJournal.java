package br.com.mb.engine.journal;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.book.Trade;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.util.Objects;

public final class KafkaSettlementJournal implements ExecutionJournal {

    private final CommandPublisher publisher;
    private final String topic;

    public KafkaSettlementJournal(CommandPublisher publisher, String topic) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
    }

    @Override
    public void append(Order takerOrder, PlacementResult placement) {
        Objects.requireNonNull(takerOrder, "takerOrder must not be null");
        Objects.requireNonNull(placement, "placement must not be null");
        var tradeNumber = 1;
        for (var trade : placement.trades()) {
            publish(takerOrder, trade, tradeNumber);
            publishPriceImprovementRelease(takerOrder, trade, tradeNumber);
            tradeNumber++;
        }
    }

    private void publish(Order takerOrder, Trade trade, int tradeNumber) {
        publisher.publish(new CommandMessage(
            topic,
            takerOrder.instrument().symbol(),
            settlementFix(takerOrder, trade, tradeNumber)
        ));
    }

    private void publishPriceImprovementRelease(Order takerOrder, Trade trade, int tradeNumber) {
        if (takerOrder.side() != Side.BUY || takerOrder.price() == trade.price()) {
            return;
        }
        publisher.publish(new CommandMessage(
            topic,
            takerOrder.instrument().symbol(),
            priceImprovementReleaseFix(takerOrder, trade, tradeNumber)
        ));
    }

    private static String settlementFix(Order takerOrder, Trade trade, int tradeNumber) {
        return "8=FIX.4.4\u000135=U2\u000149=engine\u000156=ledger\u000117=%s\u000155=%s\u000154=%s\u000144=%d\u000138=%d\u000110001=%s\u000110002=%s\u000141=%s\u000111=%s\u0001"
            .formatted(
                sanitize("settle-" + takerOrder.clientOrderId().value() + "-" + tradeNumber),
                sanitize(takerOrder.instrument().symbol()),
                sideTag(trade.makerSide()),
                trade.price(),
                trade.quantity(),
                sanitize(trade.makerAccountId().value()),
                sanitize(trade.takerAccountId().value()),
                sanitize(trade.makerClientOrderId().value()),
                sanitize(trade.takerClientOrderId().value())
            );
    }

    private static String priceImprovementReleaseFix(Order takerOrder, Trade trade, int tradeNumber) {
        return "8=FIX.4.4\u000135=U3\u000149=engine\u000156=ledger\u000117=%s\u00011=%s\u000111=%s\u000155=%s\u000138=%d\u0001"
            .formatted(
                sanitize("release-" + takerOrder.clientOrderId().value() + "-" + tradeNumber),
                sanitize(takerOrder.accountId().value()),
                sanitize(takerOrder.clientOrderId().value()),
                quoteAsset(takerOrder.instrument().symbol()),
                priceImprovementAmount(takerOrder, trade)
            );
    }

    private static String sideTag(Side side) {
        return switch (side) {
            case BUY -> "1";
            case SELL -> "2";
        };
    }

    private static long priceImprovementAmount(Order takerOrder, Trade trade) {
        try {
            return Math.multiplyExact(takerOrder.price() - trade.price(), trade.quantity());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("price improvement amount overflow", exception);
        }
    }

    private static String quoteAsset(String symbol) {
        var separator = symbol.indexOf('/');
        if (separator < 0 || separator == symbol.length() - 1) {
            throw new IllegalArgumentException("instrument symbol must contain quote asset: " + symbol);
        }
        return sanitize(symbol.substring(separator + 1));
    }

    private static String sanitize(String value) {
        return value.replace('\u0001', ' ').replace('|', ' ');
    }
}
