package io.aetherstream.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.HeartbeatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonRoundTripTest {
    @Test
    void commandsRoundTripWithTypeDiscriminator() throws Exception {
        var resp = new HeartbeatResponse(List.of(new Command.TriggerCheckpoint("e1", 2, 7), new Command.Kill(),
                new Command.SetSinkDelay(50)));
        String json = Json.MAPPER.writeValueAsString(resp);
        assertThat(json).contains("\"type\":\"TRIGGER_CHECKPOINT\"").contains("\"type\":\"KILL\"");
        HeartbeatResponse back = Json.MAPPER.readValue(json, HeartbeatResponse.class);
        assertThat(back).isEqualTo(resp);
    }
}
