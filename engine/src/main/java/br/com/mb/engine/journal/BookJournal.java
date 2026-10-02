package br.com.mb.engine.journal;

import br.com.mb.engine.book.BookOrder;
import br.com.mb.engine.domain.Order;
import java.time.Instant;

public interface BookJournal {

    void appendAccepted(Order order, long entrySequence, Instant enteredAt);

    void appendCancelled(BookOrder order);
}
