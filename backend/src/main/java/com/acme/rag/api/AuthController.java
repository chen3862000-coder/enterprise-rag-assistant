package com.acme.rag.api;

import com.acme.rag.security.DemoAuthenticationService;
import com.acme.rag.security.DemoIdentity;
import com.acme.rag.security.DemoIdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final DemoAuthenticationService authentication;
    private final DemoIdentityService identities;

    public AuthController(DemoAuthenticationService authentication, DemoIdentityService identities) {
        this.authentication = authentication;
        this.identities = identities;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            DemoIdentity identity = authentication.login(request.username());
            return ResponseEntity.ok(new LoginResponse(identity.username(), identity.roles(), identity.documentAdmin()));
        } catch (BadCredentialsException exception) {
            return ResponseEntity.status(401).build();
        }
    }

    @org.springframework.web.bind.annotation.GetMapping("/me")
    public ResponseEntity<LoginResponse> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        DemoIdentity identity = identities.find(authentication.getName()).orElse(null);
        if (identity == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new LoginResponse(identity.username(), identity.roles(), identity.documentAdmin()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authentication.logout();
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(@NotBlank String username) {}

    public record LoginResponse(String username, java.util.Set<String> roles, boolean documentAdmin) {
        public LoginResponse {
            roles = java.util.Set.copyOf(roles);
        }
    }
}
