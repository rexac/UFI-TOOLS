#!/system/bin/sh

meminfo=$(cat /proc/meminfo)

# 读取主内存信息
total=$(echo "$meminfo" | grep '^MemTotal:' | awk '{print $2}')
available=$(echo "$meminfo" | grep '^MemAvailable:' | awk '{print $2}')
used=$((total - available))
usage=$(echo "scale=1; $used * 100 / $total" | bc)

# 读取 swap 信息
swaptotal=$(echo "$meminfo" | grep '^SwapTotal:' | awk '{print $2}')
swapfree=$(echo "$meminfo" | grep '^SwapFree:' | awk '{print $2}')
swapused=$((swaptotal - swapfree))
if [ "$swaptotal" -eq 0 ]; then
  swapusage="0.0"
else
  swapusage=$(echo "scale=1; $swapused * 100 / $swaptotal" | bc)
fi

# 输出 JSON 一行
echo "{\"mem_total_kb\":$total,\"mem_available_kb\":$available,\"mem_used_kb\":$used,\"mem_usage_percent\":$usage,\"swap_total_kb\":$swaptotal,\"swap_free_kb\":$swapfree,\"swap_used_kb\":$swapused,\"swap_usage_percent\":$swapusage}"