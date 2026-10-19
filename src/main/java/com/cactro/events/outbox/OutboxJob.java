package com.cactro.events.outbox;

import java.util.UUID;

public record OutboxJob(UUID id, String jobType, String payload, int attempts) { }
