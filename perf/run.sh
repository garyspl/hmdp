#!/usr/bin/env bash
set -euo pipefail
THREADS="${THREADS:-100}"
RAMP="${RAMP:-10}"
LOOPS="${LOOPS:-1}"
VOUCHER_ID="${VOUCHER_ID:-100}"
rm -rf perf/results/report perf/results/result.jtl
jmeter -n -t perf/seckill.jmx -Jthreads="$THREADS" -Jramp="$RAMP" -Jloops="$LOOPS" -JvoucherId="$VOUCHER_ID" -l perf/results/result.jtl -e -o perf/results/report

