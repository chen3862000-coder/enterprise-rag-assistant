package com.acme.rag.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService noPasswordUsers() {
        return username -> {
            throw new UsernameNotFoundException("Only server-issued demo sessions are supported");
        };
    }

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            @Value("${app.demo.authentication-enabled:true}") boolean demoAuthenticationEnabled) throws Exception {
        configure(http, demoAuthenticationEnabled);
        return http.build();
    }

    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "Spring Security HttpSecurity configuration methods declare Exception")
    private static void configure(HttpSecurity http, boolean demoAuthenticationEnabled) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        http.authorizeHttpRequests(authorize -> {
            if (demoAuthenticationEnabled) {
                authorize.requestMatchers("/api/auth/**", "/actuator/health").permitAll();
            } else {
                authorize.requestMatchers("/api/auth/**").denyAll();
            }
            authorize.anyRequest().authenticated();
        });
        http.requestCache(cache -> cache.disable());
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
    }
}
