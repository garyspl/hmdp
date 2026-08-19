package com.hmdp.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Service
public class HotShopCacheService {
    private static final String HOT_KEY_PREFIX = "shop__";
    private final Cache<String, Shop> localCache;
    private final CacheClient redisCache;
    @Value("${hmdp.hotkey.enabled:false}")
    private boolean hotKeyEnabled;

    public HotShopCacheService(Cache<String, Shop> localCache, CacheClient redisCache) {
        this.localCache = localCache;
        this.redisCache = redisCache;
    }

    public Shop query(Long id, Function<Long, Shop> dbFallback) {
        String hotKey = HOT_KEY_PREFIX + id;
        if (hotKeyEnabled && JdHotKeyStore.isHotKey(hotKey)) {
            Shop local = localCache.getIfPresent(hotKey);
            if (local != null) {
                return local;
            }
        }
        Shop shop = redisCache.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class, dbFallback,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop != null && hotKeyEnabled && JdHotKeyStore.isHotKey(hotKey)) {
            localCache.put(hotKey, shop);
            JdHotKeyStore.smartSet(hotKey, shop);
        }
        return shop;
    }

    public void invalidate(Long id) {
        localCache.invalidate(HOT_KEY_PREFIX + id);
        redisCache.delete(RedisConstants.CACHE_SHOP_KEY + id);
    }
}
