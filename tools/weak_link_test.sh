#!/usr/bin/env bash
#
# WHAT THIS SCRIPT MEASURES ON AN EMULATOR: NOTHING.
#
# It drives the field phone through `adb emu network speed`, which throttles
# the emulator's simulated cellular radio. An emulator has no cellular data
# path at all, so the tiers below do not constrain the link. An earlier run of
# this produced a tidy table showing the app working at EDGE and failing at
# GPRS; that table was thrown away because it was measuring the tap loop, not
# the network. Do not quote numbers this script prints on an emulator.
#
# It is kept because it is the right harness for the run that matters: a real
# device on a 2G-only SIM, with a real radio. Against real hardware the tier
# calls become no-ops and the sent/acked/loss/RTT accounting is honest.
#
# See README.md, "What is honestly still ahead".

set -uo pipefail

FIELD="${1:?usage: weak_link_test.sh <field-serial> [frames]}"
FRAMES="${2:-4}"
PKG=com.androidengineers.agent_quickstart_android
SEND_TAP="${SEND_TAP:-540 2272}"
OUT="${TMPDIR:-/tmp}/sutra-weak-link.md"

tier() {
  adb -s "$FIELD" emu network speed "$1" >/dev/null 2>&1
  adb -s "$FIELD" emu network delay "$2" >/dev/null 2>&1
}

echo "| network | sent | acked | loss | median RTT | notes |" | tee "$OUT"
echo "|---|---|---|---|---|---|" | tee -a "$OUT"

for spec in "full none" "umts umts" "edge edge" "gprs gprs" "gsm gprs"; do
  set -- $spec
  speed="$1"; delay="$2"
  tier "$speed" "$delay"
  sleep 6

  adb -s "$FIELD" logcat -c
  for _ in $(seq 1 "$FRAMES"); do
    adb -s "$FIELD" shell input tap $SEND_TAP
    sleep 4
  done
  sleep 6

  log="$(adb -s "$FIELD" logcat -d -s SutraField:I 2>/dev/null)"
  sent="$(grep -c 'sent seq=' <<<"$log")"
  acked="$(grep -c 'ack seq=' <<<"$log")"
  rtts="$(grep -o 'rttMs=[0-9]*' <<<"$log" | cut -d= -f2 | sort -n)"
  median="$(awk '{a[NR]=$1} END{if(NR){print (NR%2)?a[(NR+1)/2]:int((a[NR/2]+a[NR/2+1])/2); }else print "-"}' <<<"$rtts")"
  lost=$(( sent - acked ))
  pct=0; [ "$sent" -gt 0 ] && pct=$(( lost * 100 / sent ))

  note=""
  [ "$acked" -eq 0 ] && note="nothing acknowledged"
  grep -q "connection" <<<"$log" && note="$note rtc state change"

  echo "| $speed (delay $delay) | $sent | $acked | ${pct}% | ${median} ms | $note |" | tee -a "$OUT"
done

tier full none
echo >> "$OUT"
echo "Written to $OUT"
