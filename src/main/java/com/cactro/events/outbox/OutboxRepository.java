package com.cactro.events.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final int maxAttempts;
    private final long leaseSeconds;

    public OutboxRepository(JdbcTemplate jdbc,
                            @Value("${app.email.worker.max-attempts:5}") int maxAttempts,
                            @Value("${app.email.worker.lease-seconds:600}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
    }

    @Transactional
    public List<OutboxJob> claimBatch(int batchSize) {
        jdbc.update("UPDATE outbox_job SET status='DEAD',locked_until=NULL,updated_at=CURRENT_TIMESTAMP, " +
                        "last_error=COALESCE(last_error,'Worker lease expired after the retry limit') " +
                        "WHERE status='PROCESSING' AND locked_until<=CURRENT_TIMESTAMP AND attempts>=?", maxAttempts);
        return jdbc.query("WITH candidate AS (" +
                        " SELECT id FROM outbox_job " +
                        " WHERE (status='PENDING' AND available_at<=CURRENT_TIMESTAMP AND attempts<?) " +
                        "    OR (status='PROCESSING' AND locked_until<=CURRENT_TIMESTAMP AND attempts<?) " +
                        " ORDER BY available_at,created_at LIMIT ? FOR UPDATE SKIP LOCKED" +
                        ") UPDATE outbox_job j SET status='PROCESSING',attempts=j.attempts+1, " +
                        "locked_until=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'),last_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "FROM candidate c WHERE j.id=c.id RETURNING j.id,j.job_type,j.payload::text AS payload,j.attempts",
                (rs, row) -> new OutboxJob(rs.getObject("id", UUID.class), rs.getString("job_type"), rs.getString("payload"), rs.getInt("attempts")),
                maxAttempts, maxAttempts, batchSize, leaseSeconds);
    }

    @Transactional
    public void markCompleted(UUID id) {
        jdbc.update("UPDATE outbox_job SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP,locked_until=NULL, " +
                "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING'", id);
    }

    @Transactional
    public void markFailed(OutboxJob job, String safeError, boolean retryable, long backoffSeconds) {
        boolean retry = retryable && job.attempts() < maxAttempts;
        if (retry) {
            jdbc.update("UPDATE outbox_job SET status='PENDING',available_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'), " +
                            "locked_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PROCESSING'",
                    backoffSeconds, safeError, job.id());
        } else {
            jdbc.update("UPDATE outbox_job SET status='DEAD',locked_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP " +
                    "WHERE id=? AND status='PROCESSING'", safeError, job.id());
        }
    }
}
