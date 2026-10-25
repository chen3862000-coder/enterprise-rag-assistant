package com.acme.rag.security;

import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class DemoAuthenticationService {
    private final DemoIdentityService identities;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public DemoAuthenticationService(DemoIdentityService identities) {
        this.identities = identities;
    }

    public DemoIdentity login(String username) {
        DemoIdentity identity = identities.find(username)
                .orElseThrow(() -> new BadCredentialsException("Unknown demo identity"));
        List<SimpleGrantedAuthority> authorities = identity.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        if (identity.documentAdmin()) {
            authorities = new java.util.ArrayList<>(authorities);
            authorities.add(new SimpleGrantedAuthority("DOCUMENT_ADMIN"));
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                identity.username(), "", authorities);
        ServletRequestAttributes attributes = currentRequest();
        attributes.getRequest().getSession(true);
        attributes.getRequest().changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, attributes.getRequest(), attributes.getResponse());
        return identity;
    }

    public void logout() {
        ServletRequestAttributes attributes = currentRequest();
        if (attributes.getRequest().getSession(false) != null) {
            attributes.getRequest().getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private static ServletRequestAttributes currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes;
        }
        throw new IllegalStateException("Demo login requires an HTTP request");
    }
}
