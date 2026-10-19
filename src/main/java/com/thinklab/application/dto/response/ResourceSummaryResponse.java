package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/** How many resources the tenant has, counted by provider, kind, state and how they are judged now. Counts only; nothing of the resources themselves. */
@Serdeable
public record ResourceSummaryResponse(int total, Map<String, Integer> byProvider, Map<String, Integer> byKind, Map<String, Integer> byState, Map<String, Integer> byPresence) {}
