package com.example.dispute.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-Role";

    private static final Pattern SAFE_ACTOR_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String actorId = request.getHeader(USER_ID_HEADER);
        String roleValue = request.getHeader(ROLE_HEADER);

        if (isValidActorId(actorId) && roleValue != null) {
            authenticate(actorId, roleValue);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean isValidActorId(String actorId) {
        return actorId != null && SAFE_ACTOR_ID.matcher(actorId).matches();
    }

    private static void authenticate(String actorId, String roleValue) {
        try {
            ActorRole role = ActorRole.valueOf(roleValue);
            AuthenticatedActor actor = new AuthenticatedActor(actorId, role);
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            actor,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (IllegalArgumentException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
