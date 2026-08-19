# 黑马点评源码走读与面试手册

> 本文只描述当前仓库真实代码。阅读顺序：先看架构，再按业务链路打断点，最后做面试自测。

## 一、项目定位和总体架构

这是一个 Spring Boot 单体应用，业务包括验证码登录、商铺查询、附近商铺、优惠券秒杀、签到、关注、共同关注、博客点赞和关注流。MySQL 保存最终业务数据，Redis 承担登录态、缓存、Geo、Bitmap、Set、ZSet、秒杀资格与对账暂存，RabbitMQ 将抢购资格判定和数据库创建订单解耦，Nginx 提供统一入口。

真实请求分层：

1. `nginx/nginx.conf`：入口、反向代理、连接复用和秒杀限流。
2. `controller/`：HTTP 参数接收，不应该承载复杂业务。
3. `interceptor/`：解析 token、恢复登录用户、登录校验。
4. `service/impl/`：业务编排和事务边界。
5. `mapper/`：MyBatis-Plus 数据访问；复杂券查询在 XML。
6. `entity/`：数据库表映射；`dto/`：接口传输对象。
7. Redis、RabbitMQ、MySQL：缓存/高并发入口、异步削峰、最终存储。

启动入口是 `src/main/java/com/hmdp/HmDianPingApplication.java`：

- `@SpringBootApplication` 启动组件扫描和自动配置。
- `@MapperScan` 扫描 Mapper。
- `@EnableRabbit` 开启 RabbitMQ 监听。
- `@EnableScheduling` 开启秒杀对账定时任务。
- `@EnableAspectJAutoProxy(exposeProxy=true)` 允许获取 AOP 代理；当前主链路不应依赖它。

核心配置在 `src/main/resources/application.yaml`；依赖版本在 `pom.xml`；本地依赖编排在 `docker-compose.perf.yml`；数据库初始化在 `src/main/resources/db/hmdp.sql`。

## 二、目录和每个文件的职责

### 1. 配置与入口

| 文件 | 作用 | 面试关注点 |
|---|---|---|
| `HmDianPingApplication.java` | 应用入口，开启 Mapper、Rabbit、定时任务、AOP | Spring Boot 启动和注解作用 |
| `application.yaml` | 端口、MySQL、Redis、RabbitMQ、hotkey、对账参数 | 连接池、MQ ACK/retry、参数外置化 |
| `MvcConfig.java` | 注册两层拦截器及放行路径 | 为什么用两层拦截器、顺序为何 0/1 |
| `MybatisConfig.java` | MyBatis-Plus 分页插件 | 分页拦截器原理 |
| `RedissonConfig.java` | 单机 Redis 的 RedissonClient | 看门狗、可重入锁、集群配置差异 |
| `QueueConfig.java` | X/Y 交换机、QA/QD 队列、路由和死信参数 | exchange/queue/routing key、TTL、DLX |
| `HotKeyConfig.java` | 启动 JD-hotkey 客户端，创建 Caffeine Cache | 动态热点识别与本地缓存职责 |
| `WebExceptionAdvice.java` | 全局捕获 RuntimeException | 统一错误响应、日志和异常泄漏 |
| `nginx/nginx.conf` | 反向代理、keepalive、least_conn、限流 | Nginx 能做什么，限流如何影响压测 |
| `docker-compose.perf.yml` | MySQL/Redis/RabbitMQ/Nginx 本地环境 | 容器网络、健康检查、数据初始化 |

### 2. Controller：HTTP 入口

