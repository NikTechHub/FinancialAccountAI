package com.example.accounting.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tenant-safe cache facade. All keys must start with {@code org:} (see
 * {@link CacheKeyBuilder}); any attempt to use a non-tenant-scoped key is
 * rejected. Redis failures never propagate: on any cache error the call
 * falls back to empty (caller loads from the database) and the failure is
 * logged without secrets.
 */
@Service
public class TenantCacheService {

    private static final Logger log = LoggerFactory.getLogger(TenantCacheService.class);

    private final CacheManager cacheManager;
    private final RedisProperties redisProperties;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, Object>> redisTemplateProvider;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    public TenantCacheService(CacheManager cacheManager,
                              RedisProperties redisProperties,
                              ObjectMapper objectMapper,
                              org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, Object>> redisTemplateProvider) {
        this.cacheManager = cacheManager;
        this.redisProperties = redisProperties;
        this.objectMapper = objectMapper;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    private static void requireTenantSafeKey(String key) {
        if (key == null || !key.startsWith("org:")) {
            throw new IllegalArgumentException("Cache key must be tenant-scoped (start with 'org:')");
        }
    }

    public <T> Optional<T> get(String cacheName, String key, Class<T> type) {
        requireTenantSafeKey(key);
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper == null || wrapper.get() == null) {
                misses.incrementAndGet();
                log.debug("cache miss cache={} keyHash={}", cacheName, hashForLog(key));
                return Optional.empty();
            }
            Object raw = wrapper.get();
            if (type.isInstance(raw)) {
                hits.incrementAndGet();
                log.debug("cache hit cache={} keyHash={}", cacheName, hashForLog(key));
                return Optional.of(type.cast(raw));
            }
            // Redis JSON round-trip may produce LinkedHashMap; convert safely.
            try {
                T converted = objectMapper.convertValue(raw, type);
                hits.incrementAndGet();
                log.debug("cache hit (converted) cache={} keyHash={}", cacheName, hashForLog(key));
                return Optional.of(converted);
            } catch (Exception convEx) {
                log.warn("cache value type mismatch cache={} (evicting stale entry)", cacheName);
                safeEvict(cache, key);
                misses.incrementAndGet();
                return Optional.empty();
            }
        } catch (Exception ex) {
            // Redis outage must not break reads; fall back to DB.
            log.warn("cache get failed cache={} (falling back to database)", cacheName);
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    public <T> Optional<T> get(String cacheName, String key, TypeReference<T> typeRef) {
        requireTenantSafeKey(key);
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper == null || wrapper.get() == null) {
                misses.incrementAndGet();
                log.debug("cache miss cache={} keyHash={}", cacheName, hashForLog(key));
                return Optional.empty();
            }
            Object raw = wrapper.get();
            try {
                T converted = objectMapper.convertValue(raw, typeRef);
                // Guard against converting a single object into an incompatible shape:
                // convertValue never returns null for valid input; if it does, treat as miss.
                if (converted == null) {
                    misses.incrementAndGet();
                    return Optional.empty();
                }
                hits.incrementAndGet();
                log.debug("cache hit cache={} keyHash={}", cacheName, hashForLog(key));
                return Optional.of(converted);
            } catch (Exception convEx) {
                log.warn("cache value type mismatch cache={} (evicting stale entry)", cacheName);
                safeEvict(cache, key);
                misses.incrementAndGet();
                return Optional.empty();
            }
        } catch (Exception ex) {
            log.warn("cache get failed cache={} (falling back to database)", cacheName);
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    public void put(String cacheName, String key, Object value) {
        requireTenantSafeKey(key);
        if (value == null) {
            return;
        }
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return;
            }
            cache.put(key, value);
            log.debug("cache put cache={} keyHash={}", cacheName, hashForLog(key));
        } catch (Exception ex) {
            log.warn("cache put failed cache={} (continuing without cache)", cacheName);
        }
    }

    public void evict(String cacheName, String key) {
        requireTenantSafeKey(key);
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return;
            }
            cache.evict(key);
            invalidations.incrementAndGet();
            log.debug("cache evict cache={} keyHash={}", cacheName, hashForLog(key));
        } catch (Exception ex) {
            log.warn("cache evict failed cache={} (continuing)", cacheName);
        }
    }

    /**
     * Evict all entries in the given cache whose key contains the organization
     * ID. This clears the organization-scoped namespace for one category
     * without touching other organizations or other categories.
     */
    public void evictOrgCategory(UUID organizationId, String cacheName) {
        if (organizationId == null || cacheName == null) {
            return;
        }
        String needle = organizationId.toString();
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return;
            }
            // Prefer Redis pattern delete when Redis is the active manager.
            if (redisProperties.isEnabled() && cache instanceof RedisCache) {
                if (evictViaRedisPattern(cacheName, needle)) {
                    return;
                }
            }
            Object nativeCache = cache.getNativeCache();
            if (nativeCache instanceof ConcurrentMap<?, ?> map) {
                int removed = 0;
                for (Object k : ((ConcurrentMap<?, ?>) map).keySet().toArray()) {
                    if (k != null && k.toString().contains(needle)) {
                        cache.evict(k);
                        removed++;
                    }
                }
                if (removed > 0) {
                    invalidations.addAndGet(removed);
                    log.debug("cache evict-org cache={} removed={}", cacheName, removed);
                }
                return;
            }
            log.debug("cache evict-org skipped scan for unsupported cache impl cache={}", cacheName);
        } catch (Exception ex) {
            log.warn("cache evict-org failed cache={} (continuing)", cacheName);
        }
    }

    /** Evict the organization's namespace across all known caches. */
    public void evictOrgAll(UUID organizationId) {
        for (String name : CacheNames.all()) {
            evictOrgCategory(organizationId, name);
        }
    }

    /** Clear a whole named cache (used only in tests). */
    public void clearCache(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        } catch (Exception ex) {
            log.warn("cache clear failed cache={} (continuing)", cacheName);
        }
    }

    /** Clear all known caches (used only in tests). */
    public void clearAll() {
        for (String name : CacheNames.all()) {
            clearCache(name);
        }
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getInvalidations() {
        return invalidations.get();
    }

    private boolean evictViaRedisPattern(String cacheName, String needle) {
        try {
            var template = redisTemplateProvider.getIfAvailable();
            if (template == null) {
                return false;
            }
            // RedisCache default key format is "cacheName::key".
            String pattern = cacheName + "*" + needle + "*";
            var keys = template.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                template.delete(keys);
                invalidations.addAndGet(keys.size());
                log.debug("cache redis evict-org cache={} removed={}", cacheName, keys.size());
            }
            return true;
        } catch (Exception ex) {
            log.warn("cache redis pattern evict failed cache={} (continuing)", cacheName);
            return false;
        }
    }

    private void safeEvict(Cache cache, Object key) {
        try {
            cache.evict(key);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static String hashForLog(String key) {
        // Never log raw keys that might contain search text; log a stable hash instead.
        return Integer.toHexString(key.hashCode());
    }
}
