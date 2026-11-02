package dev.paymentwallet.wallet;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Configuration
public class ApiSecurity {
    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtFilter jwt, RateLimitFilter rateLimit) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimit, JwtFilter.class)
            .build();
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> disableRateLimitServletFilter(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<JwtFilter> disableJwtServletFilter(JwtFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    JwtFilter jwtFilter(@Value("${wallet.jwt-secret}") String secret) {
        return new JwtFilter(secret);
    }

    static final class JwtFilter extends OncePerRequestFilter {
        private final byte[] secret;
        JwtFilter(String secret) {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
        ) throws ServletException, IOException {
            var header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    String token = header.substring(7);
                    String subject = Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(secret))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
                        .getSubject();
                    UUID.fromString(subject);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(subject, null, List.of()));
                } catch (Exception e) {
                    response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid bearer token");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
