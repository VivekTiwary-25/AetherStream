package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Envelope;
import org.junit.jupiter.api.Test;
import static io.aetherstream.compiler.ops.OpsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConditionTest {
    @Test void missingFieldsNeverMatchEvenInequalityAndTypesDoNotCoerce() {
        Envelope event = event();
        assertThat(matches(event, "payload.absent", "!=", "1")).isFalse();
        assertThat(matches(event, "payload.speedKmh", "==", "\"10\"")).isFalse();
        assertThat(matches(event, "type", "==", "\"truck.position\"")).isTrue();
        assertThat(matches(event, "type", "!=", "\"truck.delivery\"")).isTrue();
        assertThat(matches(event, "type", ">", "\"truck.delivery\"")).isFalse();
        assertThat(matches(event, "payload.enabled", "==", "true")).isTrue();
        assertThat(matches(event, "payload.enabled", "!=", "false")).isTrue();
        assertThat(matches(event, "payload.enabled", ">=", "true")).isFalse();
    }

    @Test void numericOperatorsCompareValuesNotJsonNumberRepresentations() {
        Envelope event = event();
        assertThat(matches(event, "payload.speedKmh", "==", "10.0")).isTrue();
        assertThat(matches(event, "payload.speedKmh", "!=", "10")).isFalse();
        assertThat(matches(event, "payload.speedKmh", ">", "9")).isTrue();
        assertThat(matches(event, "payload.speedKmh", ">=", "10")).isTrue();
        assertThat(matches(event, "payload.speedKmh", "<", "11")).isTrue();
        assertThat(matches(event, "payload.speedKmh", "<=", "10")).isTrue();
        assertThat(matches(event, "eventTime", "==", "9007199254740993")).isTrue();
        assertThat(matches(event, "eventTime", "==", "9007199254740992")).isFalse();
    }

    private static Envelope event() {
        ObjectNode raw = position("p", "truck-007", 9007199254740993L, 10);
        ((ObjectNode) raw.get("payload")).put("enabled", true);
        return ((EnvelopeValidator.Accepted) EnvelopeValidator.parse("truck-events", 0, 0, raw.toString())).envelope();
    }

    private static boolean matches(Envelope event, String field, String op, String value) {
        return new Condition(json("{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + value + "}")).matches(event);
    }
}
