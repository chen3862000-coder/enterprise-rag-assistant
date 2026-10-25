package com.acme.rag.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoIdentityServiceTest {
    private final DemoIdentityService identities = new DemoIdentityService();

    @Test
    void adminCapabilityIsSeparateFromSecurityDocumentRole() {
        DemoIdentity carol = identities.find("Carol").orElseThrow();
        DemoIdentity alice = identities.find("alice").orElseThrow();

        assertThat(carol.roles()).contains("SECURITY_ADMIN");
        assertThat(carol.documentAdmin()).isTrue();
        assertThat(alice.roles()).doesNotContain("HR", "SECURITY_ADMIN");
        assertThat(alice.documentAdmin()).isFalse();
    }

    @Test
    void rejectsUnknownIdentityAndIgnoresClientRoleClaims() {
        assertThat(identities.find("administrator")).isEmpty();
        assertThat(identities.find("alice").orElseThrow().roles()).containsExactlyInAnyOrder("PUBLIC", "ENGINEERING");
    }
}
