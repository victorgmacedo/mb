package br.com.mb.engine.domain;

import br.com.mb.shared.model.Asset;
import java.util.Objects;

public record ListedInstrument(Instrument instrument, Asset baseAsset, Asset quoteAsset) {

    public ListedInstrument {
        Objects.requireNonNull(instrument, "instrument must not be null");
        Objects.requireNonNull(baseAsset, "baseAsset must not be null");
        Objects.requireNonNull(quoteAsset, "quoteAsset must not be null");
        if (baseAsset.equals(quoteAsset)) {
            throw new IllegalArgumentException("base and quote assets must be different");
        }
    }
}
