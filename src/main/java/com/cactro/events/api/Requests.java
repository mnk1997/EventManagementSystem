package com.cactro.events.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class Requests {
    private Requests() { }

    public record Register(@NotBlank @Email @Size(max = 320) String email,
                           @NotBlank @Size(min = 12, max = 72) String password) { }
    public record Login(@NotBlank @Email String email, @NotBlank String password) { }
    public record EventInput(@NotBlank @Size(max = 200) String name,
                             @NotBlank String description,
                             @NotNull @Future Instant startsAt,
                             @NotBlank @Size(max = 300) String venue,
                             @NotBlank @Size(max = 200) String location,
                             @Positive int capacity) { }
    public record BookingInput(@Positive int quantity) { }
    public record PageQuery(@Min(0) int page, @Min(1) @MaxPageSize int size) { }
}
