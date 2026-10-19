package com.example.accounting.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache wiring. Redis is optional: when {@code app.redis.enabled=false}
 * (the default, and always in tests) a simple in-memory manager is used so
 * the application and test suite run without a Redis server. When enabled,
 * a JSON-serializing Redis manager is used with per-category TTLs.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(RedisProperties.class)
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory,
                                           RedisProperties redisProperties,
                                           ObjectMapper objectMapper) {
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer))
                .entryTtl(Duration.ofSeconds(redisProperties.getTtl().getAccountListSeconds()));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CacheNames.ACCOUNTS_TREE,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getAccountTreeSeconds())));
        perCache.put(CacheNames.ACCOUNTS_LIST,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getAccountListSeconds())));
        perCache.put(CacheNames.ACCOUNTS_ITEM,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getAccountItemSeconds())));
        perCache.put(CacheNames.VENDORS_LIST,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getVendorListSeconds())));
        perCache.put(CacheNames.VENDORS_ITEM,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getVendorItemSeconds())));
        perCache.put(CacheNames.CUSTOMERS_LIST,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getCustomerListSeconds())));
        perCache.put(CacheNames.CUSTOMERS_ITEM,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getCustomerItemSeconds())));
        perCache.put(CacheNames.ORGANIZATIONS,
                defaults.entryTtl(Duration.ofSeconds(redisProperties.getTtl().getOrganizationSeconds())));

        log.info("Redis cache enabled with per-category TTLs");
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .transactionAware()
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager simpleCacheManager() {
        // Fallback for tests / Redis-disabled mode. TTLs are not strictly
        // enforced here; entries live for the (short-lived) process lifetime.
        // This keeps caching logic testable without a Redis server.
        log.info("Redis disabled - using in-memory cache manager");
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager(CacheNames.all());
        manager.setAllowNullValues(false);
        return manager;
    }

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        // A Redis outage must never break accounting operations. Swallow
        // cache errors (logged without secrets) so callers fall back to DB.
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, org.springframework.cache.Cache cache, Object key) {
                log.warn("cache get error cache={} (falling back to database)", cache.getName());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, org.springframework.cache.Cache cache, Object key, Object value) {
                log.warn("cache put error cache={} (continuing without cache)", cache.getName());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, org.springframework.cache.Cache cache, Object key) {
                log.warn("cache evict error cache={} (continuing)", cache.getName());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, org.springframework.cache.Cache cache) {
                log.warn("cache clear error cache={} (continuing)", cache.getName());
            }
        };
    }
}
