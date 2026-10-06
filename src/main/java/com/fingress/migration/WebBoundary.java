package com.fingress.migration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** Local single-user tool. Prevents browser cross-origin writes and DNS rebinding. */
@Component
public class WebBoundary extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        response.setHeader("Cache-Control", "no-store");
        if (!Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(request.getServerName())) { response.sendError(403); return; }
        if (request.getRequestURI().startsWith("/api/") && !Set.of("GET", "HEAD").contains(request.getMethod()) && !"migration-ui".equals(request.getHeader("X-Migration-Client"))) {
            response.sendError(403); return;
        }
        if (request.getHeader("Origin") != null && !request.getHeader("Origin").equals(request.getScheme() + "://" + request.getHeader("Host"))) { response.sendError(403); return; }
        chain.doFilter(request, response);
    }
}
