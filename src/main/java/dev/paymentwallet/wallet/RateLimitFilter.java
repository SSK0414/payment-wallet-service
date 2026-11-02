package dev.paymentwallet.wallet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;
    private final int limit;

    public RateLimitFilter(
        StringRedisTemplate redis,
        @Value("${wallet.rate-limit-per-minute}") int limit
    ) {
        this.redis = redis;
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && request.getRequestURI().startsWith("/v1/")) {
            try {
                var key = "rate:" + auth.getName() + ":" + Instant.now().getEpochSecond() / 60;
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1) redis.expire(key, Duration.ofMinutes(2));
                if (count != null && count > limit) {
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.setHeader("Retry-After", "60");
                    response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
                    return;
                }
            } catch (DataAccessException ignored) {
                // ponytail: Redis outage fails open; use a durable limiter if policy needs fail-closed behavior.
            }
        }
        chain.doFilter(request, response);
    }
}
