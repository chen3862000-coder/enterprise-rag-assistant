package com.acme.rag.security;

import java.util.Set;

public record DemoIdentity(String username, Set<String> roles, boolean documentAdmin) {
    public DemoIdentity {
        roles = Set.copyOf(roles);
    }
}
