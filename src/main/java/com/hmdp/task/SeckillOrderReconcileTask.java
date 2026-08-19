package com.hmdp.task;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import cn.hutool.json.JSONUtil;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SeckillOrderReconcileTask {
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;
    private final IVoucherOrderService orderService;

    @Value("${hmdp.reconcile.pending-timeout-seconds:15}")
    private long pendingTimeoutSeconds;
    @Value("${hmdp.reconcile.max-retries:5}")
    private int maxRetries;

    public SeckillOrderReconcileTask(StringRedisTemplate redis, RabbitTemplate rabbitTemplate,
                                     IVoucherOrderService orderService) {
        this.redis = redis;
        this.rabbitTemplate = rabbitTemplate;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${hmdp.reconcile.fixed-delay-ms:5000}")
    public void reconcile() {
        double deadline = Instant.now().minusSeconds(pendingTimeoutSeconds).getEpochSecond();
        Set<String> ids = redis.opsForZSet().rangeByScore("seckill:pending", 0, deadline, 0, 100);
        if (ids == null) return;
        for (String id : ids) reconcileOne(id);
    }

    private void reconcileOne(String id) {
        VoucherOrder dbOrder = orderService.getById(Long.valueOf(id));
        if (dbOrder != null) {
            orderService.markCreated(dbOrder);
            return;
        }
        String key = "seckill:order:state:" + id;
        Map<Object, Object> state = redis.opsForHash().entries(key);
        if (state.isEmpty()) {
            redis.opsForZSet().remove("seckill:pending", id);
            return;
        }
        VoucherOrder order = new VoucherOrder()
                .setId(Long.valueOf(id))
                .setUserId(Long.valueOf(state.get("userId").toString()))
                .setVoucherId(Long.valueOf(state.get("voucherId").toString()));
        int retry = Integer.parseInt(String.valueOf(state.getOrDefault("retry", "0")));
        if (retry >= maxRetries) {
            orderService.compensate(order);
            log.error("秒杀订单超过重试次数，已补偿资格: orderId={}", id);
            return;
        }
        rabbitTemplate.convertAndSend("X", "XA", JSONUtil.toJsonStr(order));
        redis.opsForHash().increment(key, "retry", 1);
        redis.opsForHash().put(key, "state", "RESENT");
        redis.opsForZSet().add("seckill:pending", id, Instant.now().getEpochSecond());
        log.warn("对账重投秒杀订单: orderId={}, retry={}", id, retry + 1);
    }
}
