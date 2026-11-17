#!/system/bin/sh
# TempRoot VPN Tether - Modo Ponte Local
# Rode COMO ROOT. Escuta em 127.0.0.1:48999 e aceita SOMENTE:
#   <TOKEN> PING | ID | UP <vpn_if> <hotspot_if> <dns> | DOWN
# (nao executa comandos arbitrarios; interfaces/DNS sao validados)

PORT=48999
T=99
PREF=90
DIR=/data/local/tmp
STATE=$DIR/tvt.state
TOKEN_FILE=$DIR/tvt.token
SELF=$DIR/tvt-bridge.sh

[ "$(id -u)" = "0" ] || { echo "Execute como root."; exit 1; }

# Estagio 1: copia o script para /data e reexecuta dentro do namespace global (netd)
if [ -z "$TVT_NS" ]; then
  [ "$0" = "$SELF" ] || cp -f "$0" "$SELF"
  chmod 700 "$SELF"
  if command -v nsenter >/dev/null 2>&1; then
    export TVT_NS=1
    exec nsenter -t 1 -m -n sh "$SELF" "$@"
  fi
fi

valid_if() { case "$1" in ""|*[!A-Za-z0-9_.-]*) return 1;; esac; [ "${#1}" -le 15 ]; }
valid_ip() { case "$1" in ""|*[!0-9.]*) return 1;; esac; [ "$(printf %s "$1" | tr -cd . | wc -c)" -eq 3 ]; }
r() { echo "# $*"; "$@" 2>&1; }

do_down() {
  [ -f "$STATE" ] || { echo "Nada ativo"; [ "$1" = quiet ] || echo OK; return; }
  read vpn hs dns sub < "$STATE"
  if ! valid_if "$vpn" || ! valid_if "$hs" || ! valid_ip "$dns"; then
    rm -f "$STATE"; echo "ERR estado corrompido"; return
  fi
  r iptables -t nat -D PREROUTING -i "$hs" -p tcp --dport 53 -j DNAT --to-destination "$dns:53"
  r iptables -t nat -D PREROUTING -i "$hs" -p udp --dport 53 -j DNAT --to-destination "$dns:53"
  r iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
  r iptables -D FORWARD -i "$vpn" -o "$hs" -m state --state RELATED,ESTABLISHED -j ACCEPT
  r iptables -D FORWARD -i "$hs" -o "$vpn" -j ACCEPT
  r iptables -t nat -D POSTROUTING -o "$vpn" -j MASQUERADE
  r ip rule del iif "$hs" lookup $T pref $PREF
  r ip route flush table $T
  rm -f "$STATE"
  [ "$1" = quiet ] || echo OK
}

do_up() {
  vpn="$1"; hs="$2"; dns="$3"
  if ! valid_if "$vpn" || ! valid_if "$hs" || ! valid_ip "$dns"; then echo "ERR parametros invalidos"; return; fi
  [ -f "$STATE" ] && do_down quiet
  sub=$(ip route show table all dev "$hs" 2>/dev/null | awk '$1 ~ /^[0-9.]+\/[0-9]+$/ {print $1; exit}')
  echo "$vpn $hs $dns $sub" > "$STATE"   # persiste antes de aplicar
  fail=0
  r sysctl -w net.ipv4.ip_forward=1
  r ip route replace default dev "$vpn" table $T || fail=1
  [ -n "$sub" ] && r ip route replace "$sub" dev "$hs" table $T
  r ip rule add iif "$hs" lookup $T pref $PREF || fail=1
  r iptables -t nat -I POSTROUTING 1 -o "$vpn" -j MASQUERADE || fail=1
  r iptables -I FORWARD 1 -i "$hs" -o "$vpn" -j ACCEPT || fail=1
  r iptables -I FORWARD 1 -i "$vpn" -o "$hs" -m state --state RELATED,ESTABLISHED -j ACCEPT || fail=1
  r iptables -t mangle -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu || fail=1
  r iptables -t nat -I PREROUTING 1 -i "$hs" -p udp --dport 53 -j DNAT --to-destination "$dns:53" || fail=1
  r iptables -t nat -I PREROUTING 1 -i "$hs" -p tcp --dport 53 -j DNAT --to-destination "$dns:53" || fail=1
  if [ "$fail" = 1 ]; then do_down quiet; echo "ERR falha ao aplicar regras (revertido)"; else echo OK; fi
}

handle() {
  set -f
  IFS= read -r line || exit 0
  line=$(printf '%s' "$line" | tr -d '\r')
  set -- $line
  tok="$1"; verb="$2"
  if [ -z "$tok" ] || [ "$tok" != "$(cat "$TOKEN_FILE" 2>/dev/null)" ]; then echo "ERR token invalido"; exit 1; fi
  case "$verb" in
    PING) echo OK ;;
    ID)   id; echo OK ;;
    UP)   do_up "$3" "$4" "$5" ;;
    DOWN) do_down ;;
    *)    echo "ERR comando desconhecido" ;;
  esac
}

[ "$1" = "--handle" ] && { handle; exit 0; }

# Token aleatorio (apenas root le)
if [ ! -s "$TOKEN_FILE" ]; then
  head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE"
echo "=============================="
echo "TOKEN: $(cat "$TOKEN_FILE")"
echo "Escutando em 127.0.0.1:$PORT  (Ctrl+C para sair)"
echo "=============================="

if toybox nc --help 2>&1 | grep -q -- '-L'; then
  exec toybox nc -s 127.0.0.1 -p $PORT -L sh "$SELF" --handle
elif command -v busybox >/dev/null 2>&1; then
  exec busybox nc -ll -s 127.0.0.1 -p $PORT -e sh "$SELF" --handle
else
  echo "Nenhum nc com suporte a -L/-e encontrado (toybox/busybox)."; exit 1
fi
