package com.cactro.events.security;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, Role role) {
}
