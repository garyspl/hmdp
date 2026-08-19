# 黑马点评：项目走读与压测手册

## 1. 面试时先讲清这条链路

`Nginx -> HTTP -> Redis Lua 抢购资格/一人一单 -> RabbitMQ -> 消费者事务创建 MySQL 订单 -> 状态机`

Lua 把并发竞争挡在 Redis，RabbitMQ 削峰并把数据库写入移出请求线程。接口返回成功表示“已取得购买资格并进入异步链路”，不等于订单已落库。因此必须分别报告：

- 接入层：吞吐量、平均延迟、P90/P95/P99、错误率。
- 消费层：RabbitMQ 积压峰值、消化耗时、数据库最终订单数。
- 正确性：Redis/MySQL 剩余库存、订单数、一人一单、是否超卖。

Lua 会登记 RESERVED 待处理订单；对账任务重投超时消息，并在超过重试上限后补偿 Redis 资格。数据库唯一索引与条件更新分别兜底一人一单和状态机并发。生产环境还应进一步采用 outbox/事务消息，并完善告警与人工补偿平台。

## 2. 一次完整实验

前置：Docker、JDK、Maven、JMeter。macOS 可安装 JMeter：`brew install jmeter`。

```bash
docker compose -f docker-compose.perf.yml up -d --wait
USERS=1000 STOCK=1000 VOUCHER_ID=100 bash perf/prepare.sh
HMDP_LOG_LEVEL=warn mvn spring-boot:run
```

另开终端先冒烟，再逐级加压：

```bash
THREADS=1 RAMP=1 bash perf/run.sh
USERS=100 STOCK=100 bash perf/prepare.sh && THREADS=100 RAMP=10 bash perf/run.sh
USERS=500 STOCK=500 bash perf/prepare.sh && THREADS=500 RAMP=10 bash perf/run.sh
USERS=1000 STOCK=1000 bash perf/prepare.sh && THREADS=1000 RAMP=1 bash perf/run.sh
bash perf/verify.sh
```

报告在 `perf/results/report/index.html`。每轮必须重新执行 `prepare.sh`，否则“一人一单”会让下一轮全失败。正式压测用 JMeter 非 GUI 模式；GUI 仅用于编辑脚本。

## 3. 怎么读结果

- 不要只报 TPS。先确认业务断言错误率为 0，再看 P95/P99。
- `THREADS` 是并发用户数，不是 QPS；QPS 是结果，约等于并发数/平均响应秒数。
- `RAMP=1` 是突发秒杀；`RAMP=10` 更接近阶梯预热。两者不可混成一个数字。
- 压测机和服务同机时，结果只代表本机实验环境。面试中必须交代 CPU、内存、JDK、依赖版本、线程数、持续时间和数据量。
- 若 HTTP 很快但 MQ 长时间积压，说明只是入口削峰成功，系统端到端处理能力仍受消费者/MySQL 限制。

## 4. 建议做的四组对照实验

1. 商铺热点查询：冷缓存、热缓存、缓存失效瞬间，对比数据库查询和 P99。
2. 秒杀基线：10/50/100 并发，寻找无明显排队时的吞吐。
3. 阶梯负载：100/300/500/1000 并发，每档至少 60 秒（需准备足够独立用户和库存）。
4. 正确性与恢复：库存小于用户数、重复用户、暂停消费者制造积压、恢复后观察消化时间。

## 5. 高频追问

- 为什么 Lua？多条 Redis 命令在服务端原子执行，避免“查库存”和“扣库存”间被其他请求插入。
- 为什么还要数据库条件扣减和唯一索引？Redis 是入口判定，数据库约束是最终一致性的最后防线。
- 为什么使用不同 token？一人一单按 userId 校验；一个 token 压 1000 次测到的是重复请求拒绝能力，不是真实抢购。
- MQ 能提升什么？降低接口延迟、削平数据库写峰值；它不凭空提升数据库最终写入上限。
- 如何定位拐点？并发继续升高时吞吐趋平、P99 和错误率陡升、线程池/连接池/MQ 积压持续增长，该区间就是容量拐点。
- 为什么不能直接写“QPS 5000”？没有可复现实验环境、报告和正确性校验的数字没有可信度。

## 6. 简历校准

当前仓库已实现 Nginx 入口、JD-hotkey 动态探测、Caffeine 本地热点副本、订单状态机和 Redis/MySQL 对账补偿。JD-hotkey 默认关闭；只有部署 etcd、worker、配置热点规则并设置 `HMDP_HOTKEY_ENABLED=true` 后，才可以声称完成了真实动态热点实验。

### “一人一单”与解耦的准确表述

一人一单是业务幂等约束，不负责解耦。Redis Set + Lua 在抢购阶段阻止同一用户重复取得资格，MySQL `(user_id,voucher_id)` 唯一索引做最终兜底。抢购与下单的解耦来自 RabbitMQ：请求线程只完成资格预扣和消息投递，消费者异步创建订单。
