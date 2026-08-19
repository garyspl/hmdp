package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.jd.platform.hotkey.client.ClientStarter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.time.Duration;

@Slf4j
@Configuration
public class HotKeyConfig {
    @Value("${hmdp.hotkey.enabled:false}")
    private boolean enabled;
    @Value("${hmdp.hotkey.app-name:hmdp}")
    private String appName;
    @Value("${hmdp.hotkey.etcd-server:http://127.0.0.1:2379}")
    private String etcdServer;

    @PostConstruct
    public void startHotKeyClient() {
        if (!enabled) {
            log.info("JD-hotkey disabled; shop reads use Redis/DB without dynamic local-cache promotion");
            return;
        }
        ClientStarter starter = new ClientStarter.Builder()
                .setAppName(appName)
                .setEtcdServer(etcdServer)
                .setCaffeineSize(10000)
                .build();
        starter.startPipeline();
        log.info("JD-hotkey client started, appName={}, etcd={}", appName, etcdServer);
    }

    @Bean
    public Cache<String, Shop> shopLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats()
                .build();
    }
}
