package com.trackngo.auth.internal.security;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/**
 * The user lookup that {@link JwtFilter} performs on every authenticated
 * request, with a short-lived cache in front of it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every request carrying a Bearer token was doing a {@code SELECT ... FROM
 * user WHERE email = ?} before the controller ran. Against a database on another
 * host that is a full network round trip added to every single API call, paid
 * again and again to re-read a row that had not changed.
 *
 * <h2>Why it is deliberately not on CustomUserDetailsService itself</h2>
 *
 * <p>{@code UserDetailsService} is also what Spring Security's
 * {@code DaoAuthenticationProvider} calls during <em>login</em>, and the object
 * it returns carries the password hash. Caching there would mean a user who had
 * just changed their password could still sign in with the old one until the
 * entry expired - a real functional change. Caching here instead keeps the login
 * path reading straight from the database, exactly as before. This cache is read
 * only when validating an already-issued token, where the password is never
 * consulted.
 *
 * <h2>What the cache can make stale, and why it does not</h2>
 *
 * <p>The only thing {@code JwtFilter} takes from the result is the user's
 * authorities, so the one thing a stale entry could get wrong is a change of
 * role, or a user deleted mid-session. Both are administrator actions, and every
 * one of them evicts this cache - see the {@code @CacheEvict} annotations on
 * {@code UserServiceImpl}'s update, status and delete paths. The TTL in
 * {@code CacheConfig} is only a backstop for a change made directly in the
 * database, and can be set to 0 to switch caching off entirely.
 *
 * <p>An unknown user still throws, and exceptions are never cached, so a bogus
 * token gets the same answer it always did.
 */
@Component
@RequiredArgsConstructor
public class JwtUserDetailsCache {

    /**
     * Cache name, shared with the {@code @CacheEvict} annotations on the user
     * administration paths and with the TTL declared in {@code CacheConfig}.
     */
    public static final String CACHE_NAME = "jwtUserDetails";

    private final UserDetailsService userDetailsService;

    /**
     * Loads the user behind a token's subject.
     *
     * <p>{@code sync = true} means that when many requests for the same user
     * arrive at once on a cold cache, one of them does the database read and the
     * rest wait for its result, instead of all of them stampeding the database.
     */
    @Cacheable(cacheNames = CACHE_NAME, sync = true)
    public UserDetails loadForToken(String username) {
        return userDetailsService.loadUserByUsername(username);
    }
}
