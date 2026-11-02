#!/usr/bin/env bash
# Start, stop and check the kubeadm cluster VMs on GCE, and keep the GoDaddy
# A records pointed at the ingress node (worker1).
#   gce-cluster.sh status | start | stop | ip | dns | dns-setup
set -euo pipefail

PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
ZONE="${ZONE:-asia-south1-a}"
MASTER="${MASTER:-k8s-master}"
WORKERS="${WORKERS:-k8s-worker1 k8s-worker2 k8s-worker3}"
INGRESS_NODE="${INGRESS_NODE:-k8s-worker1}"          # node running Traefik (DNS points here)
DOMAINS="${DOMAINS:-shop.bhumaji.com argocd.bhumaji.com grafana.bhumaji.com prom.bhumaji.com}"
DNS_ZONE="${DNS_ZONE:-bhumaji.com}"                   # only subdomains of this zone are touched, never the apex
DNS_TTL="${DNS_TTL:-600}"
GODADDY_ENV="${GODADDY_ENV:-$HOME/.config/gce-cluster/godaddy.env}"
GODADDY_API="https://api.godaddy.com/v3/domains"

g() { gcloud --project "$PROJECT" "$@"; }
say() { printf '\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }

external_ip() {
  g compute instances describe "$1" --zone "$ZONE" \
    --format='get(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null
}
# Ask the zone's own name server when possible, so a fresh update shows up
# immediately instead of waiting for resolver caches.
resolve() {
  if command -v dig >/dev/null; then
    local ns; ns="$(dig +short NS "$DNS_ZONE" | head -1)"
    if [ -n "$ns" ]; then dig +short @"$ns" "$1" A | tail -1; else dig +short "$1" A | tail -1; fi
  else getent ahostsv4 "$1" | awk 'NR==1{print $1}'; fi
}
show_vms() {
  g compute instances list --zones "$ZONE" \
    --filter="name=($MASTER ${WORKERS// / })" \
    --format="table(name, status, networkInterfaces[0].networkIP:label=INTERNAL_IP, networkInterfaces[0].accessConfigs[0].natIP:label=EXTERNAL_IP, machineType.basename():label=TYPE)"
}
check_dns() {
  local ip; ip="$(external_ip "$INGRESS_NODE")"
  if [ -z "$ip" ]; then bad "$INGRESS_NODE has no external IP (is it running?)"; return; fi
  say "DNS check (records must point to $INGRESS_NODE = $ip)"
  local d cur mismatch=0
  for d in $DOMAINS; do
    cur="$(resolve "$d" || true)"
    if [ "$cur" = "$ip" ]; then ok "$d -> $cur"
    else bad "$d -> ${cur:-<no record>}  (should be $ip)"; mismatch=1; fi
  done
  if [ "$mismatch" -eq 1 ]; then
    echo "  Fix with: $0 dns   (or update the A records by hand to $ip)"
  fi
}

# ---- GoDaddy (Domains API v3, personal access token) ----------------------
# Token scopes needed: domains.domain:read and domains.dns:update
# godaddy METHOD PATH [JSON]  ->  prints "<body>\n<http code>"
godaddy() {
  local args=(-sS -X "$1" -w '\n%{http_code}'
    -H "Authorization: Bearer ${GODADDY_PAT}"
    -H 'Accept: application/json')
  [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' --data "$3")
  curl "${args[@]}" "$GODADDY_API/zones/$DNS_ZONE$2" || printf '\n000'
}
explain_http() {
  case "$1" in
    401) echo "    GoDaddy rejected the token (expired or wrong). Run: $0 dns-setup" ;;
    403) echo "    The token is missing a scope. It needs domains.domain:read and domains.dns:update." ;;
    404) echo "    $DNS_ZONE is not in this GoDaddy account." ;;
    429) echo "    Too many requests - wait a minute and run: $0 dns" ;;
    000) echo "    Could not reach api.godaddy.com." ;;
  esac
}
load_godaddy() {
  if [ ! -r "$GODADDY_ENV" ]; then
    bad "no GoDaddy token saved yet. Run once: $0 dns-setup"; return 1
  fi
  # shellcheck disable=SC1090
  . "$GODADDY_ENV"
  [ -n "${GODADDY_PAT:-}" ] || { bad "$GODADDY_ENV has no token. Run: $0 dns-setup"; return 1; }
}
# JSON list body on stdin -> one "recordId data" line per record
records() {
  python3 -c 'import json,sys
for r in json.load(sys.stdin).get("items", []):
    print(r.get("recordId") or r.get("id") or "", r.get("data", ""))'
}
update_dns() {
  load_godaddy || return 1
  local ip; ip="$(external_ip "$INGRESS_NODE")"
  [ -n "$ip" ] || { bad "$INGRESS_NODE has no external IP (is it running?)"; return 1; }
  say "GoDaddy: pointing A records at $INGRESS_NODE = $ip"
  local d name resp code body rows id cur n failed=0 rec
  for d in $DOMAINS; do
    case "$d" in
      *."$DNS_ZONE") name="${d%."$DNS_ZONE"}" ;;
      *) bad "$d is not a subdomain of $DNS_ZONE - skipped (the apex is never changed)"; continue ;;
    esac
    resp="$(godaddy GET "/dns-records?type=A&name=$name")"
    code="${resp##*$'\n'}"; body="${resp%$'\n'*}"
    if [ "$code" != 200 ]; then bad "$d: lookup failed (HTTP $code)"; explain_http "$code"; failed=1; continue; fi
    rows="$(printf '%s' "$body" | records)" || { bad "$d: could not read GoDaddy's reply"; failed=1; continue; }
    n="$(printf '%s' "$rows" | grep -c . || true)"
    rec="{\"name\":\"$name\",\"type\":\"A\",\"data\":\"$ip\",\"ttl\":$DNS_TTL}"
    if [ "$n" -eq 0 ]; then
      resp="$(godaddy POST "/dns-records" "$rec")"; code="${resp##*$'\n'}"
      if [ "$code" = 201 ] || [ "$code" = 200 ]; then ok "$d -> $ip (new record)"
      else bad "$d: create failed (HTTP $code)"; explain_http "$code"; failed=1; fi
      continue
    fi
    [ "$n" -gt 1 ] && bad "$d has $n A records in GoDaddy - updating the first; delete the extras by hand"
    id="${rows%%$'\n'*}"; cur="${id#* }"; id="${id%% *}"
    if [ "$cur" = "$ip" ]; then ok "$d already $ip"; continue; fi
    resp="$(godaddy PUT "/dns-records/$id" "$rec")"; code="${resp##*$'\n'}"
    if [ "$code" = 200 ]; then ok "$d -> $ip (was ${cur:-?})"
    else bad "$d: update failed (HTTP $code)"; explain_http "$code"; failed=1; fi
  done
  [ "$failed" -eq 0 ] && echo "  Browsers and ISPs may keep the old IP cached for up to $((DNS_TTL / 60)) minutes."
  return "$failed"
}
cmd_dns_setup() {
  say "Save GoDaddy personal access token (scopes: domains.domain:read, domains.dns:update)"
  local token resp code
  read -rsp "  Token (hidden): " token; echo
  [ -n "$token" ] || { bad "no token entered"; exit 1; }
  GODADDY_PAT="$token"
  resp="$(godaddy GET "/dns-records?type=A&pageSize=1")"; code="${resp##*$'\n'}"
  if [ "$code" != 200 ]; then bad "test call failed (HTTP $code) - nothing saved"; explain_http "$code"; exit 1; fi
  mkdir -p "$(dirname "$GODADDY_ENV")"
  ( umask 077; printf 'GODADDY_PAT=%q\n' "$token" > "$GODADDY_ENV" )
  ok "token works for $DNS_ZONE; saved to $GODADDY_ENV (readable only by you)"
  echo "  Now run: $0 dns"
}
# ---------------------------------------------------------------------------

