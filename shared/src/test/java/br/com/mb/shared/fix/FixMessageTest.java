package br.com.mb.shared.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FixMessageTest {

    @Test
    void parsesReadableFixAndNormalizesDelimiter() {
        var message = FixMessage.parse("8=FIX.4.4|35=D|49=gateway|56=engine|1=account-A|11=order-1|");

        assertEquals("account-A", message.kafkaKey());
        assertEquals(FixMessageType.NEW_ORDER_SINGLE, message.messageType());
        assertEquals("8=FIX.4.4\u000135=D\u000149=gateway\u000156=engine\u00011=account-A\u000111=order-1\u0001", message.normalized());
    }

    @Test
    void usesSenderCompIdAsFallbackKafkaKey() {
        var message = FixMessage.parse("8=FIX.4.4|35=F|49=gateway|56=engine|11=order-1|41=order-0|");

        assertEquals("gateway", message.kafkaKey());
        assertEquals(FixMessageType.ORDER_CANCEL_REQUEST, message.messageType());
    }

    @Test
    void rejectsUnsupportedMessageType() {
        var exception = assertThrows(
            InvalidFixMessageException.class,
            () -> FixMessage.parse("8=FIX.4.4|35=Z|49=gateway|")
        );

        assertEquals("Unsupported FIX MsgType(35): Z", exception.getMessage());
    }
}
