package com.huangyifei.rag.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private OrgTagAuthorizationFilter orgTagAuthorizationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/chat/**", "/ws/**").permitAll()
                        .requestMatchers("/api/v1/users/register", "/api/v1/users/login").permitAll()
                        .requestMatchers("/api/v1/test/**").permitAll()
                        .requestMatchers(
                                "/api/v1/upload/**",
                                "/api/v1/parse",
                                "/api/v1/documents/download",
                                "/api/v1/documents/preview",
                                "/api/v1/documents/page-preview"
                        ).hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/v1/chat/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/v1/agent/tools/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/v1/users/conversation/**", "/api/v1/users/conversations/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/v1/users/primary-org").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class)
                .build();
    }
}
