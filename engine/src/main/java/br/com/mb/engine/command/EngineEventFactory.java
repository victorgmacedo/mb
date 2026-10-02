package br.com.mb.engine.command;

import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.book.Trade;
import java.util.ArrayList;
import java.util.List;

public final class EngineEventFactory {

    public List<String> accepted(EngineCommand command) {
        return switch (command) {
            case NewOrderSingleCommand newOrder -> List.of(executionReport(
                newOrder.accountId(),
                newOrder.clientOrderId(),
                "accepted-" + newOrder.clientOrderId(),
                "0",
                "0",
                0,
                0,
                0,
                "Order accepted"
            ));
            case CancelOrderCommand cancelOrder -> List.of(executionReport(
                cancelOrder.accountId(),
                cancelOrder.clientOrderId(),
                "accepted-" + cancelOrder.clientOrderId(),
                "4",
                "4",
                0,
                0,
                0,
                "Order cancelled"
            ));
            case FundingCreditCommand fundingCredit -> List.of(executionReport(
                fundingCredit.accountId(),
                fundingCredit.clientOrderId(),
                "accepted-" + fundingCredit.clientOrderId(),
                "0",
                "0",
                0,
                0,
                0,
                "Funding credited"
            ));
        };
    }

    public List<String> accepted(NewOrderSingleCommand command, PlacementResult placement) {
        if (placement.trades().isEmpty()) {
            return accepted(command);
        }

        var events = new ArrayList<String>();
        var tradeNumber = 1;
        for (var trade : placement.trades()) {
            events.add(makerTradeReport(command, trade, tradeNumber));
            events.add(takerTradeReport(command, trade, tradeNumber));
            tradeNumber++;
        }
        placement.restingOrder().ifPresent(resting -> events.add(executionReport(
            command.accountId(),
            command.clientOrderId(),
            "resting-" + command.clientOrderId(),
            "0",
            "1",
            0,
            0,
            resting.remainingQuantity(),
            "Order partially filled and resting"
        )));
        return events;
    }

    public List<String> rejected(String key, String reason) {
        return List.of("8=FIX.4.4\u000135=j\u000149=engine\u000156=%s\u000158=%s\u0001".formatted(key, sanitize(reason)));
    }

    private static String makerTradeReport(NewOrderSingleCommand taker, Trade trade, int tradeNumber) {
        var makerStatus = trade.makerLeavesQuantity() == 0 ? "2" : "1";
        return executionReport(
            trade.makerAccountId().value(),
            trade.makerClientOrderId().value(),
            "trade-" + taker.clientOrderId() + "-" + tradeNumber + "-maker",
            "F",
            makerStatus,
            trade.price(),
            trade.quantity(),
            trade.makerLeavesQuantity(),
            "Maker fill"
        );
    }

    private static String takerTradeReport(NewOrderSingleCommand taker, Trade trade, int tradeNumber) {
        var takerStatus = trade.takerLeavesQuantity() == 0 ? "2" : "1";
        return executionReport(
            taker.accountId(),
            taker.clientOrderId(),
            "trade-" + taker.clientOrderId() + "-" + tradeNumber + "-taker",
            "F",
            takerStatus,
            trade.price(),
            trade.quantity(),
            trade.takerLeavesQuantity(),
            "Taker fill"
        );
    }

    private static String executionReport(
        String accountId,
        String clientOrderId,
        String executionId,
        String executionType,
        String orderStatus,
        long lastPrice,
        long lastQuantity,
        long leavesQuantity,
        String text
    ) {
        return "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=%s\u000111=%s\u000117=%s\u0001150=%s\u000139=%s\u000131=%d\u000132=%d\u0001151=%d\u000158=%s\u0001"
            .formatted(
                sanitize(accountId),
                sanitize(clientOrderId),
                sanitize(executionId),
                executionType,
                orderStatus,
                lastPrice,
                lastQuantity,
                leavesQuantity,
                sanitize(text)
            );
    }

    private static String sanitize(String value) {
        return value.replace('\u0001', ' ').replace('|', ' ');
    }
}
