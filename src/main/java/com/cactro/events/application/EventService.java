package com.cactro.events.application;

import com.cactro.events.api.ApiException;
import com.cactro.events.api.Requests;
import com.cactro.events.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventService {
    private final JdbcTemplate jdbc;
    public EventService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public EventView create(Requests.EventInput input, AuthenticatedUser organizer) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,organizer_id,name,description,starts_at,venue,location,capacity,status) " +
                        "VALUES (?,?,?,?,?,?,?,?,'PUBLISHED')", id, organizer.id(), input.name().trim(),
                input.description(), Timestamp.from(input.startsAt()), input.venue().trim(), input.location().trim(), input.capacity());
        return find(id);
    }

    @Transactional
    public EventView update(UUID id, Requests.EventInput input, AuthenticatedUser organizer) {
        UUID updateId = UUID.randomUUID();
        int changed = jdbc.update("UPDATE event SET name=?,description=?,starts_at=?,venue=?,location=?,capacity=?,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND organizer_id=? AND capacity >= booked_tickets",
                input.name().trim(), input.description(), Timestamp.from(input.startsAt()), input.venue().trim(),
                input.location().trim(), input.capacity(), id, organizer.id());
        if (changed == 0) {
            boolean exists = !jdbc.query("SELECT id FROM event WHERE id=? AND organizer_id=?", (rs, n) -> rs.getObject(1), id, organizer.id()).isEmpty();
            if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "Event not found");
            throw new ApiException(HttpStatus.CONFLICT, "Capacity cannot be lower than tickets already booked");
        }
        jdbc.update("INSERT INTO outbox_job(id,deduplication_key,job_type,aggregate_type,aggregate_id,payload) " +
                        "SELECT gen_random_uuid(), 'event-update:' || ? || ':' || u.id::text, 'EVENT_UPDATED_NOTIFICATION','EVENT',e.id, " +
                        "jsonb_build_object('recipient',u.email,'eventId',e.id::text,'eventName',e.name, " +
                        "'eventStartsAt',e.starts_at::text,'venue',e.venue,'location',e.location) " +
                        "FROM event e JOIN booking b ON b.event_id=e.id AND b.status='CONFIRMED' " +
                        "JOIN app_user u ON u.id=b.customer_id WHERE e.id=? GROUP BY u.id,u.email,e.id,e.name,e.starts_at,e.venue,e.location",
                updateId.toString(), id);
        return find(id);
    }

    public EventView find(UUID id) {
        return jdbc.query("SELECT e.*, e.capacity-e.booked_tickets AS available_tickets FROM event e WHERE id=? AND status='PUBLISHED'",
                (rs, n) -> map(rs), id).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    public Page<EventView> browse(Instant from, Instant to, String location, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size must be between 1 and 100");
        if (from != null && to != null && !from.isBefore(to)) throw new ApiException(HttpStatus.BAD_REQUEST, "from must be earlier than to");
        StringBuilder where = new StringBuilder(" WHERE status='PUBLISHED'");
        var args = new java.util.ArrayList<Object>();
        if (from != null) { where.append(" AND starts_at >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { where.append(" AND starts_at < ?"); args.add(Timestamp.from(to)); }
        if (location != null && !location.isBlank()) { where.append(" AND location ILIKE ?"); args.add("%" + location.trim() + "%"); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM event" + where, Long.class, args.toArray());
        var pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size); pageArgs.add((long) page * size);
        List<EventView> items = jdbc.query("SELECT e.*,e.capacity-e.booked_tickets AS available_tickets FROM event e" + where +
                " ORDER BY starts_at,id LIMIT ? OFFSET ?", (rs, n) -> map(rs), pageArgs.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    private EventView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventView(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getTimestamp("starts_at").toInstant(), rs.getString("venue"), rs.getString("location"),
                rs.getInt("capacity"), rs.getInt("available_tickets"), rs.getString("status"));
    }

    public record EventView(UUID id, UUID organizerId, String name, String description, Instant startsAt,
                            String venue, String location, int capacity, int availableTickets, String status) { }
    public record Page<T>(List<T> items, int page, int size, long totalItems) { }
}
