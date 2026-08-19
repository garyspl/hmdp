local stateKey='seckill:order:state:' .. ARGV[3]
local state=redis.call('hget',stateKey,'state')
if state == 'CREATED' or state == 'COMPENSATED' then
    return 0
end
redis.call('incrby','seckill:stock:' .. ARGV[1],1)
redis.call('srem','seckill:order:' .. ARGV[1],ARGV[2])
redis.call('hset',stateKey,'state','COMPENSATED')
redis.call('zrem','seckill:pending',ARGV[3])
return 1