show_nodes() {
  say "Kubernetes nodes (via $MASTER)"
  g compute ssh "$MASTER" --zone "$ZONE" --quiet --command \
    "kubectl get nodes; echo; kubectl get pods -A --no-headers | awk '\$4!=\"Running\" && \$4!=\"Completed\"{n++; print \"  not running: \"\$1\"/\"\$2\" \"\$4} END{if(!n) print \"  all pods Running\"}'" \
    2>/dev/null || bad "could not reach $MASTER over SSH (is it running?)"
}
wait_for_nodes() {
  say "Waiting for Kubernetes nodes to become Ready (up to 5 min)"
  local _
  for _ in $(seq 1 30); do
    if g compute ssh "$MASTER" --zone "$ZONE" --quiet --command \
        "kubectl get nodes --no-headers 2>/dev/null | awk '\$2!=\"Ready\"{n++} END{exit (NR==0 || n>0)}'" 2>/dev/null; then
      ok "all nodes Ready"; return 0
    fi
    sleep 10
  done
  bad "nodes not Ready yet; check again with: $0 status"
}
cmd_start() {
  say "Starting $MASTER"
  g compute instances start "$MASTER" --zone "$ZONE"
  say "Starting workers: $WORKERS"
  # shellcheck disable=SC2086
  g compute instances start $WORKERS --zone "$ZONE"
  show_vms
  if [ -r "$GODADDY_ENV" ]; then update_dns || true; fi
  wait_for_nodes
  check_dns
  echo
  echo "Apps need 3-5 more minutes (databases and Kafka start first; some pods restart once)."
}
cmd_stop() {
  say "Stopping workers: $WORKERS"
  # shellcheck disable=SC2086
  g compute instances stop $WORKERS --zone "$ZONE"
  say "Stopping $MASTER"
  g compute instances stop "$MASTER" --zone "$ZONE"
  show_vms
  echo "Stopped VMs are not billed for CPU/RAM; their disks (and any static IPs) still are."
}
cmd_status() {
  say "VMs (project $PROJECT, zone $ZONE)"
  show_vms
  if [ "$(g compute instances describe "$MASTER" --zone "$ZONE" --format='get(status)')" = "RUNNING" ]; then
    show_nodes
    check_dns
  else
    echo "  $MASTER is not running - skipping Kubernetes and DNS checks."
  fi
}

[ -n "$PROJECT" ] || { echo "No GCP project set. Run: gcloud config set project <id>"; exit 1; }
case "${1:-status}" in
  start)     cmd_start ;;
  stop)      cmd_stop ;;
  status)    cmd_status ;;
  ip)        external_ip "$INGRESS_NODE" ;;
  dns)       update_dns; check_dns ;;
  dns-setup) cmd_dns_setup ;;
  *) echo "usage: $0 {start|stop|status|ip|dns|dns-setup}"; exit 1 ;;
esac
