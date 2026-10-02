package br.com.mb.engine.journal;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.Order;

public final class NoOpBookJournal implements BookJournal {

    @Override
    public void appendAccepted(Order order, long entrySequence) {
    }

    @Override
    public void appendCancelled(BookOrder order) {
    }
}
