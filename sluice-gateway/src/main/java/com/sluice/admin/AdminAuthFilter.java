package com.sluice.admin;

import com.sluice.config.SluiceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private final byte[] expectedToken;
    private final int adminPort;

    public AdminAuthFilter(SluiceProperties properties) {
        this.expectedToken = properties.admin().token().getBytes(StandardCharsets.UTF_8);
        this.adminPort = properties.admin().port();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getLocalPort() != adminPort || !request.getRequestURI().startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-Admin-Token");
        boolean authorized = provided != null
                && MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"unauthorized\",\"message\":\"a valid X-Admin-Token header is required\",\"status\":401}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
