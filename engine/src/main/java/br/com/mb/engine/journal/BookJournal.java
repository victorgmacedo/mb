package br.com.mb.engine.journal;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.Order;

public interface BookJournal {

    void appendAccepted(Order order, long entrySequence);

    void appendCancelled(BookOrder order);
}
