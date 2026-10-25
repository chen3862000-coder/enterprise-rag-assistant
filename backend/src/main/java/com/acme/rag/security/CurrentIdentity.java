package com.acme.rag.security;

import com.acme.rag.AccessDeniedEvent;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentIdentity {
    private final DemoIdentityService identities;
    private final ApplicationEventPublisher events;

    public CurrentIdentity(DemoIdentityService identities, ApplicationEventPublisher events) {
        this.identities = identities;
        this.events = events;
    }

    public DemoIdentity require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            events.publishEvent(new AccessDeniedEvent());
            throw new AccessDeniedException("Authentication is required");
        }
        return identities.find(authentication.getName()).orElseThrow(() -> {
            events.publishEvent(new AccessDeniedEvent());
            return new AccessDeniedException("Identity is not recognized");
        });
    }

    public Set<String> allowedRoles() {
        return require().roles();
    }

    public void requireDocumentAdmin() {
        if (!isDocumentAdmin()) {
            events.publishEvent(new AccessDeniedEvent());
            throw new AccessDeniedException("Document administration is not permitted");
        }
    }

    public boolean isDocumentAdmin() {
        return require().documentAdmin();
    }

    public String username() {
        return require().username();
    }
}
