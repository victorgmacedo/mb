package br.com.mb.engine.command;

public final class EngineEventFactory {

    public String accepted(EngineCommand command) {
        return switch (command) {
            case NewOrderSingleCommand newOrder -> executionReport(
                newOrder.accountId(),
                newOrder.clientOrderId(),
                "accepted-" + newOrder.clientOrderId(),
                "0",
                "0",
                "Order accepted"
            );
            case CancelOrderCommand cancelOrder -> executionReport(
                cancelOrder.accountId(),
                cancelOrder.clientOrderId(),
                "accepted-" + cancelOrder.clientOrderId(),
                "4",
                "4",
                "Order cancelled"
            );
        };
    }

    public String rejected(String key, String reason) {
        return "8=FIX.4.4\u000135=j\u000149=engine\u000156=%s\u000158=%s\u0001".formatted(key, sanitize(reason));
    }

    private static String executionReport(
        String accountId,
        String clientOrderId,
        String executionId,
        String executionType,
        String orderStatus,
        String text
    ) {
        return "8=FIX.4.4\u000135=8\u000149=engine\u000156=gateway\u00011=%s\u000111=%s\u000117=%s\u0001150=%s\u000139=%s\u000158=%s\u0001"
            .formatted(
                sanitize(accountId),
                sanitize(clientOrderId),
                sanitize(executionId),
                executionType,
                orderStatus,
                sanitize(text)
            );
    }

    private static String sanitize(String value) {
        return value.replace('\u0001', ' ').replace('|', ' ');
    }
}
