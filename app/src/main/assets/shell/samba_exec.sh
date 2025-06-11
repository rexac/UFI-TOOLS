#!/system/bin/sh

LOG_FILE="/sdcard/smb_log.log"
MAX_SIZE=$((4 * 1024 * 1024))  # 4MB = 4 * 1024 * 1024 bytes

FLAG_FILE="/data/local/tmp/boot_once.flag"
THRESHOLD=120  # uptime 120s

SOCKET_DIR="/data/data/com.minikano.f50_sms/files"
SOCKET_FILE="$SOCKET_DIR/kano_root_shell.sock"
SOCAT_PATH="/data/data/com.minikano.f50_sms/files/socat"
TTYD_PATH="/data/data/com.minikano.f50_sms/files/ttyd"
BOOTUP_SCRIPT_PATH="/sdcard/ufi_tools_boot.sh"
SCHEDULE_SCRIPT_PATH="/sdcard/ufi_tools_schedule.sh"

UNLOCK_SAMBA_CONF='#!/system/bin/sh
chattr -i /data/samba/etc/smb.conf
chmod 644 /data/samba/etc/smb.conf
rm -f /data/samba/etc/smb.conf
sync'

BOOTUP_SH='#!/system/bin/sh
# you script here...
sync'

SCHEDULE_SH='#!/system/bin/sh
# you script here...
'

TASK_SH='#!/system/bin/sh
# you script here...
sync'

check_log_file(){
  # check log file
  if [ -f "$LOG_FILE" ]; then
      # get file size
      FILE_SIZE=$(wc -c < "$LOG_FILE")

      if [ "$FILE_SIZE" -ge "$MAX_SIZE" ]; then
          echo "Log file exceeded 4MB, clearing..." > "$LOG_FILE"
      fi
  fi
}

check_ttyd_running(){
  # check ttyd running
  if ! ps -ef | grep "ttyd --writable --port 1146 /system/bin/sh" | grep -v grep > /dev/null; then
      echo "[`date`] start ttyd..." >> "$LOG_FILE"
      export PATH="/data/data/com.termux/files/usr/bin:$PATH"
      "$TTYD_PATH" --writable --port 1146 /system/bin/sh &
  else
      echo "[`date`] ttyd already running." >> "$LOG_FILE"
  fi
}

check_socat_running(){
  # check socat
  mkdir -p "$SOCKET_DIR"
  # check socat running
  if ! ps -ef | grep "$SOCKET_FILE" | grep -v grep > /dev/null; then
      echo "[`date`] start socat..." >> "$LOG_FILE"
      # run socat unix socket，exec /system/bin/sh
      "$SOCAT_PATH" -d -d UNIX-LISTEN:"$SOCKET_FILE",fork,reuseaddr,unlink-early EXEC:/system/bin/sh &
  else
      echo "[`date`] socat already running." >> "$LOG_FILE"
  fi
}

#boot_script
boot_up_script() {
  #lock samba conf
  chmod 444 /data/samba/etc/smb.conf
  chattr +i /data/samba/etc/smb.conf

  echo "$UNLOCK_SAMBA_CONF" > /cache/unlock_samba.sh
  echo "$UNLOCK_SAMBA_CONF" > /sdcard/unlock_samba.sh

  if [ -f "$BOOTUP_SCRIPT_PATH" ]; then
      echo "[`date`] exec $BOOTUP_SCRIPT_PATH ..." >> "$LOG_FILE"
      sh "$BOOTUP_SCRIPT_PATH"
  else
      echo "$BOOTUP_SH" > /sdcard/ufi_tools_boot.sh
      chmod +x /sdcard/ufi_tools_boot.sh
      echo "[`date`] $BOOTUP_SCRIPT_PATH not found，skip" >> "$LOG_FILE"
  fi

  #Drop port for ipv6
  ip6tables -A INPUT -p tcp --dport 8080 -j DROP
  ip6tables -A INPUT -p tcp --dport 1146 -j DROP
  ip6tables -A INPUT -p tcp --dport 139 -j DROP
  ip6tables -A INPUT -p tcp --dport 445 -j DROP
  ip6tables -A INPUT -p tcp --dport 5555 -j DROP

  check_log_file
  check_ttyd_running
  check_socat_running
}

#schedule_script(30s per time)
schedule_script() {
  if [ -f "$SCHEDULE_SCRIPT_PATH" ]; then
      echo "[`date`] exec $SCHEDULE_SCRIPT_PATH ..." >> "$LOG_FILE"
      sh "$SCHEDULE_SCRIPT_PATH"
  else
      echo "$SCHEDULE_SH" > /sdcard/ufi_tools_schedule.sh
      chmod +x /sdcard/ufi_tools_schedule.sh
      echo "[`date`] $SCHEDULE_SCRIPT_PATH not found，skip" >> "$LOG_FILE"
  fi

  check_log_file
  check_ttyd_running
  check_socat_running
}

uptime_seconds=$(cut -d. -f1 /proc/uptime)
now_ts=$(date +%s)
boot_time=$((now_ts - uptime_seconds))

if [ -f "$FLAG_FILE" ]; then
    last_boot_time=$(cat "$FLAG_FILE" 2>/dev/null)
else
    last_boot_time=0
fi

echo "[`date`] samba_exec.sh start running..." >> "$LOG_FILE"
echo "[`date`] uptime_seconds=$uptime_seconds, now_ts=$now_ts, boot_time=$boot_time, last_boot_time=$last_boot_time" >> "$LOG_FILE"

if [ "$uptime_seconds" -lt "$THRESHOLD" ]; then
    echo "[`date`] boot window (<${THRESHOLD}s) detected." >> "$LOG_FILE"

    if [ "$boot_time" -ne "$last_boot_time" ]; then
        echo "[`date`] detected new boot, running boot_up_script..." >> "$LOG_FILE"
        boot_up_script
        echo "$boot_time" > "$FLAG_FILE"; sync
    else
        echo "[`date`] same boot_time detected, skipping boot_up_script. Running schedule_script instead..." >> "$LOG_FILE"
        schedule_script
    fi
else
    echo "[`date`] outside boot window (>${THRESHOLD}s), running schedule_script..." >> "$LOG_FILE"
    schedule_script
fi