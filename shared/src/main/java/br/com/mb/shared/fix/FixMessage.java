package br.com.mb.shared.fix;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FixMessage {

    public static final char SOH = '\u0001';
    private static final char READABLE_DELIMITER = '|';

    private final String normalized;
    private final Map<Integer, String> fields;

    private FixMessage(String normalized, Map<Integer, String> fields) {
        this.normalized = normalized;
        this.fields = Map.copyOf(fields);
    }

    public static FixMessage parse(String rawMessage) {
        Objects.requireNonNull(rawMessage, "rawMessage must not be null");
        if (rawMessage.isBlank()) {
            throw new InvalidFixMessageException("FIX message must not be blank");
        }

        var normalized = rawMessage.trim().replace(READABLE_DELIMITER, SOH);
        if (!normalized.endsWith(String.valueOf(SOH))) {
            normalized = normalized + SOH;
        }

        var fields = parseFields(normalized);
        require(fields, 8, "BeginString");
        FixMessageType.fromTagValue(require(fields, 35, "MsgType"));

        return new FixMessage(normalized, fields);
    }

    public String normalized() {
        return normalized;
    }

    public String kafkaKey() {
        return field(55)
            .or(() -> field(49))
            .orElseThrow(() -> new InvalidFixMessageException("FIX message requires Symbol/Asset(55) or SenderCompID(49)"));
    }

    public Optional<String> field(int tag) {
        return Optional.ofNullable(fields.get(tag));
    }

    public FixMessageType messageType() {
        return FixMessageType.fromTagValue(require(fields, 35, "MsgType"));
    }

    private static Map<Integer, String> parseFields(String normalized) {
        var fields = new LinkedHashMap<Integer, String>();
        for (var token : normalized.split(String.valueOf(SOH))) {
            if (token.isBlank()) {
                continue;
            }

            var separator = token.indexOf('=');
            if (separator <= 0 || separator == token.length() - 1) {
                throw new InvalidFixMessageException("Invalid FIX field: " + token);
            }

            var tag = parseTag(token.substring(0, separator));
            fields.put(tag, token.substring(separator + 1));
        }
        return fields;
    }

    private static int parseTag(String rawTag) {
        try {
            return Integer.parseInt(rawTag);
        } catch (NumberFormatException exception) {
            throw new InvalidFixMessageException("Invalid FIX tag: " + rawTag, exception);
        }
    }

    private static String require(Map<Integer, String> fields, int tag, String name) {
        var value = fields.get(tag);
        if (value == null || value.isBlank()) {
            throw new InvalidFixMessageException("FIX message requires " + name + "(" + tag + ")");
        }
        return value;
    }
}
