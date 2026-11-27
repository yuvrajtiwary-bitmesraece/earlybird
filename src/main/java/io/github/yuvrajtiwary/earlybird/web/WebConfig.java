package io.github.yuvrajtiwary.earlybird.web;

import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.config.ConditionalOnRole;
import io.github.yuvrajtiwary.earlybird.config.Role;
import io.github.yuvrajtiwary.earlybird.enrich.GeminiBudget;
import io.github.yuvrajtiwary.earlybird.events.KafkaMonitor;
import io.github.yuvrajtiwary.earlybird.persistence.PublicStatsRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/** The public website (spec 3.12), only in processes with the web role. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.WEB)
class WebConfig {

    @Bean
    PublicData publicData(PublicStatsRepository stats, ObjectProvider<KafkaMonitor> kafka,
            ObjectProvider<RedisGuard> redis, ObjectProvider<GeminiBudget> gemini, Clock clock) {
        return new PublicData(stats, kafka, redis, gemini, clock);
    }

    /**
     * Spec 3.12's headers on every response. The CSP allows only this site's own files: no inline scripts or
     * styles, no external fonts or trackers, and no framing (clickjacking).
     */
    @Bean
    OncePerRequestFilter securityHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                response.setHeader("Content-Security-Policy",
                        "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                chain.doFilter(request, response);
            }
        };
    }
}
