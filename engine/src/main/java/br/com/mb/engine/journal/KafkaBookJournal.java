package br.com.mb.engine.journal;

import br.com.mb.commandlog.CommandMessage;
import br.com.mb.commandlog.CommandPublisher;
import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.Order;
import br.com.mb.engine.domain.Side;
import java.util.Objects;

public final class KafkaBookJournal implements BookJournal {

    private final CommandPublisher publisher;
    private final String topic;

    public KafkaBookJournal(CommandPublisher publisher, String topic) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
    }

    @Override
    public void appendAccepted(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        publisher.publish(new CommandMessage(topic, order.instrument().symbol(), acceptedFix(order)));
    }

    @Override
    public void appendCancelled(BookOrder order) {
        Objects.requireNonNull(order, "order must not be null");
        publisher.publish(new CommandMessage(topic, order.instrument().symbol(), cancelledFix(order)));
    }

    private static String acceptedFix(Order order) {
        return "8=FIX.4.4\u000135=U4\u000149=engine\u000156=engine\u00011=%s\u000111=%s\u000155=%s\u000154=%s\u000144=%d\u000138=%d\u0001"
            .formatted(
                sanitize(order.accountId().value()),
                sanitize(order.clientOrderId().value()),
                sanitize(order.instrument().symbol()),
                sideTag(order.side()),
                order.price(),
                order.quantity()
            );
    }

    private static String cancelledFix(BookOrder order) {
        return "8=FIX.4.4\u000135=U5\u000149=engine\u000156=engine\u00011=%s\u000141=%s\u000155=%s\u0001"
            .formatted(
                sanitize(order.accountId().value()),
                sanitize(order.clientOrderId().value()),
                sanitize(order.instrument().symbol())
            );
    }

    private static String sideTag(Side side) {
        return switch (side) {
            case BUY -> "1";
            case SELL -> "2";
        };
    }

    private static String sanitize(String value) {
        return value.replace('\u0001', ' ').replace('|', ' ');
    }
}
