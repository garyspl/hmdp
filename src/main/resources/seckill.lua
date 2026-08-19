-- 1.参数列表
--1.1.优惠券id
local voucherId=ARGV[1]
--1.2.用户id
local userId=ARGV[2]
--1.3.订单id
local orderId=ARGV[3]

-- 2.数据key
--2.1.库存key
local stockKey='seckill:stock:' .. voucherId
--2.2.订单key
local orderKey='seckill:order:' .. voucherId
local stateKey='seckill:order:state:' .. orderId

-- 3.脚本业务
--3.1.判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    --print("库存获取失败: " .. stockKey)
    return -1
end

if (stock<= 0) then
    --3.2.库存不足，返回1
    return 1
end
--3.3.判断用户是否下单
if(redis.call('sismember',orderKey,userId)==1) then
    --3.4.存在，说明重复下单，返回2
    return 2
end
-- 3.5.扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.6.下单(保存)用户 sadd orderKey userId
redis.call('sadd',orderKey,userId)
-- 资格预扣与待处理登记同属一次 Lua 原子操作。即使应用在发 MQ 前崩溃，对账也能找到它。
redis.call('hset',stateKey,'orderId',orderId,'userId',userId,'voucherId',voucherId,
    'state','RESERVED','retry','0')
redis.call('expire',stateKey,86400)
redis.call('zadd','seckill:pending',redis.call('time')[1],orderId)
-- MQ 只保留一种实现：Java 在脚本成功后投递 RabbitMQ。
-- 若同时 XADD Redis Stream，会产生两条独立消息链路，压测结果也无法解释。
return 0