| 文件 | 主要接口 | 当前状态 |
|---|---|---|
| `UserController.java` | 验证码、登录、当前用户、用户资料、签到 | 登出仍未真正删除 Redis token |
| `ShopController.java` | 商铺详情、新增、更新、按类型/名称查询 | 详情走热点两级缓存，附近查询走 Geo |
| `ShopTypeController.java` | 商铺类型列表 | 业务在 ShopTypeServiceImpl |
| `VoucherController.java` | 券新增、秒杀券新增、店铺券列表 | 普通券接口目前也调用秒杀券新增逻辑，需修正 |
| `VoucherOrderController.java` | 秒杀、订单状态查询、状态流转 | 秒杀核心 HTTP 入口 |
| `BlogController.java` | 发布、点赞、热门、点赞榜、关注流 | ZSet 点赞与 Feed 滚动分页 |
| `FollowController.java` | 关注/取关、是否关注、共同关注 | DB + Redis Set |
| `UploadController.java` | 博客图片上传和删除 | 上传目录当前写死 Windows 路径 |
| `BlogCommentsController.java` | 评论路由占位 | 当前没有业务实现 |

### 3. Service 接口与实现

每个 `I*Service.java` 定义业务能力并继承 MyBatis-Plus `IService<T>`；对应 `impl/*ServiceImpl.java` 继承 `ServiceImpl<Mapper,Entity>`，因此自动拥有 `getById/save/query/update/page` 等 CRUD。

| 实现文件 | 业务职责 |
|---|---|
| `UserServiceImpl.java` | 验证码、Redis token 登录、Bitmap 签到和连续签到 |
| `ShopServiceImpl.java` | 商铺详情、更新缓存失效、分页和 Redis Geo 附近查询 |
| `HotShopCacheService.java` | JD-hotkey 探测、Caffeine L1、Redis L2、DB 回源 |
| `ShopTypeServiceImpl.java` | 商铺分类排序和 List 缓存 |
| `VoucherServiceImpl.java` | 保存券/秒杀券、预热 Redis 库存、查询店铺券 |
| `SeckillVoucherServiceImpl.java` | 秒杀券表的 MyBatis-Plus CRUD 容器 |
| `VoucherOrderServiceImpl.java` | Lua 抢购、MQ 投递、事务落库、补偿、订单状态机 |
| `BlogServiceImpl.java` | 热门博客、ZSet 点赞榜、Feed 推送与滚动分页 |
| `FollowServiceImpl.java` | DB 关注关系、Redis Set 共同关注 |
| `UserInfoServiceImpl.java` | 用户详情 CRUD |
| `BlogCommentsServiceImpl.java` | 评论表 CRUD 基础能力，尚无业务方法 |

### 4. Mapper 与 SQL

`mapper/*.java` 都是 MyBatis-Plus `BaseMapper<Entity>`，负责对应表 CRUD：Blog、BlogComments、Follow、SeckillVoucher、Shop、ShopType、User、UserInfo、Voucher、VoucherOrder。

唯一手写复杂 SQL 是 `src/main/resources/mapper/VoucherMapper.xml`：连接 `tb_voucher` 和 `tb_seckill_voucher`，一次返回普通券信息与秒杀库存/时间。

`src/main/resources/db/hmdp.sql` 定义 11 张表：

- `tb_user`、`tb_user_info`：用户基础信息和详情。
- `tb_shop`、`tb_shop_type`：商铺与类型。
- `tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`：券、秒杀扩展、订单。
- `tb_blog`、`tb_blog_comments`：博客与评论。
- `tb_follow`：关注关系。
- `tb_sign`：传统签到表，但当前签到实际使用 Redis Bitmap，未写该表。

订单表的 `(user_id,voucher_id)` 唯一索引是“一人一单”的最终兜底；`voucher_id` 普通索引用于按券查询订单。

### 5. Entity 与 DTO

`entity/*.java` 与同名业务表映射：`User/UserInfo/Shop/ShopType/Voucher/SeckillVoucher/VoucherOrder/Blog/BlogComments/Follow`。`Shop.distance`、`Blog.name/icon/isLike` 等使用 `@TableField(exist=false)` 表示展示字段而非数据库列。

