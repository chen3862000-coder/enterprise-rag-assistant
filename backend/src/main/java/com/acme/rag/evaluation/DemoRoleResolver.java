package com.acme.rag.evaluation;

import com.acme.rag.security.DemoIdentityService;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class DemoRoleResolver {
    private final DemoIdentityService identities;

    public DemoRoleResolver(DemoIdentityService identities) {
        this.identities = identities;
    }

    public Set<String> rolesFor(String username) {
        return identities.find(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluation identity"))
                .roles();
    }
}
