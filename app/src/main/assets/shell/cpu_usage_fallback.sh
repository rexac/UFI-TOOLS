#!/system/bin/sh

if [ -w "/data/local/tmp" ]; then
    TMPDIR="/data/local/tmp"
elif [ -w "/sdcard" ]; then
    TMPDIR="/sdcard"
else
    echo 0
    exit 1
fi

STAT1="$TMPDIR/stat_cpu.txt"
STAT2="$TMPDIR/stat_cpu_2.txt"

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

# 读取两次状态
read_cpu_stat > "$STAT1"
sleep 0.2
read_cpu_stat > "$STAT2"

json="{"
first=1

while read -r cpu total1 idle1; do
    line2=$(grep "^$cpu " "$STAT2")
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
done < "$STAT1"

json="$json}"
echo "$json"

# 清理
rm "$STAT1" "$STAT2"