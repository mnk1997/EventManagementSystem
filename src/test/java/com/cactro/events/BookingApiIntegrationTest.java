package com.cactro.events;

import com.cactro.events.security.JwtService;
import com.cactro.events.security.Role;
import com.cactro.events.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingApiIntegrationTest {
    private static final String JWT_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        registry.add("app.email.worker.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxRepository outboxRepository;

    UUID organizer;
    UUID otherOrganizer;
    UUID customer;
    UUID secondCustomer;
    UUID eventId;
    String organizerToken;
    String otherOrganizerToken;
    String customerToken;
    String secondCustomerToken;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_job");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM booking");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM app_user");
        organizer = user("organizer@example.test", Role.EVENT_ORGANIZER);
        otherOrganizer = user("other@example.test", Role.EVENT_ORGANIZER);
        customer = user("customer@example.test", Role.CUSTOMER);
        secondCustomer = user("second@example.test", Role.CUSTOMER);
        organizerToken = token(organizer, "organizer@example.test", Role.EVENT_ORGANIZER);
        otherOrganizerToken = token(otherOrganizer, "other@example.test", Role.EVENT_ORGANIZER);
        customerToken = token(customer, "customer@example.test", Role.CUSTOMER);
        secondCustomerToken = token(secondCustomer, "second@example.test", Role.CUSTOMER);
        eventId = event(organizer, 5);
    }

    @Test
    void registrationHashesPasswordAndLoginIssuesJwt() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.test\",\"password\":\"long-safe-password\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CUSTOMER"));
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email='new@example.test'", String.class);
        assertThat(hash).isNotEqualTo("long-safe-password");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.test\",\"password\":\"long-safe-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void protectsWriteEndpointsAndValidatesRequests() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/api/events").header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(5)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/events").header("Authorization", bearer(organizerToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors").exists());
    }

    @Test
    void organizerCanUpdateOwnEventButCannotUpdateAnotherOrganizersEvent() throws Exception {
        mvc.perform(put("/api/events/" + eventId).header("Authorization", bearer(otherOrganizerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(8)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/events/" + eventId).header("Authorization", bearer(organizerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(8)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(8));
    }

    @Test
    void rejectsSoldOutBookingAndSupportsOwnerCancellationWithoutDoubleRelease() throws Exception {
        eventId = event(organizer, 1);
        mvc.perform(post("/api/events/" + eventId + "/bookings").header("Authorization", bearer(customerToken))
                        .header("Idempotency-Key", "book-one").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isConflict());
        String booking = mvc.perform(post("/api/events/" + eventId + "/bookings").header("Authorization", bearer(customerToken))
                        .header("Idempotency-Key", "book-two").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_job WHERE job_type='BOOKING_CONFIRMATION'", Integer.class)).isEqualTo(1);
        UUID bookingId = UUID.fromString(mapper.readTree(booking).get("id").asText());
        mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", bearer(secondCustomerToken)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", bearer(customerToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", bearer(customerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", bearer(customerToken)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT booked_tickets FROM event WHERE id=?", Integer.class, eventId)).isZero();
    }

    @Test
    void idempotentReplayDoesNotDoubleBookAndCustomerLimitIsEnforced() throws Exception {
        String first = mvc.perform(post("/api/events/" + eventId + "/bookings").header("Authorization", bearer(customerToken))
                        .header("Idempotency-Key", "same-booking").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/events/" + eventId + "/bookings").header("Authorization", bearer(customerToken))
                        .header("Idempotency-Key", "same-booking").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(first).get("id").asText()).isEqualTo(mapper.readTree(replay).get("id").asText());
        mvc.perform(post("/api/events/" + eventId + "/bookings").header("Authorization", bearer(customerToken))
                        .header("Idempotency-Key", "over-limit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":4}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT booked_tickets FROM event WHERE id=?", Integer.class, eventId)).isEqualTo(2);
    }

    @Test
    void eventUpdateCreatesOneNotificationPerDistinctBookedCustomer() throws Exception {
        book(customerToken, "event-a");
        book(secondCustomerToken, "event-b");
        mvc.perform(put("/api/events/" + eventId).header("Authorization", bearer(organizerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(5)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_job WHERE job_type='EVENT_UPDATED_NOTIFICATION' AND aggregate_id=?",
                Integer.class, eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_job WHERE job_type='BOOKING_CONFIRMATION' AND aggregate_id IN " +
                "(SELECT id FROM booking WHERE event_id=?)", Integer.class, eventId)).isEqualTo(2);
    }

    @Test
    void outboxClaimIncrementsAttemptAndTransientFailureSchedulesRetry() {
        book(customerToken, "retry-test");
        var claimed = outboxRepository.claimBatch(1);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().jobType()).isEqualTo("BOOKING_CONFIRMATION");
        assertThat(claimed.getFirst().attempts()).isEqualTo(1);

        outboxRepository.markFailed(claimed.getFirst(), "Email job failed (MailSendException)", true, 30);

        assertThat(jdbc.queryForObject("SELECT status FROM outbox_job WHERE id=?", String.class, claimed.getFirst().id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_job WHERE id=?", Integer.class, claimed.getFirst().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_attempt_at IS NOT NULL FROM outbox_job WHERE id=?", Boolean.class, claimed.getFirst().id())).isTrue();
    }

    @Test
    void simultaneousCustomersCompetingForFinalTicketCannotOversell() throws Exception {
        eventId = event(organizer, 1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> book(customerToken, "race-a"));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> book(secondCustomerToken, "race-b"));
        int firstStatus = first.join();
        int secondStatus = second.join();
        assertThat(java.util.List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT booked_tickets FROM event WHERE id=?", Integer.class, eventId)).isEqualTo(1);
    }

    private int book(String token, String key) {
        try {
            return mvc.perform(post("/api/events/" + eventId + "/bookings").header("Authorization", bearer(token))
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private UUID user(String email, Role role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,email,password_hash,role) VALUES (?,?,?,?)", id, email, encoder.encode("correct-horse-battery-staple"), role.name());
        return id;
    }

    private UUID event(UUID organizerId, int capacity) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,organizer_id,name,description,starts_at,venue,location,capacity,status) VALUES (?,?,?,?,?,?,?,?, 'PUBLISHED')",
                id, organizerId, "Test event", "Description", java.sql.Timestamp.from(Instant.now().plusSeconds(10L * 24 * 3600)),
                "Hall", "Delhi", capacity);
        return id;
    }

    private String token(UUID id, String email, Role role) { return jwt.issue(id, email, role); }
    private String bearer(String token) { return "Bearer " + token; }
    private String eventJson(int capacity) {
        return "{\"name\":\"Event\",\"description\":\"Details\",\"startsAt\":\"" +
                Instant.now().plusSeconds(20L * 24 * 3600) + "\",\"venue\":\"Hall\",\"location\":\"Delhi\",\"capacity\":" + capacity + "}";
    }
}
