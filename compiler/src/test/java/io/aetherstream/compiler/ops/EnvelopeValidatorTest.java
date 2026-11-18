package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.SourcePosition;
import org.junit.jupiter.api.Test;
import static io.aetherstream.compiler.ops.OpsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeValidatorTest {
    @Test void validRecordUsesTrustedSourceAndIgnoresUnknownFields() {
        ObjectNode raw = position("e-1", "truck-007", 1234, 20);
        raw.putObject("source").put("partition", 99);
        raw.put("future", true);
        var accepted = (EnvelopeValidator.Accepted) EnvelopeValidator.parse("truck-events", 2, 42, raw.toString());
        assertThat(accepted.envelope().eventId()).isEqualTo("e-1");
        assertThat(accepted.envelope().payload().get("speedKmh").doubleValue()).isEqualTo(20);
        assertThat(accepted.envelope().source()).isEqualTo(new SourcePosition("truck-events", 2, 42));
        assertThat(EnvelopeValidator.parse("t", 0, 0, delivery("d", "truck-007", 20, 3).toString()))
                .isInstanceOf(EnvelopeValidator.Accepted.class);
    }

    @Test void malformedAndMissingRecordsKeepDeadLetterIdentity() {
        for (String raw : new String[]{"{", "", "{} {}"}) {
            DeadLetter rejected = reject(raw);
            assertThat(rejected.eventId()).isEqualTo("p2-o42");
            assertThat(rejected.reason()).isEqualTo("unparseable JSON");
            assertThat(rejected.eventTime()).isNull();
        }
        assertThat(reject("null").reason()).isEqualTo("expected JSON object");
        assertThat(reject("[]").reason()).isEqualTo("expected JSON object");
        assertThat(reject(null).reason()).isEqualTo("unparseable JSON");
        ObjectNode raw = position("e-1", "truck-007", 1234, 20);
        ((ObjectNode) raw.get("payload")).remove("speedKmh");
        DeadLetter rejected = reject(raw.toString());
        assertThat(rejected.eventId()).isEqualTo("e-1");
        assertThat(rejected.eventTime()).isEqualTo(1234L);
        assertThat(rejected.reason()).isEqualTo("missing field payload.speedKmh");
        assertThat(rejected.partition()).isEqualTo(2);
        assertThat(rejected.offset()).isEqualTo(42);
        assertThat(rejected.raw()).isEqualTo(raw.toString());
    }

    @Test void envelopeFieldsHaveStrictTypesAndPresence() {
        for (String field : new String[]{"eventId", "schemaVersion", "type", "eventTime", "key", "payload"}) {
            ObjectNode missing = position("e", "truck-007", 1, 0);
            missing.remove(field);
            assertThat(reject(missing.toString()).reason()).isEqualTo("missing field " + field);
            ObjectNode wrong = position("e", "truck-007", 1, 0);
            wrong.putNull(field);
            assertThat(reject(wrong.toString()).reason()).isEqualTo("invalid type " + field);
        }
        assertThat(reject(position("", "truck-007", 1, 0).toString()).reason()).contains("must not be empty");
        ObjectNode raw = position("e", "truck-007", 1, 0);
        raw.put("schemaVersion", 2);
        assertThat(reject(raw.toString()).reason()).isEqualTo("schemaVersion must be 1");
        raw.put("schemaVersion", 1.0);
        assertThat(reject(raw.toString()).reason()).isEqualTo("invalid type schemaVersion");
        raw.put("schemaVersion", 1).put("type", "unknown");
        assertThat(reject(raw.toString()).reason()).isEqualTo("unknown type unknown");
        raw.put("type", "truck.position").put("eventTime", "1");
        assertThat(reject(raw.toString()).reason()).isEqualTo("invalid type eventTime");
        raw.put("eventTime", 1).put("key", "other");
        assertThat(reject(raw.toString()).reason()).isEqualTo("key != payload.truckId");
    }

    @Test void validatesEveryPositionPayloadRule() {
        for (String field : new String[]{"truckId", "x", "y", "speedKmh", "heading", "routeId", "status"}) {
            ObjectNode raw = position("e", "truck-007", 1, 0);
            ((ObjectNode) raw.get("payload")).remove(field);
            assertThat(reject(raw.toString()).reason()).isEqualTo("missing field payload." + field);
        }
        for (String field : new String[]{"x", "y", "speedKmh", "heading"}) {
            ObjectNode raw = position("e", "truck-007", 1, 0);
            ((ObjectNode) raw.get("payload")).put(field, -1);
            assertThat(reject(raw.toString()).reason()).contains("payload." + field);
            ((ObjectNode) raw.get("payload")).put(field, "10");
            assertThat(reject(raw.toString()).reason()).isEqualTo("invalid type payload." + field);
        }
        for (String field : new String[]{"x", "y", "heading"}) {
            ObjectNode raw = position("e", "truck-007", 1, 0);
            ((ObjectNode) raw.get("payload")).put(field, 2001);
            assertThat(reject(raw.toString()).reason()).contains("payload." + field);
        }
        ObjectNode raw = position("e", "truck-007", 1, 0);
        ((ObjectNode) raw.get("payload")).put("status", "BROKEN");
        assertThat(reject(raw.toString()).reason()).isEqualTo("invalid payload.status");
    }

    @Test void deliveryRequiresIntegerPositiveParcelsButDoesNotConstrainCoordinates() {
        ObjectNode raw = delivery("d", "truck-007", 1, 3);
        ObjectNode payload = (ObjectNode) raw.get("payload");
        payload.put("x", -10).put("y", 3000);
        assertThat(EnvelopeValidator.parse("t", 2, 42, raw.toString())).isInstanceOf(EnvelopeValidator.Accepted.class);
        payload.put("parcels", 0);
        assertThat(reject(raw.toString()).reason()).isEqualTo("payload.parcels must be >= 1");
        payload.put("parcels", 1.5);
        assertThat(reject(raw.toString()).reason()).isEqualTo("invalid type payload.parcels");
        payload.put("parcels", 1).remove("stopId");
        assertThat(reject(raw.toString()).reason()).isEqualTo("missing field payload.stopId");
    }

    private static DeadLetter reject(String raw) {
        var result = EnvelopeValidator.parse("truck-events", 2, 42, raw);
        assertThat(result).isInstanceOf(EnvelopeValidator.Rejected.class);
        DeadLetter deadLetter = ((EnvelopeValidator.Rejected) result).deadLetter();
        assertThat(deadLetter.kind()).isEqualTo(DeadLetter.REJECTED);
        return deadLetter;
    }
}
