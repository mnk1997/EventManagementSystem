package com.cactro.events.api;

import com.cactro.events.application.EventService;
import com.cactro.events.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService events;
    public EventController(EventService events) { this.events = events; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventService.EventView create(@Valid @RequestBody Requests.EventInput request,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return events.create(request, user);
    }

    @PutMapping("/{eventId}")
    public EventService.EventView update(@PathVariable UUID eventId, @Valid @RequestBody Requests.EventInput request,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return events.update(eventId, request, user);
    }

    @GetMapping
    public EventService.Page<EventService.EventView> browse(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return events.browse(from, to, location, page, size);
    }

    @GetMapping("/{eventId}")
    public EventService.EventView get(@PathVariable UUID eventId) { return events.find(eventId); }
}
