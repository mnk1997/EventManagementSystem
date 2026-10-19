package com.cactro.events.application;

import com.cactro.events.api.ApiException;
import com.cactro.events.api.Requests;
import com.cactro.events.security.JwtService;
import com.cactro.events.security.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public Map<String, Object> register(Requests.Register request) {
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes");
        UUID id = UUID.randomUUID();
        String email = request.email().trim().toLowerCase();
        jdbc.update("INSERT INTO app_user(id,email,password_hash,role) VALUES (?,?,?,?)",
                id, email, passwordEncoder.encode(request.password()), Role.CUSTOMER.name());
        return Map.of("id", id, "email", email, "role", Role.CUSTOMER.name());
    }

    public Map<String, Object> login(Requests.Login request) {
        var users = jdbc.query("SELECT id,email,password_hash,role FROM app_user WHERE email = ?",
                (rs, row) -> new User(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), Role.valueOf(rs.getString("role"))),
                request.email().trim().toLowerCase());
        if (users.isEmpty() || !passwordEncoder.matches(request.password(), users.getFirst().passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        User user = users.getFirst();
        return Map.of("accessToken", jwtService.issue(user.id(), user.email(), user.role()),
                "tokenType", "Bearer", "expiresIn", jwtService.expirationSeconds(),
                "user", Map.of("id", user.id(), "email", user.email(), "role", user.role().name()));
    }

    private record User(UUID id, String email, String passwordHash, Role role) { }
}
