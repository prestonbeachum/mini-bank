package dev.minibank.common;

import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Reads an amount in minor units. JSON Schema treats {@code 233.0} as the integer 233, so it is
 * accepted; anything with a fractional part ({@code 10.5}) is rejected, never rounded or truncated.
 */
public class MinorUnits extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            BigDecimal value = p.getDecimalValue();
            try {
                return value.stripTrailingZeros().longValueExact();
            } catch (ArithmeticException notWhole) {
                throw InvalidFormatException.from(p, "amount must be a whole number of minor units", value, Long.class);
            }
        }
        throw InvalidFormatException.from(p, "amount must be a number", p.getString(), Long.class);
    }
}
