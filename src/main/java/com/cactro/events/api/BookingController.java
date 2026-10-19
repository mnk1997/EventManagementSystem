package com.cactro.events.api;

import com.cactro.events.application.BookingService;
import com.cactro.events.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class BookingController {
    private final BookingService bookings;
    public BookingController(BookingService bookings) { this.bookings = bookings; }

    @PostMapping("/api/events/{eventId}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingService.BookingView create(@PathVariable UUID eventId, @Valid @RequestBody Requests.BookingInput request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return bookings.create(eventId, request.quantity(), idempotencyKey, user);
    }

    @GetMapping("/api/bookings")
    public List<BookingService.BookingView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return bookings.list(user.id());
    }

    @GetMapping("/api/bookings/{bookingId}")
    public BookingService.BookingView get(@PathVariable UUID bookingId, @AuthenticationPrincipal AuthenticatedUser user) {
        return bookings.get(bookingId, user.id());
    }

    @PostMapping("/api/bookings/{bookingId}/cancel")
    public BookingService.BookingView cancel(@PathVariable UUID bookingId, @AuthenticationPrincipal AuthenticatedUser user) {
        return bookings.cancel(bookingId, user);
    }
}
