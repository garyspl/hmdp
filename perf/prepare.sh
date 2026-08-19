#!/usr/bin/env bash
set -euo pipefail
USERS="${USERS:-1000}"
VOUCHER_ID="${VOUCHER_ID:-100}"
STOCK="${STOCK:-$USERS}"
MYSQL_PASSWORD="${HMDP_MYSQL_PASSWORD:-290390}"

mkdir -p perf/data perf/results
mysql -h127.0.0.1 -uroot -p"$MYSQL_PASSWORD" dingping -e "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES ($VOUCHER_ID,$STOCK,NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY) ON DUPLICATE KEY UPDATE stock=VALUES(stock),begin_time=VALUES(begin_time),end_time=VALUES(end_time); DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;"
redis-cli SET "seckill:stock:$VOUCHER_ID" "$STOCK" >/dev/null
redis-cli DEL "seckill:order:$VOUCHER_ID" >/dev/null
old_pending="$(redis-cli ZRANGE seckill:pending 0 -1)"
if [[ -n "$old_pending" ]]; then
  while IFS= read -r order_id; do redis-cli DEL "seckill:order:state:$order_id" >/dev/null; done <<< "$old_pending"
fi
redis-cli DEL seckill:pending >/dev/null
: > perf/data/tokens.csv
for ((i=1; i<=USERS; i++)); do
  token="perf-$i"
  user_id=$((1000000+i))
  redis-cli HSET "login:token:$token" id "$user_id" nickName "perf-$i" >/dev/null
  redis-cli EXPIRE "login:token:$token" 7200 >/dev/null
  printf '%s\n' "$token" >> perf/data/tokens.csv
done
echo "Prepared voucher=$VOUCHER_ID stock=$STOCK users=$USERS"
