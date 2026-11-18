package io.aetherstream.common;

import com.fasterxml.jackson.databind.JsonNode;

/** Engine envelope (contract 01 section 2). source is filled by the engine source, never by producers. */
public record Envelope(String eventId, int schemaVersion, String type, long eventTime, String key,
                       JsonNode payload, SourcePosition source) {}
