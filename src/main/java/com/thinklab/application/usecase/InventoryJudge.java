package com.thinklab.application.usecase;

import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Presence;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Judges a resource when it is read (ADR-032), against the clock and the time the tool may stay silent about it ({@code thinklab.cloud-inventory.missing-after-hours}). */
@Singleton
public class InventoryJudge {

    private final Clock clock;
    private final Duration missingAfter;

    public InventoryJudge(Clock clock, @Property(name = "thinklab.cloud-inventory.missing-after-hours", defaultValue = "48") long missingAfterHours) {
        this.clock = clock;
        this.missingAfter = Duration.ofHours(missingAfterHours);
    }

    public Presence presenceOf(CloudResource resource) {
        return resource.assess(Instant.now(clock), missingAfter);
    }
}
