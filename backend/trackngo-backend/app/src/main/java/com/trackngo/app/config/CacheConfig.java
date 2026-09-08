package com.trackngo.app.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.trackngo.auth.internal.security.JwtUserDetailsCache;
import com.trackngo.tracking.internal.service.RouteGeometryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-process caches for the two reads that dominate request latency.
 *
 * <p>Both caches exist because the same unchanged rows were being fetched from
 * the database over and over: the route table on every passenger map view, and
 * one user row on every authenticated request. Neither cache is allowed to be a
 * source of stale answers - the write paths that could invalidate them carry
 * {@code @CacheEvict}, so an administrator's change is visible on the very next
 * request. The TTLs below are a backstop for changes made directly in the
 * database, outside the application, not the primary correctness mechanism.
 *
 * <p>Caffeine is in-process, which is the right choice while this runs as a
 * single instance. Running more than one instance would mean an eviction on one
 * instance not reaching the others; that is the point at which to move these to
 * Redis, and not before - until then Redis is another thing to operate for no
 * gain.
 *
 * <p>Setting either TTL to 0 makes entries expire the moment they are written,
 * which is how to turn a cache off without a rebuild:
 * {@code CACHE_REFERENCE_TTL_SECONDS=0} or {@code CACHE_USER_DETAILS_TTL_SECONDS=0}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(
            @Value("${trackngo.cache.reference-ttl-seconds:300}") long referenceTtlSeconds,
            @Value("${trackngo.cache.user-details-ttl-seconds:60}") long userDetailsTtlSeconds) {

        /*
          CaffeineCacheManager rather than SimpleCacheManager so a cache name that
          is not registered below still works - it is built on demand from the
          default spec - instead of failing the request with "Cannot find cache
          named ...". A future @Cacheable should be slower than intended at worst,
          never broken.
        */
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(defaults().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(500));

        /*
          The whole route table, held as one entry. Small, read constantly by the
          passenger map, and evicted by every write path in RouteServiceImpl.
        */
        manager.registerCustomCache(
                RouteGeometryService.ROUTE_INDEX_CACHE,
                defaults()
                        .expireAfterWrite(Duration.ofSeconds(referenceTtlSeconds))
                        .maximumSize(16)
                        .build());

        /*
          One entry per signed-in user. Sized to hold a realistic number of
          concurrently active sessions; beyond that Caffeine evicts the least
          recently used, which simply means that user pays for one database read.
        */
        manager.registerCustomCache(
                JwtUserDetailsCache.CACHE_NAME,
                defaults()
                        .expireAfterWrite(Duration.ofSeconds(userDetailsTtlSeconds))
                        .maximumSize(5_000)
                        .build());

        return manager;
    }

    /*
      recordStats so /actuator/metrics/cache.gets reports hits and misses. Without
      it a cache is invisible, and an ineffective cache looks exactly like an
      effective one.
    */
    private static Caffeine<Object, Object> defaults() {
        return Caffeine.newBuilder().recordStats();
    }
}
