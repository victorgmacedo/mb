package br.com.mb.engine.domain;

import br.com.mb.engine.command.OrderSide;

public enum Side {
    BUY,
    SELL;

    public static Side fromCommandSide(OrderSide side) {
        return switch (side) {
            case BUY -> BUY;
            case SELL -> SELL;
        };
    }
}
