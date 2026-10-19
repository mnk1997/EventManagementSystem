package com.cactro.events.outbox;

public record EmailPayload(String recipient, String bookingId, String eventId, String eventName,
                           String eventStartsAt, String venue, String location, Integer quantity) { }
