#!/system/bin/sh

read_cpu_stat() {
    while IFS= read -r line; do
        case $line in
            cpu*)
                set -- $line
                cpu=$1
                shift
                total=0
                i=0
                idle=0
                for val in "$@"; do
                    total=$((total + val))
                    if [ "$i" -eq 3 ]; then
                        idle=$val
                    elif [ "$i" -eq 4 ]; then
                        idle=$((idle + val))
                    fi
                    i=$((i + 1))
                done
                echo "$cpu $total $idle"
                ;;
            *)
                break
                ;;
        esac
    done < /proc/stat
}

# 读取两次 CPU 状态（短暂 sleep 提供 delta 差值）
stats1=$(read_cpu_stat)
sleep 0.1
stats2=$(read_cpu_stat)

# 处理为 JSON
json="{"
first=1

# 用 echo 到文件临时再读入，或重定向 while 输入，避免子进程影响变量作用域
echo "$stats1" > /data/local/tmp/stat1.txt
echo "$stats2" > /data/local/tmp/stat2.txt

while read -r cpu total1 idle1; do
    line2=$(grep "^$cpu " /data/local/tmp/stat2.txt)
    total2=$(echo "$line2" | cut -d' ' -f2)
    idle2=$(echo "$line2" | cut -d' ' -f3)

    total_diff=$((total2 - total1))
    idle_diff=$((idle2 - idle1))
    if [ "$total_diff" -eq 0 ]; then
        usage="0.0"
    else
        usage=$(echo "(($total_diff - $idle_diff) * 100.0) / $total_diff" | bc -l | awk '{printf "%.1f", $0}')
    fi

    [ $first -eq 0 ] && json="$json,"
    json="$json\"$cpu\":$usage"
    first=0
done < /data/local/tmp/stat1.txt

json="$json}"
echo "$json"