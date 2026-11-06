package io.github.huck5.recordservice.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        long startNanos = System.nanoTime();

        filterChain.doFilter(request, response);

        log.atInfo()
           .addKeyValue("event", "http_request_completed")
           .addKeyValue("method", request.getMethod())
           .addKeyValue("path", request.getRequestURI())
           .addKeyValue("status", response.getStatus())
           .addKeyValue("durationMs", (System.nanoTime() - startNanos) / 1_000_000)
           .log("HTTP request completed");
    }
}
