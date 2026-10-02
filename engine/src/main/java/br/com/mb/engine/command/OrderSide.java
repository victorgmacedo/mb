package br.com.mb.engine.command;

import br.com.mb.shared.fix.InvalidFixMessageException;

public enum OrderSide {
    BUY("1"),
    SELL("2");

    private final String fixValue;

    OrderSide(String fixValue) {
        this.fixValue = fixValue;
    }

    public static OrderSide fromFixValue(String fixValue) {
        for (var side : values()) {
            if (side.fixValue.equals(fixValue)) {
                return side;
            }
        }
        throw new InvalidFixMessageException("Unsupported FIX Side(54): " + fixValue);
    }

    public String fixValue() {
        return fixValue;
    }
}