| DTO | 用途 |
|---|---|
| `LoginFormDTO.java` | 登录请求中的 phone/code/password |
| `UserDTO.java` | 放入 Redis 和 ThreadLocal 的脱敏用户信息 |
| `Result.java` | 全项目统一 `{success,errorMsg,data,total}` 响应 |
| `ScrollResult.java` | Feed 滚动分页的 list/minTime/offset |

### 6. 工具类和脚本

| 文件 | 作用 | 关键知识 |
|---|---|---|
| `RedisConstants.java` | Redis key 前缀和 TTL | key 命名、过期策略 |
| `UserHolder.java` | ThreadLocal 保存一次请求的用户 | 线程复用后必须 remove |
| `CacheClient.java` | 缓存穿透、逻辑过期等通用封装 | Cache Aside、空值缓存、击穿 |
| `RedisData.java` | 数据 + 逻辑过期时间 | 返回旧值、异步重建 |
| `RedisIdWorker.java` | 时间戳高位 + 日自增序列低位 | 分布式 ID、时钟和位运算 |
| `RegexPatterns/RegexUtils.java` | 手机号等格式校验 | 输入校验 |
| `SystemConstants.java` | 分页、昵称、上传目录 | 上传路径目前不可移植 |
| `PasswordEncoder.java` | 盐 + MD5 示例 | MD5 不适合生产密码，应用 BCrypt/Argon2 |
| `ILock/SimpleRedisLock.java` | 自研 Redis 锁学习代码 | SET NX EX、所有权校验、Lua 解锁 |
| `seckill.lua` | 原子检查库存/重复、预扣、登记 RESERVED | Lua 原子性、一人一单 |
| `seckill_compensate.lua` | 幂等返库存、移除资格、标记补偿 | 补偿事务 |
| `unLock.lua` | 比较锁值后删除 | 防止误删他人锁；注意文件名大小写与 Java 引用不一致 |

### 7. 异步、任务与测试

| 文件 | 作用 |
|---|---|
| `SeckillVoucherListener.java` | 消费 QA/QD 消息，事务创建订单，标记 CREATED |
| `SeckillOrderReconcileTask.java` | 扫描超时 pending；已落库则确认，未落库则重投，超限补偿 |
| `VoucherOrderStatus.java` | 定义未支付、已支付、已核销、取消、退款状态及允许边 |
| `VoucherOrderStatusTest.java` | 验证合法/非法状态流转 |
| `VoucherOrderControllerTest.java` | 批量登录生成 token 的旧测试；写路径使用 Windows 分隔符 |
| `ShopCacheTest.java` | 商铺缓存预热相关测试 |
| `HmDianPingApplicationTests.java` | Spring 上下文测试 |
| `perf/seckill.jmx` | 多 token 秒杀 JMeter 计划 |
| `perf/prepare.sh` | 重置库存、资格、状态并生成压测 token |
| `perf/run.sh` | 非 GUI 执行 JMeter |
| `perf/verify.sh` | 核对 Redis/MySQL 库存、订单数和重复用户 |
| `perf/schema.sql` | 压测环境订单表兜底建表 |

## 三、核心业务流程

### 1. 验证码登录

入口：`UserController.sendCode/login`。

验证码流程在 `UserServiceImpl.sendCode`：校验手机号 → 生成 6 位验证码 → 写 `login:code:{phone}`，TTL 2 分钟。当前只打印日志，不接真实短信平台。

登录流程在 `UserServiceImpl.login`：从 Redis 比对验证码 → 按手机号查/建用户 → 生成随机 token → 将 `UserDTO` 以 Hash 写入 `login:token:{token}` → 返回 token。

后续请求先经过 `RefreshTokenInterceptor`：读取 authorization → Redis 查 token → UserDTO 写入 `UserHolder` → 刷新 TTL。再经过 `LoginInterceptor` 判断 ThreadLocal 是否有用户。请求结束在 `afterCompletion` 清理 ThreadLocal。

