#!/system/bin/sh

DIAG_DEV="/dev/sdiag_nr"
REQ_HEX="7e00000000000800007e7effffffff000800007e7e030000000a005e8100007e"
REQ_BIN="/data/local/tmp/diag_req.bin"
RESP_BIN="/data/local/tmp/diag_resp.bin"

echo "$REQ_HEX" | xxd -r -p > "$REQ_BIN"
rm -f "$RESP_BIN"

if command -v lsof >/dev/null 2>&1; then
  for pid in $(lsof "$DIAG_DEV" 2>/dev/null | awk 'NR>1 {print $2}'); do
    kill -9 "$pid" 2>/dev/null
  done
fi

timeout 1 cat "$DIAG_DEV" > "$RESP_BIN" &
READ_PID=$!

sleep 0.1
cat "$REQ_BIN" > "$DIAG_DEV"

wait $READ_PID

size=$(stat -c %s "$RESP_BIN" 2>/dev/null || echo 0)
if [ "$size" -eq 0 ]; then
  echo "错误:读取超时或无数据"
  exit 1
fi

HEX_CONTENT=$(xxd -p "$RESP_BIN" | tr -d '\n')

START_POS=$(echo "$HEX_CONTENT" | grep -b -o "7e03" | head -1 | cut -d: -f1)

if [ -z "$START_POS" ]; then
  echo "错误:未找到 IMEI 数据起始位置"
  exit 1
fi

BYTE_START=$((START_POS / 2 + 9))

IMEI_HEX=$(dd if="$RESP_BIN" bs=1 skip=$BYTE_START count=8 2>/dev/null | xxd -p)

imei=""

i=0
while [ $i -lt 16 ]; do
  byte_index=$((i / 2))
  pos=$((byte_index * 2 + 1))
  high_nibble=$(echo "$IMEI_HEX" | cut -c $pos)
  low_nibble=$(echo "$IMEI_HEX" | cut -c $((pos+1)))
  if [ $((i % 2)) -eq 0 ]; then
    imei="$imei$low_nibble"
  else
    imei="$imei$high_nibble"
  fi
  i=$((i + 1))
done
imei=$(echo "$imei" | sed 's/^a//')
imei=$(echo "$imei" | cut -c1-15)
echo "$imei"

rm -f $REQ_BIN
rm -f $RESP_BIN