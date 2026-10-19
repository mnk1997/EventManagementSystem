package com.cactro.events.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxWorkerTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final EmailDelivery delivery = mock(EmailDelivery.class);
    private final OutboxWorker worker = new OutboxWorker(repository, delivery, new ObjectMapper(), 10, 30, 900);
    private final String payload = "{\"recipient\":\"person@example.test\",\"eventName\":\"Concert\",\"eventStartsAt\":\"2030-01-01T00:00:00Z\",\"venue\":\"Hall\",\"location\":\"Delhi\",\"quantity\":2}";

    @Test
    void successfulJobIsCompletedAndRepeatedPollDoesNotResendClaimedCompletedWork() {
        OutboxJob job = new OutboxJob(UUID.randomUUID(), "BOOKING_CONFIRMATION", payload, 1);
        when(repository.claimBatch(10)).thenReturn(List.of(job), List.of());

        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(worker.processBatch()).isZero();

        verify(delivery, times(1)).sendBookingConfirmation(any(EmailPayload.class));
        verify(repository).markCompleted(job.id());
        verify(repository, never()).markFailed(any(), anyString(), anyBoolean(), anyLong());
    }

    @Test
    void temporaryProviderFailureIsRetriedWithExponentialBackoff() {
        OutboxJob first = new OutboxJob(UUID.randomUUID(), "BOOKING_CONFIRMATION", payload, 1);
        OutboxJob second = new OutboxJob(first.id(), "BOOKING_CONFIRMATION", payload, 2);
        doThrow(new MailSendException("temporary relay outage")).when(delivery).sendBookingConfirmation(any());
        when(repository.claimBatch(10)).thenReturn(List.of(first), List.of(second));

        worker.processBatch();
        worker.processBatch();

        verify(repository).markFailed(first, "Email job failed (MailSendException)", true, 30);
        verify(repository).markFailed(second, "Email job failed (MailSendException)", true, 60);
    }

    @Test
    void authenticationFailureIsNotRetriedAndIsRecordedWithoutCredentialDetails() {
        OutboxJob job = new OutboxJob(UUID.randomUUID(), "BOOKING_CONFIRMATION", payload, 1);
        doThrow(new MailAuthenticationException("auth failed password=should-not-be-logged"))
                .when(delivery).sendBookingConfirmation(any());
        when(repository.claimBatch(10)).thenReturn(List.of(job));

        worker.processBatch();

        verify(repository).markFailed(job, "Email job failed (MailAuthenticationException)", false, 30);
    }

    @Test
    void workerProcessesMultipleEventNotificationsInOneBatch() {
        OutboxJob one = new OutboxJob(UUID.randomUUID(), "EVENT_UPDATED_NOTIFICATION", payload, 1);
        OutboxJob two = new OutboxJob(UUID.randomUUID(), "EVENT_UPDATED_NOTIFICATION", payload, 1);
        when(repository.claimBatch(10)).thenReturn(List.of(one, two));

        assertThat(worker.processBatch()).isEqualTo(2);

        verify(delivery, times(2)).sendEventUpdated(any(EmailPayload.class));
        verify(repository).markCompleted(one.id());
        verify(repository).markCompleted(two.id());
    }
}
