package com.acme.rag.security;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class DemoIdentityService {
    private static final Map<String, DemoIdentity> IDENTITIES = Map.of(
            "alice", new DemoIdentity("alice", Set.of("ENGINEERING", "PUBLIC"), false),
            "bob", new DemoIdentity("bob", Set.of("HR", "PUBLIC"), false),
            "carol", new DemoIdentity("carol", Set.of("SECURITY_ADMIN", "PUBLIC"), true));

    public Optional<DemoIdentity> find(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(IDENTITIES.get(username.strip().toLowerCase(java.util.Locale.ROOT)));
    }
}
