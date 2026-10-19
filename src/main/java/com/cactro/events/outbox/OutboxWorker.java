package com.cactro.events.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "app.email.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxRepository repository;
    private final EmailDelivery delivery;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int initialBackoffSeconds;
    private final int maxBackoffSeconds;

    public OutboxWorker(OutboxRepository repository, EmailDelivery delivery, ObjectMapper objectMapper,
                        @Value("${app.email.worker.batch-size:25}") int batchSize,
                        @Value("${app.email.worker.initial-backoff-seconds:30}") int initialBackoffSeconds,
                        @Value("${app.email.worker.max-backoff-seconds:900}") int maxBackoffSeconds) {
        this.repository = repository;
        this.delivery = delivery;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.initialBackoffSeconds = initialBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    @Scheduled(fixedDelayString = "${app.email.worker.fixed-delay-ms:5000}")
    public void poll() {
        processBatch();
    }

    public int processBatch() {
        var jobs = repository.claimBatch(batchSize);
        for (OutboxJob job : jobs) process(job);
        return jobs.size();
    }

    private void process(OutboxJob job) {
        try {
            EmailPayload payload = objectMapper.readValue(job.payload(), EmailPayload.class);
            switch (job.jobType()) {
                case "BOOKING_CONFIRMATION" -> delivery.sendBookingConfirmation(payload);
                case "EVENT_UPDATED_NOTIFICATION" -> delivery.sendEventUpdated(payload);
                default -> throw new IllegalArgumentException("Unsupported outbox job type");
            }
            repository.markCompleted(job.id());
        } catch (Exception exception) {
            boolean retryable = isRetryable(exception);
            String safeError = "Email job failed (" + exception.getClass().getSimpleName() + ")";
            repository.markFailed(job, safeError, retryable, backoffSeconds(job.attempts()));
            log.warn("Outbox job {} ({}) failed with {}", job.id(), job.jobType(), exception.getClass().getSimpleName());
        }
    }

    private long backoffSeconds(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        return Math.min(maxBackoffSeconds, (long) initialBackoffSeconds * multiplier);
    }

    private boolean isRetryable(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof MailAuthenticationException || current instanceof MailParseException
                    || current instanceof IllegalArgumentException
                    || current instanceof com.fasterxml.jackson.core.JsonProcessingException) return false;
            String message = current.getMessage();
            if (message != null) {
                Matcher smtpStatus = Pattern.compile("(?<!\\d)([45]\\d\\d)(?!\\d)").matcher(message);
                if (smtpStatus.find()) return smtpStatus.group(1).startsWith("4");
            }
            if (current instanceof jakarta.mail.MessagingException messagingException
                    && messagingException.getNextException() != null) {
                Throwable next = messagingException.getNextException();
                if (next == current) break;
                current = next;
            } else {
                current = current.getCause();
            }
        }
        return true;
    }
}
