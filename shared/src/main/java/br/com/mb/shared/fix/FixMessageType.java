package br.com.mb.shared.fix;

public enum FixMessageType {
    NEW_ORDER_SINGLE("D"),
    ORDER_CANCEL_REQUEST("F");

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
