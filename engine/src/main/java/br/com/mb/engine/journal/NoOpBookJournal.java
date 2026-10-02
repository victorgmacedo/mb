package br.com.mb.engine.journal;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.Order;
import java.time.Instant;

public final class NoOpBookJournal implements BookJournal {

    @Override
    public void appendAccepted(Order order, long entrySequence, Instant enteredAt) {
    }

    @Override
    public void appendCancelled(BookOrder order) {
    }
}
