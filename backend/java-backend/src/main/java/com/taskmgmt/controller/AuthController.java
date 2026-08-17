package com.taskmgmt.controller;

import com.taskmgmt.entity.Role;
import com.taskmgmt.entity.User;
import com.taskmgmt.repository.RoleRepository;
import com.taskmgmt.repository.UserRepository;
import com.taskmgmt.security.JwtService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password) {}
    public record RegisterResponse(String id, String email, String role) {}

    public record LoginRequest(String email, String password) {}
    public record LoginResponse(String token, String email, String role) {}

    /**
     * Public self-registration. Previously missing entirely — the API
     * docs (docs/02-API-Documentation.md) described this endpoint, but
     * the only way to create a user was the seed SQL script, so there
     * was no actual answer to "how does a new user get an account in
     * production?". Every new registrant gets MEMBER — promoting
     * someone to MANAGER/ADMIN is an intentionally separate, privileged
     * action (not implemented here yet; today that's a direct DB
     * update by an operator, same as the seed data).
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@jakarta.validation.Valid @RequestBody RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An account with this email already exists");
        }

        Role memberRole = roleRepository.findByName("MEMBER")
                .orElseThrow(() -> new IllegalStateException("MEMBER role missing — check roles seed data"));

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(memberRole);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(user.getId().toString(), user.getEmail(), memberRole.getName()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
        }
        String token = jwtService.issueToken(user);
        return ResponseEntity.ok(new LoginResponse(token, user.getEmail(), user.getRole().getName()));
    }
}
