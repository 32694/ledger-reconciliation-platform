#!/bin/sh

set -eu

if [ "$#" -ne 2 ]; then
    echo "用法: $0 <输出 CSV 路径> <行数 1..100000>" >&2
    exit 64
fi

output=$1
rows=$2

if [ -d "$output" ]; then
    echo "输出路径不能是目录" >&2
    exit 64
fi

case $rows in
    ''|*[!0-9]*)
        echo "行数必须是 1 到 100000 的整数" >&2
        exit 64
        ;;
esac

if [ "$rows" -lt 1 ] || [ "$rows" -gt 100000 ]; then
    echo "行数必须是 1 到 100000 的整数" >&2
    exit 64
fi

temporary_file="${output}.tmp.$$"
trap 'rm -f "$temporary_file"' 0 1 2 15

awk -v rows="$rows" 'BEGIN {
    print "channel_transaction_id,amount_cents,occurred_at"
    for (row = 1; row <= rows; row++) {
        day = 1 + int((row - 1) / 86400)
        hour = int((row - 1) / 3600) % 24
        minute = int((row - 1) / 60) % 60
        second = (row - 1) % 60
        if (row % 10 == 0) {
            reference = sprintf("PERF-MATCH-%06d", row)
            amount = 1000
        } else if (row % 10 == 1) {
            reference = sprintf("PERF-MISMATCH-%06d", row)
            amount = 1001
        } else {
            reference = sprintf("PERF-CHANNEL-%06d", row)
            amount = 1000
        }
        printf "%s,%d,2026-01-%02dT%02d:%02d:%02dZ\n", \
            reference, amount, day, hour, minute, second
    }
}' > "$temporary_file"

mv "$temporary_file" "$output"
trap - 0 1 2 15
