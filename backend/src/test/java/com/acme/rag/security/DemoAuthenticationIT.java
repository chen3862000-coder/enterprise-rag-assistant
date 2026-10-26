package com.acme.rag.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.M2TestConfiguration;
import com.acme.rag.observability.RagTrace;
import com.acme.rag.observability.RagTraceRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"app.demo.authentication-enabled=true", "app.seed.enabled=false", "spring.ai.model.chat=none"})
@AutoConfigureMockMvc
@Testcontainers
@Import(M2TestConfiguration.class)
class DemoAuthenticationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("rag_auth_test").withUsername("rag").withPassword("rag");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired RagTraceRepository traces;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sessionLoginUsesFixedServerRolesAndRejectsSpoofedRoleInput() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"role\":\"SECURITY_ADMIN\"}"))
                .andExpect(status().isOk()).andReturn();

        var session = login.getRequest().getSession(false);
        String current = mvc.perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(current).contains("alice", "ENGINEERING", "PUBLIC").doesNotContain("SECURITY_ADMIN");
        assertThat(session).isNotNull();
    }

    @Test
    void loginRequiresCsrfAndRejectsUnknownUser() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesSessionAndRequiresCsrf() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = login.getRequest().getSession(false);

        mvc.perform(post("/api/auth/logout").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").with(csrf()).session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(new org.springframework.mock.web.MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void evaluationEndpointsRequireExplicitDocumentAdminCapability() throws Exception {
        MvcResult aliceLogin = login("alice");
        mvc.perform(get("/api/evaluations").session((org.springframework.mock.web.MockHttpSession) aliceLogin.getRequest().getSession(false)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/traces/" + java.util.UUID.randomUUID())
                        .session((org.springframework.mock.web.MockHttpSession) aliceLogin.getRequest().getSession(false)))
                .andExpect(status().isNotFound());

        MvcResult carolLogin = login("carol");
        mvc.perform(get("/api/evaluations").session((org.springframework.mock.web.MockHttpSession) carolLogin.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }

    @Test
    void traceApiOnlyAllowsOwnerOrExplicitAdmin() throws Exception {
        MvcResult alice = login("alice");
        MvcResult bob = login("bob");
        UUID traceId = UUID.randomUUID();
        traces.save(new RagTrace(traceId, "alice", 1, 2, 3, 1, 1, 0, 1, 1, true, "LEXICAL_AND_RELEVANCE_MATCH",
                "deepseek", "model", "CITATIONS_PRESENT", "SUCCEEDED", null, Instant.now()), "b".repeat(64));

        mvc.perform(get("/api/traces/" + traceId).session((org.springframework.mock.web.MockHttpSession) alice.getRequest().getSession(false)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/traces/" + traceId).session((org.springframework.mock.web.MockHttpSession) bob.getRequest().getSession(false)))
                .andExpect(status().isNotFound());
        MvcResult carol = login("carol");
        mvc.perform(get("/api/traces/" + traceId).session((org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }

    @Test
    void documentAdminCanManageMetadataWithoutGrantingOtherUsersVisibility() throws Exception {
        jdbc.update("INSERT INTO documents (id, title, department, classification) VALUES ('doc-private-hr', 'Private HR Policy', 'HR', 'CONFIDENTIAL')");
        jdbc.update("INSERT INTO document_permissions (document_id, allowed_role) VALUES ('doc-private-hr', 'HR')");

        MvcResult alice = login("alice");
        String aliceDocuments = mvc.perform(get("/api/documents").session((org.springframework.mock.web.MockHttpSession) alice.getRequest().getSession(false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(aliceDocuments).doesNotContain("Private HR Policy");

        MvcResult carol = login("carol");
        String adminDocuments = mvc.perform(get("/api/documents").session((org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(adminDocuments).contains("Private HR Policy");
    }

    @Test
    void documentMutationIsAdminOnlyAndNewVersionPermissionsActivateWithVersion() throws Exception {
        MvcResult alice = login("alice");
        MockMultipartFile file = new MockMultipartFile("file", "new-guide.md", "text/markdown",
                "# Guide\nRestricted test evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/documents").file(file).with(csrf())
                        .session((org.springframework.mock.web.MockHttpSession) alice.getRequest().getSession(false))
                        .param("documentId", "doc-admin-upload-test")
                        .param("title", "Admin upload test")
                        .param("department", "HR")
                        .param("classification", "CONFIDENTIAL")
                        .param("allowedRoles", "HR"))
                .andExpect(status().isForbidden());

        mvc.perform(multipart("/api/documents").file(file).with(csrf())
                        .session((org.springframework.mock.web.MockHttpSession) alice.getRequest().getSession(false))
                        .param("documentId", "doc-alice-forbidden-upload")
                        .param("title", "Forbidden upload")
                        .param("department", "ENGINEERING")
                        .param("classification", "INTERNAL")
                        .param("allowedRoles", "ENGINEERING"))
                .andExpect(status().isForbidden());

        MvcResult carol = login("carol");
        mvc.perform(multipart("/api/documents").file(file).with(csrf())
                        .session((org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false))
                        .param("documentId", "doc-admin-upload-test")
                        .param("title", "Admin upload test")
                        .param("department", "HR")
                        .param("classification", "CONFIDENTIAL")
                        .param("allowedRoles", "HR"))
                .andExpect(status().isOk());

        String carolList = mvc.perform(get("/api/documents")
                        .session((org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(carolList).contains("Admin upload test");
        assertThat(mvc.perform(get("/api/sources/doc-admin-upload-test-v1-c0")
                        .session((org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void explicitDisableAndDeleteRoutesHaveDistinctSoftDeleteStates() throws Exception {
        jdbc.update("INSERT INTO documents (id, title, department, classification) VALUES ('doc-status-test', 'Status test', 'PUBLIC', 'PUBLIC')");
        jdbc.update("INSERT INTO document_permissions (document_id, allowed_role) VALUES ('doc-status-test', 'PUBLIC')");
        MvcResult carol = login("carol");
        var session = (org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false);

        mvc.perform(post("/api/documents/doc-status-test/disable").with(csrf()).session(session))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id='doc-status-test'", String.class))
                .isEqualTo("DISABLED");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/documents/doc-status-test")
                        .with(csrf()).session(session))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id='doc-status-test'", String.class))
                .isEqualTo("DELETED");
    }

    @Test
    void invalidUploadReturnsStructuredClientError() throws Exception {
        MvcResult carol = login("carol");
        var session = (org.springframework.mock.web.MockHttpSession) carol.getRequest().getSession(false);
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "bad.exe", "application/octet-stream", new byte[]{1}))
                        .with(csrf()).session(session).param("documentId", "doc-bad-upload")
                        .param("title", "Bad file").param("department", "PUBLIC")
                        .param("classification", "PUBLIC").param("allowedRoles", "PUBLIC"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INGESTION_INVALID_INPUT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Only Markdown, plain text, and PDF files are supported"));
    }

    private MvcResult login(String username) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }
}
