package io.aetherstream.common;

/** Output of a rule node (contract 02 section 4.4). */
public record Alert(String alertId, String key, String ruleId, String message, long eventTime) {}
