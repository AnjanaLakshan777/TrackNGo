
package com.trackngo.auth.internal.security;

import com.trackngo.commons.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    // Cached lookup rather than UserDetailsService directly: this filter runs on
    // every authenticated request, and the underlying read is a database round
    // trip. See JwtUserDetailsCache for what the cache can and cannot make stale.
    private final JwtUserDetailsCache userDetailsCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                String token = auth.substring(7);
                Claims claims = jwtUtil.parse(token);
                if ("2fa".equals(claims.get("purpose", String.class))) {
                    filterChain.doFilter(request, response);
                    return;
                }
                String username = claims.getSubject();
                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    var userDetails = userDetailsCache.loadForToken(username);
                    var authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            } catch (Exception e) {
                // Expired or invalid token — continue without authentication, but leave a
                // trace. Swallowing this silently turns every downstream failure into an
                // unexplained 403.
                log.debug("Rejected JWT for {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}

