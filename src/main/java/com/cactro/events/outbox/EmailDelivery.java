package com.cactro.events.outbox;

public interface EmailDelivery {
    void sendBookingConfirmation(EmailPayload payload);
    void sendEventUpdated(EmailPayload payload);
}
