package br.com.mb.engine.book;

import java.util.ArrayList;
import java.util.List;

final class PriceLevel {

    private final long price;
    private BookOrder head;
    private BookOrder tail;

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

    List<BookOrder> orders() {
        var orders = new ArrayList<BookOrder>();
        var current = head;
        while (current != null) {
            orders.add(current);
            current = current.next;
        }
        return orders;
    }
}
