package com.cactro.events.application;

import com.cactro.events.api.ApiException;
import com.cactro.events.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final int maxTicketsPerCustomer;

    public BookingService(JdbcTemplate jdbc, @Value("${app.booking.max-tickets-per-customer:5}") int maxTicketsPerCustomer) {
        this.jdbc = jdbc;
        this.maxTicketsPerCustomer = maxTicketsPerCustomer;
    }

    @Transactional
    public BookingView create(UUID eventId, int quantity, String idempotencyKey, AuthenticatedUser customer) {
        if (quantity <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new ApiException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key header is required");

        // Serializes same-customer requests; the event lock below serializes inventory changes per event.
        jdbc.query("SELECT id FROM app_user WHERE id=? FOR UPDATE", rs -> { }, customer.id());
        String requestHash = sha256(eventId + ":" + quantity);
        var prior = jdbc.query("SELECT request_hash,booking_id FROM idempotency_record WHERE customer_id=? AND idempotency_key=?",
                (rs, n) -> new Prior(rs.getString(1), rs.getObject(2, UUID.class)), customer.id(), idempotencyKey);
        if (!prior.isEmpty()) {
            Prior found = prior.getFirst();
            if (!found.requestHash().equals(requestHash)) throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different request");
            if (found.bookingId() != null) return get(found.bookingId(), customer.id());
        }

        var eventRows = jdbc.query("SELECT id,capacity,booked_tickets,status,starts_at FROM event WHERE id=? FOR UPDATE",
                (rs, n) -> new EventInventory(rs.getInt("capacity"), rs.getInt("booked_tickets"), rs.getString("status"), rs.getTimestamp("starts_at").toInstant()), eventId);
        if (eventRows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Event not found");
        EventInventory event = eventRows.getFirst();
        if (!"PUBLISHED".equals(event.status()) || !event.startsAt().isAfter(Instant.now()))
            throw new ApiException(HttpStatus.CONFLICT, "Event is not available for booking");

        Integer customerBooked = jdbc.queryForObject("SELECT COALESCE(sum(ticket_quantity),0) FROM booking WHERE event_id=? AND customer_id=? AND status='CONFIRMED'",
                Integer.class, eventId, customer.id());
        if (customerBooked + quantity > maxTicketsPerCustomer)
            throw new ApiException(HttpStatus.CONFLICT, "Booking exceeds the per-customer ticket limit");
        int inventoryUpdated = jdbc.update("UPDATE event SET booked_tickets=booked_tickets+?,updated_at=CURRENT_TIMESTAMP " +
                "WHERE id=? AND status='PUBLISHED' AND starts_at>CURRENT_TIMESTAMP AND booked_tickets+?<=capacity", quantity, eventId, quantity);
        if (inventoryUpdated != 1) throw new ApiException(HttpStatus.CONFLICT, "Not enough tickets are available");

        UUID bookingId = UUID.randomUUID();
        jdbc.update("INSERT INTO booking(id,event_id,customer_id,ticket_quantity,status) VALUES (?,?,?,?,'CONFIRMED')",
                bookingId, eventId, customer.id(), quantity);
        if (prior.isEmpty()) {
            jdbc.update("INSERT INTO idempotency_record(id,customer_id,idempotency_key,request_hash,booking_id) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), customer.id(), idempotencyKey, requestHash, bookingId);
        } else {
            jdbc.update("UPDATE idempotency_record SET booking_id=? WHERE customer_id=? AND idempotency_key=?",
                    bookingId, customer.id(), idempotencyKey);
        }
        jdbc.update("INSERT INTO outbox_job(id,deduplication_key,job_type,aggregate_type,aggregate_id,payload) " +
                        "SELECT ?, 'booking-confirmation:' || b.id::text, 'BOOKING_CONFIRMATION','BOOKING',b.id, " +
                        "jsonb_build_object('recipient',u.email,'bookingId',b.id::text,'eventId',e.id::text,'eventName',e.name, " +
                        "'eventStartsAt',e.starts_at::text,'venue',e.venue,'location',e.location,'quantity',b.ticket_quantity) " +
                        "FROM booking b JOIN app_user u ON u.id=b.customer_id JOIN event e ON e.id=b.event_id WHERE b.id=?",
                UUID.randomUUID(), bookingId);
        return get(bookingId, customer.id());
    }

    public List<BookingView> list(UUID customerId) {
        return jdbc.query("SELECT b.*,e.name AS event_name,e.starts_at FROM booking b JOIN event e ON e.id=b.event_id " +
                "WHERE b.customer_id=? ORDER BY b.created_at DESC", (rs, n) -> map(rs), customerId);
    }

    public BookingView get(UUID bookingId, UUID customerId) {
        return jdbc.query("SELECT b.*,e.name AS event_name,e.starts_at FROM booking b JOIN event e ON e.id=b.event_id " +
                "WHERE b.id=? AND b.customer_id=?", (rs, n) -> map(rs), bookingId, customerId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    @Transactional
    public BookingView cancel(UUID bookingId, AuthenticatedUser customer) {
        // Lock the customer first, matching booking creation's lock order.
        jdbc.query("SELECT id FROM app_user WHERE id=? FOR UPDATE", rs -> { }, customer.id());
        var rows = jdbc.query("SELECT event_id,status,ticket_quantity FROM booking WHERE id=? AND customer_id=? FOR UPDATE",
                (rs, n) -> new CancellationRow(rs.getObject("event_id", UUID.class), rs.getString("status"), rs.getInt("ticket_quantity")),
                bookingId, customer.id());
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Booking not found");
        CancellationRow booking = rows.getFirst();
        if ("CANCELLED".equals(booking.status())) return get(bookingId, customer.id());

        var events = jdbc.query("SELECT starts_at FROM event WHERE id=? FOR UPDATE", (rs, n) -> rs.getTimestamp(1).toInstant(), booking.eventId());
        Instant startsAt = events.getFirst();
        if (!startsAt.isAfter(Instant.now().plusSeconds(48 * 60 * 60L)))
            throw new ApiException(HttpStatus.CONFLICT, "Bookings can only be cancelled at least 48 hours before the event");
        jdbc.update("UPDATE booking SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                "WHERE id=? AND status='CONFIRMED'", bookingId);
        int updated = jdbc.update("UPDATE event SET booked_tickets=booked_tickets-?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND booked_tickets>=?",
                booking.quantity(), booking.eventId(), booking.quantity());
        if (updated != 1) throw new IllegalStateException("Booking inventory invariant violated");
        return get(bookingId, customer.id());
    }

    private BookingView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BookingView(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getInt("ticket_quantity"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
                rs.getString("event_name"), rs.getTimestamp("starts_at").toInstant());
    }

    private String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Prior(String requestHash, UUID bookingId) { }
    private record EventInventory(int capacity, int booked, String status, Instant startsAt) { }
    private record CancellationRow(UUID eventId, String status, int quantity) { }
    public record BookingView(UUID id, UUID eventId, int quantity, String status, Instant createdAt,
                              Instant cancelledAt, String eventName, Instant eventStartsAt) { }
}
