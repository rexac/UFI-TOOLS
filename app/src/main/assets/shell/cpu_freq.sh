#!/system/bin/sh

json="{"
first=1

for i in /sys/devices/system/cpu/cpu[0-9]*; do
    core=${i##*/}
    cur_freq_file="$i/cpufreq/scaling_cur_freq"
    max_freq_file="$i/cpufreq/cpuinfo_max_freq"

    if [ -f "$cur_freq_file" ] && [ -f "$max_freq_file" ]; then
        read -r cur < "$cur_freq_file"
        read -r max < "$max_freq_file"

        cur_mhz=$((cur / 1000))
        max_mhz=$((max / 1000))

        [ $first -eq 0 ] && json="${json},"
        json="${json}\"$core\":{\"cur\":$cur_mhz,\"max\":$max_mhz}"
        first=0
    fi
done

json="${json}}"
echo "$json"