面试表达：Redis token 解决多实例 Session 不共享；两层拦截器把“尝试恢复用户/续期”和“必须登录”分离。

### 2. 商铺详情与热点缓存

入口：`ShopController.queryShopById` → `ShopServiceImpl.queryById` → `HotShopCacheService.query`。

访问路径：

1. JD-hotkey 判断 `shop__{id}` 是否为热点。
2. 若是热点先查 Caffeine；命中直接返回，不访问 Redis。
3. 未命中走 `CacheClient.queryWithPassThrough` 查 Redis `cache:shop:{id}`。
4. Redis 未命中回源 MySQL；不存在则缓存空字符串 2 分钟，防穿透。
5. 热点数据回填 Caffeine；普通数据只留在 Redis。
6. 更新商铺后 `HotShopCacheService.invalidate` 同时删除本地和 Redis 缓存。

这属于 Cache Aside。当前更新顺序是先更新 DB 后删缓存，仍存在极小并发不一致窗口；可讨论延迟双删、订阅 binlog 或版本号。

### 3. 附近商铺

入口：`ShopController.queryShopByType` → `ShopServiceImpl.queryShopByType`。

无坐标时直接 MySQL 分页。有坐标时用 Redis GEO 在 `shop:geo:{typeId}` 中搜索 5km 内门店并带距离；先按距离取 ID，再用 `ORDER BY FIELD` 恢复 Redis 返回顺序，最后给 `Shop.distance` 赋值。

常问：Geo 底层历史上基于 ZSet/GeoHash；为什么不能直接对 MySQL 经纬度全表计算；为什么先查 ID 再查详情。

### 4. 签到

入口：`UserController.sign/signCount` → `UserServiceImpl`。

每个用户每月一个 Bitmap：`sign:{userId}:yyyyMM`。第 N 天写 offset=N-1。连续签到使用 BITFIELD 取本月截至今日的位，再从最低位连续与 1，遇 0 停止。

空间对比：31 天只需约 4 字节主体数据；数据库逐天一行更适合审计和复杂统计，但空间/查询成本更高。

### 5. 关注、共同关注和 Feed

关注入口在 `FollowController`，实现位于 `FollowServiceImpl`。DB `tb_follow` 保存真实关系，Redis Set `follows:{userId}` 保存关注 ID。共同关注使用 SINTER。

博客发布在 `BlogServiceImpl.saveBlog`：写 `tb_blog` 后查作者粉丝，把 blogId 以时间戳为 score 推入每个粉丝 `feed:{userId}` ZSet。这是推模式/写扩散。

Feed 查询在 `quertBlogOfFollow`：按 score 倒序范围查询，使用 `max + offset` 解决相同毫秒 score 导致的重复/遗漏，这叫滚动分页。

面试要比较：推模式读快写慢，适合普通作者；拉模式写快读慢，适合大 V；生产可采用推拉结合。

### 6. 博客点赞排行榜

入口：`BlogController.likeBlog/queryBlogLikes` → `BlogServiceImpl`。

ZSet `blog:liked:{blogId}`：member=userId，score=点赞时间。点赞时 DB `liked+1` 后 ZADD；取消时 `liked-1` 后 ZREM。查询前 5 个用户后使用 `ORDER BY FIELD` 保持 ZSet 顺序。

风险题：DB 成功 Redis 失败会不一致；可用事务消息、补偿或以某一方为准重建。并发取消还应保证 liked 不小于 0。

### 7. 秒杀：抢购与下单解耦

入口：`VoucherOrderController.seckillVoucher` → `VoucherOrderServiceImpl.seckillVoucher`。

阶段 A——抢购资格：

1. `RedisIdWorker` 生成订单 ID。
2. 执行 `seckill.lua`。
3. Lua 检查 `seckill:stock:{voucherId}`。
4. 使用 Set `seckill:order:{voucherId}` 判断用户是否取得过资格。
5. 原子扣 Redis 库存、记录用户、建立状态 Hash（RESERVED）、写入 pending ZSet。

