#!/system/bin/sh

IFACE="br0"  # 设备
BASE_CLASSID="10"

json_output() {
  echo "$1" | tr -d '\n'
}

ip_to_mark() {
  ip="$1"
  last_byte=$(echo "$ip" | awk -F. '{print $4}')
  echo $((100 + last_byte))
}

ensure_tc_root() {
  tc qdisc show dev "$IFACE" | grep -q "htb"
  if [ $? -ne 0 ]; then
    tc qdisc add dev "$IFACE" root handle 1: htb default 9999
    tc class add dev "$IFACE" parent 1: classid 1:1 htb rate 10000mbit ceil 10000mbit
    tc class add dev "$IFACE" parent 1:1 classid 1:9999 htb rate 10000mbit ceil 10000mbit
  fi
}

ip_to_classid() {
  ip="$1"
  last_byte=$(echo "$ip" | awk -F. '{print $4}')
  classid=$(printf "%x" $((BASE_CLASSID + last_byte)))
  echo "1:$classid"
}

add_limit() {
  ip="$1"
  downlimit="$2"  # 实际上限制上传 = 控制下载
  ensure_tc_root

  classid=$(ip_to_classid "$ip")
  mark=$(ip_to_mark "$ip")

  iptables -t mangle -A POSTROUTING -d "$ip" -j MARK --set-mark "$mark" 2>/dev/null
  tc class add dev "$IFACE" parent 1:1 classid "$classid" htb rate "${downlimit}kbit" ceil "${downlimit}kbit" 2>/dev/null
  tc filter add dev "$IFACE" parent 1: protocol ip handle "$mark" fw flowid "$classid" 2>/dev/null

  if [ $? -eq 0 ]; then
    json_output '{"done":true}'
  else
    json_output '{"done":false}'
  fi
}

show_limit() {
  ip="$1"
  classid=$(ip_to_classid "$ip")
  mark=$(ip_to_mark "$ip")

  # 检查是否存在对应的 iptables 规则
  iptables -t mangle -L POSTROUTING -n | grep -q -- "-d $ip .*MARK set $mark"
  ipt_found=$?

  # 检查是否存在对应的 tc class
  tc class show dev "$IFACE" 2>/dev/null | grep -q "$classid"
  class_found=$?

  # 检查是否存在对应的 tc filter
  tc filter show dev "$IFACE" 2>/dev/null | grep -q "handle $mark"
  filter_found=$?

  if [ "$ipt_found" -eq 0 ] || [ "$class_found" -eq 0 ] || [ "$filter_found" -eq 0 ]; then
    json_output '{"done":true,"devices":["'"$ip"'"]}'
  else
    json_output '{"done":true,"devices":[]}'
  fi
}

release_limit() {
  ip="$1"
  classid=$(ip_to_classid "$ip")
  mark=$(ip_to_mark "$ip")

  limit_exists=false

  # 是否存在限速规则
  iptables -t mangle -L POSTROUTING -n | grep -q "$ip" && limit_exists=true
  tc class show dev "$IFACE" 2>/dev/null | grep -q "$classid" && limit_exists=true
  tc filter show dev "$IFACE" 2>/dev/null | grep -q "handle $mark" && limit_exists=true

  # 删除 iptables 规则（循环删除，直到删完）
  while iptables -t mangle -L POSTROUTING -n | grep -q "$ip"; do
    iptables -t mangle -D POSTROUTING -d "$ip" -j MARK --set-mark "$mark" 2>/dev/null || break
  done

  # 删除 tc filter（也循环，防止残留）
  while tc filter show dev "$IFACE" 2>/dev/null | grep -q "handle $mark"; do
    tc filter del dev "$IFACE" parent 1: protocol ip handle "$mark" fw 2>/dev/null || break
  done

  # 删除 tc class
  tc class del dev "$IFACE" classid "$classid" 2>/dev/null

  # 最终确认是否都删除干净（再查一遍）
  iptables -t mangle -L POSTROUTING -n | grep -q "$ip" || \
  (tc class show dev "$IFACE" 2>/dev/null | grep -q "$classid") || \
  (tc filter show dev "$IFACE" 2>/dev/null | grep -q "handle $mark") || \
    limit_exists=false

  if [ "$limit_exists" = true ]; then
    json_output '{"done":true,"existed":true}'
  else
    json_output '{"done":true,"existed":false}'
  fi
}

# ========= 参数解析 =========
if [ "$1" = "--limit" ]; then
  if [ "$#" -eq 2 ]; then
    show_limit "$2"
  elif [ "$#" -eq 3 ]; then
    add_limit "$2" "$3"
  else
    json_output '{"done":false}'
  fi
elif [ "$1" = "--release" ]; then
  if [ "$#" -eq 2 ]; then
    release_limit "$2"
  else
    json_output '{"done":false}'
  fi
else
  json_output '{"done":false}'
fi