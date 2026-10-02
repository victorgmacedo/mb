package br.com.mb.engine.book;

final class PriceLevel {

    private final long price;
    private BookOrder head;
    private BookOrder tail;
    private long totalQuantity;

    PriceLevel(long price) {
        this.price = price;
    }

    void append(BookOrder order) {
        if (tail == null) {
            head = order;
            tail = order;
        } else {
            tail.next = order;
            order.previous = tail;
            tail = order;
        }
        totalQuantity += order.remainingQuantity();
    }

    void remove(BookOrder order) {
        if (order.previous != null) {
            order.previous.next = order.next;
        } else {
            head = order.next;
        }

        if (order.next != null) {
            order.next.previous = order.previous;
        } else {
            tail = order.previous;
        }

        totalQuantity -= order.remainingQuantity();
        order.previous = null;
        order.next = null;
    }

    BookOrder head() {
        return head;
    }

    boolean isEmpty() {
        return head == null;
    }

    long price() {
        return price;
    }

    long totalQuantity() {
        return totalQuantity;
    }
}
