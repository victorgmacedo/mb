package br.com.mb.shared.fix;

public enum FixMessageType {
    NEW_ORDER_SINGLE("D"),
    ORDER_CANCEL_REQUEST("F"),
    FUNDING_CREDIT("U1"),
    LEDGER_TRADE_SETTLEMENT("U2"),
    LEDGER_RELEASE("U3"),
    BOOK_ORDER_ACCEPTED("U4"),
    BOOK_ORDER_CANCELLED("U5"),
    EXECUTION_REPORT("8"),
    BUSINESS_MESSAGE_REJECT("j");

    private final String tagValue;

    FixMessageType(String tagValue) {
        this.tagValue = tagValue;
    }

    public static FixMessageType fromTagValue(String tagValue) {
        for (var type : values()) {
            if (type.tagValue.equals(tagValue)) {
                return type;
            }
        }
        throw new InvalidFixMessageException("Unsupported FIX MsgType(35): " + tagValue);
    }

    public String tagValue() {
        return tagValue;
    }
}
