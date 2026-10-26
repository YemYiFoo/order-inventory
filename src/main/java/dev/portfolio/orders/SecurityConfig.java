package dev.portfolio.orders;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/** Configures database-backed accounts and local non-browser HTTP Basic access. */
@Configuration
public class SecurityConfig {

    public static final class Account extends User {

        private final long id;

        public Account(long id, String username, String password, String role) {
            super(username, password, AuthorityUtils.createAuthorityList("ROLE_" + role));
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc) {
        return name ->
                jdbc
                        .query(
                                "SELECT id,username,password_hash,role FROM app_users WHERE"
                                        + " username=?",
                                (rs, n) ->
                                        new Account(
                                                rs.getLong(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4)),
                                name)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }

    @Bean
    BCryptPasswordEncoder passwords() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Only local non-browser clients are supported. Browser-cached Basic credentials would
        // require CSRF protection.
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                HttpMethod.GET,
                                                "/api/products",
                                                "/api/products/*",
                                                "/actuator/health")
                                        .permitAll()
                                        .requestMatchers("/api/admin/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/orders", "/api/orders/**")
                                        .hasRole("CUSTOMER")
                                        .anyRequest()
                                        .denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(this::authenticationFailure))
                .exceptionHandling(errors -> errors.accessDeniedHandler(this::accessDenied))
                .build();
    }

    private void authenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        response.setContentType("application/json");

        if (ApiErrors.temporaryDatabaseFailure(exception)) {
            response.setStatus(503);
            response.setHeader("Retry-After", "1");
            response.getWriter()
                    .write(
                            "{\"code\":\"TEMPORARY_DATABASE_FAILURE\",\"message\":\"Database"
                                + " temporarily unavailable; retry with the same key\"}");
            return;
        }
        if (exception instanceof AuthenticationServiceException) {
            response.setStatus(500);
            response.getWriter()
                    .write(
                            "{\"code\":\"UNEXPECTED_ERROR\",\"message\":\"Unexpected server"
                                + " error\"}");
            return;
        }

        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Basic realm=\"local-demo\"");
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\"}");
    }

    private void accessDenied(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Role not permitted\"}");
    }
}
