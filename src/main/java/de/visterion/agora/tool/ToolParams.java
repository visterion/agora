package de.visterion.agora.tool;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** Shared argument-extraction helpers for tools reading caller-supplied {@link JsonNode} args.
 *  Reject blank/malformed input explicitly instead of silently coercing to null/defaults. */
public final class ToolParams {

    private ToolParams() { }

    /** Thrown when a caller-supplied argument is present but unusable. */
    public static final class InvalidArgumentException extends RuntimeException {
        public InvalidArgumentException(String message) { super(message); }
    }

    /** Required non-blank string; throws InvalidArgumentException("missing or blank argument: &lt;field&gt;"). */
    public static String requiredString(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field))
            throw new InvalidArgumentException("missing or blank argument: " + field);
        String value = args.get(field).asString();
        if (value == null || value.isBlank())
            throw new InvalidArgumentException("missing or blank argument: " + field);
        return value;
    }

    /** Optional decimal: null when absent/JSON-null. Accepts JSON numbers AND numeric strings ("5").
     *  Present-but-unparsable -&gt; InvalidArgumentException("invalid numeric argument: &lt;field&gt;"). */
    public static BigDecimal optionalDecimal(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) return null;
        JsonNode node = args.get(field);
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            String text = node.asString();
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new InvalidArgumentException("invalid numeric argument: " + field);
            }
        }
        throw new InvalidArgumentException("invalid numeric argument: " + field);
    }
}