阶段 B——异步下单：

1. Java 向交换机 X、routing key XA 投递订单 JSON。
2. QA 消费者执行 `createVoucherOrder`。
3. MySQL 再查一人一单，并以 `stock > 0` 条件扣减库存。
4. 同一事务保存订单；成功后 Redis 状态改为 CREATED 并移出 pending。

阶段 C——故障恢复：

`SeckillOrderReconcileTask` 每 5 秒检查超过 15 秒的 pending：MySQL 已有订单就补标 CREATED；没有则重投；达到 5 次仍失败执行 `seckill_compensate.lua`，原子返 Redis 库存、删除用户资格并标记 COMPENSATED。

正确概念：一人一单是业务约束/幂等，不是解耦。解耦来自 RabbitMQ。Redis Set + Lua 是入口防重；MySQL 唯一索引是最终兜底。

### 8. 订单状态机

`VoucherOrderStatus` 允许：UNPAID→PAID/CANCELLED，PAID→USED/REFUNDING，REFUNDING→REFUNDED。

`VoucherOrderServiceImpl.transition` 先校验订单归属和合法边，再执行：

`UPDATE ... SET status=target WHERE id=? AND status=current`

把旧状态放进 WHERE 是乐观并发控制：两个请求读到相同旧状态时，只有一个能更新成功。

## 四、Redis 数据结构总表

| 结构 | Key | 用途 |
|---|---|---|
| String | `login:code:{phone}` | 验证码 |
| Hash | `login:token:{token}` | 登录用户 |
| String/JSON | `cache:shop:{id}` | 商铺缓存与空值 |
| String | `seckill:stock:{voucherId}` | Redis 秒杀库存 |
| Set | `seckill:order:{voucherId}` | 已取得资格用户，一人一单 |
| Hash | `seckill:order:state:{orderId}` | RESERVED/SENT/RESENT/CREATED/COMPENSATED |
| ZSet | `seckill:pending` | 按时间扫描待落库订单 |
| Set | `follows:{userId}` | 关注列表/交集 |
| ZSet | `blog:liked:{blogId}` | 点赞用户时间榜 |
| ZSet | `feed:{userId}` | 关注流收件箱 |
| GEO | `shop:geo:{typeId}` | 附近商铺 |
| Bitmap | `sign:{userId}:yyyyMM` | 月签到 |
| String | `icr:order:yyyy:MM:dd` | 分布式 ID 日序列 |

## 五、高频面试题与答题骨架

### 为什么 Redis 能解决集群 Session？

应用实例不再持有本地 Session；任意实例都可凭 token 从共享 Redis 恢复用户。代价是每个请求多一次 Redis 访问，因此要设置 TTL、续期、降级和安全策略。

### 为什么两层拦截器？

第一层覆盖所有路径，尽可能恢复用户并续期；第二层只保护登录接口。若只有登录拦截器，访问公开接口时 token 不续期；若所有路径都强制登录，又破坏公开查询。

### 缓存穿透、击穿、雪崩分别是什么？

- 穿透：查不存在数据，每次打 DB；本项目缓存短 TTL 空值。
- 击穿：单个热点 key 失效，大量请求回源；仓库保留互斥锁/逻辑过期代码，现主链路用 hotkey+Caffeine 减压，但普通 Redis key 仍需策略。
- 雪崩：大量 key 同时失效或 Redis 故障；随机 TTL、多级缓存、限流熔断、Redis 高可用。

### Lua 为什么原子？

Redis 在执行脚本期间不会插入执行其他命令，因此“查库存、查用户、扣库存、登记资格”成为一个不可分割操作。它解决 Redis 内部竞态，不自动保证 Redis、MQ、MySQL跨系统事务。

### 为什么 Redis 扣了库存，MySQL 还要 `stock > 0`？

