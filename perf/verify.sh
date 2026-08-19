#!/usr/bin/env bash
set -euo pipefail
VOUCHER_ID="${VOUCHER_ID:-100}"
MYSQL_PASSWORD="${HMDP_MYSQL_PASSWORD:-290390}"
echo "Redis stock: $(redis-cli GET seckill:stock:$VOUCHER_ID)"
mysql -h127.0.0.1 -uroot -p"$MYSQL_PASSWORD" dingping -e "SELECT stock AS mysql_stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID; SELECT COUNT(*) AS orders,COUNT(DISTINCT user_id) AS users FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID; SELECT user_id,COUNT(*) c FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID GROUP BY user_id HAVING c>1;"

