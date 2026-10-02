package br.com.mb.engine.journal;

import br.com.mb.engine.book.PlacementResult;
import br.com.mb.engine.domain.Order;

public interface ExecutionJournal {

    void append(Order takerOrder, PlacementResult placement);
}