Redis 是高并发入口，MySQL 是最终事实来源。消息重复、缓存漂移或人工修复都可能让两边不一致；条件扣减是最终防超卖。唯一索引则最终防重复订单。

### MQ 如何保证不丢消息？

标准答案应包含生产者 confirm/return、交换机/队列/消息持久化、消费者 ACK、幂等、重试/死信和业务对账。当前项目已配置 confirm/return 和消费重试，并实现 pending 对账，但尚未注册 confirm/return 回调，不能声称完整生产级可靠投递。

### 分布式锁与 Lua 一人一单如何选？

Lua 在入口一次完成库存和资格判断，网络往返少，适合秒杀。分布式锁适合无法放进单个 Redis 脚本的跨步骤临界区，但吞吐更低。最终仍需数据库唯一索引。

### JD-hotkey 和 Caffeine 分别做什么？

JD-hotkey 识别“谁热”，Caffeine 保存“热数据副本”。只有 Caffeine 无法动态决定缓存对象；只有 hotkey 不保存业务值也不能减少 Redis 访问。多实例本地缓存存在一致性问题，所以 TTL 短、更新主动失效，生产可用 MQ 广播失效。

### 为什么 Feed 用 ZSet？

需要按发布时间排序、按 score 范围查询和滚动分页。List 难以按相同时间戳处理偏移，普通 Set 无序。

### Bitmap 连续签到如何算？

以今天为最低有效位取出截至今天的 bit 段，不断 `num & 1`；为 1 则计数并无符号右移，为 0 停止。

### Nginx 在项目中的价值？

统一入口、连接复用、负载均衡、限流、访问日志和故障摘除。Nginx 不解决业务幂等，也不能让数据库写入上限凭空提高。压测必须说明是直压应用还是经过 Nginx。

## 六、当前代码的诚实边界

1. `UserController.logout` 未删除 Redis token，属于未完成。
2. `BlogCommentsController` 没有评论接口。
3. `SystemConstants.IMAGE_UPLOAD_DIR` 写死 Windows 路径。
4. `VoucherController.addVoucher` 错误复用了秒杀券新增逻辑。
5. `QueueConfig` 给 QA 设置 10 秒 TTL，且 QD 仍会创建订单；这不是常规失败重试设计，需要重新定义语义。
6. publisher confirm/return 只配置未处理回调；对账降低丢单风险，但不能等同事务消息。
7. JD-hotkey 默认关闭，且 Compose 没有 etcd/worker；未部署前只能说“客户端接入完成”。
8. `CacheClient.queryWithLogicalExpire` 的 finally 使用 `unLock(key)` 而非 `unLock(lockKey)`；该旧路径当前未被商铺主链路调用。
9. `SimpleRedisLock` Java 引用 `unlock.lua`，资源实际名 `unLock.lua`，大小写敏感系统会失败；当前主链路未使用。
10. 部分旧代码和大段注释应清理，否则面试源码可读性较差。

## 七、建议的源码学习和打断点顺序

1. 启动 `HmDianPingApplication`，观察 Bean 和外部依赖连接。
2. 登录：`UserController.login` → `UserServiceImpl.login` → 两个 interceptor。
3. 商铺：`ShopController.queryShopById` → `HotShopCacheService` → `CacheClient`。
4. Redis 数据结构：依次操作签到、关注、点赞、附近商铺。
5. 秒杀：Controller → Service → `seckill.lua` → RabbitTemplate → Listener → MySQL。
6. 手动暂停消费者，观察 `seckill:pending` 与对账重投/补偿。
7. 调订单状态接口，同时发送两个不同状态变更请求，观察乐观更新只有一个成功。
8. 最后跑 `perf/prepare.sh`、`perf/run.sh`、`perf/verify.sh`，把业务正确性和性能指标一起记录。

面试复习时，每个模块都按四句话回答：业务问题是什么 → 为什么选该数据结构/中间件 → 源码怎么走 → 失败场景和改进是什么。
