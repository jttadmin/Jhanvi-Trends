#!/usr/bin/env bash
# Sets up SSH and kubectl access to the kubeadm cluster on GCE from your own machine.
# Everything goes through Google's IAP tunnel (HTTPS, port 443): it works from networks that
# block outgoing SSH, and it doesn't care that the VMs get new IPs after every stop/start.
#
#   ./scripts/access-setup.sh            safe to re-run at any time
#
# Afterwards (new terminal, or: source ~/.bashrc):
#   master                    SSH into the control-plane node
#   master --command "..."    run one command there
#   worker 1                  SSH into k8s-worker1 (2, 3 ...)
#   k get pods -A             kubectl from your machine (opens the API tunnel on first use)
set -euo pipefail

PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
ZONE="${ZONE:-asia-south1-a}"
MASTER="${MASTER:-k8s-master}"
WORKER_PREFIX="${WORKER_PREFIX:-k8s-worker}"
KUBECONFIG_FILE="${KUBECONFIG_FILE:-$HOME/.kube/jhanvi-trends.conf}"
API_LOCAL_PORT="${API_LOCAL_PORT:-16443}"
IAP_RANGE="35.235.240.0/20"   # Google's IAP TCP forwarding source range
FW_RULE="allow-iap-ssh-k8s-api"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*"; exit 1; }
g()    { gcloud --project "$PROJECT" "$@"; }

# ---------------------------------------------------------------------------
say "1/6  gcloud"
command -v gcloud >/dev/null || die "gcloud CLI not found: https://cloud.google.com/sdk/docs/install"
account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -1)"
[ -n "$account" ] || die "not logged in. Run: gcloud auth login"
[ -n "$PROJECT" ] || die "no project. Run: PROJECT=<project-id> $0"
gcloud config set project "$PROJECT" >/dev/null 2>&1
gcloud config set compute/zone "$ZONE" >/dev/null 2>&1
ok "account $account, project $PROJECT, zone $ZONE"

status="$(g compute instances describe "$MASTER" --zone "$ZONE" --format='value(status)' 2>/dev/null)" \
  || die "$MASTER not found in $PROJECT/$ZONE"
[ "$status" = "RUNNING" ] || die "$MASTER is $status - start the cluster first (scripts/gce-cluster.sh start)"
ok "$MASTER is RUNNING"

# ---------------------------------------------------------------------------
say "2/6  Firewall: let IAP reach SSH (22) and the Kubernetes API (6443)"
network="$(g compute instances describe "$MASTER" --zone "$ZONE" \
  --format='value(networkInterfaces[0].network.basename())')"
if g compute firewall-rules describe "$FW_RULE" >/dev/null 2>&1; then
  ok "rule $FW_RULE already exists"
else
  g compute firewall-rules create "$FW_RULE" --network "$network" --direction INGRESS \
    --action allow --rules tcp:22,tcp:6443 --source-ranges "$IAP_RANGE" \
    --description "IAP TCP forwarding: SSH and kube-apiserver (scripts/access-setup.sh)" >/dev/null
  ok "created $FW_RULE on network $network (source $IAP_RANGE only)"
fi

# ---------------------------------------------------------------------------
say "3/6  SSH to $MASTER through IAP"
# shellcheck disable=SC2016  # expands on the master, not here
ssh_out="$(g compute ssh "$MASTER" --zone "$ZONE" --tunnel-through-iap --quiet \
  --command 'echo "$(hostname) up $(uptime -p)"' 2>&1)" || {
  echo "$ssh_out" | tail -5
  if echo "$ssh_out" | grep -qiE 'permission|403|not authorized'; then
    warn "your account needs the IAP tunnel role. Grant it, then re-run this script:"
    echo "    gcloud projects add-iam-policy-binding $PROJECT --member=user:$account --role=roles/iap.tunnelResourceAccessor"
  fi
  die "SSH through IAP failed"
}
ok "$ssh_out"

# ---------------------------------------------------------------------------
say "4/6  Shell commands in ~/.bashrc (master, worker, k)"
touch "$HOME/.bashrc"
# Drop earlier definitions of "master" (an alias or function) that would clash with the new one.
for f in "$HOME/.bashrc" "$HOME/.bash_aliases"; do
  [ -f "$f" ] || continue
  sed -i '/^[[:space:]]*alias master=/d' "$f"
  sed -i '/^\(function master\|master()\)/,/^}/d' "$f"
done
# Replace our own block if it is already there.
sed -i '/^# >>> jhanvi-trends access >>>/,/^# <<< jhanvi-trends access <<</d' "$HOME/.bashrc"
cat >> "$HOME/.bashrc" <<EOF
# >>> jhanvi-trends access >>>  (managed by scripts/access-setup.sh - re-run it to change)
JT_PROJECT="$PROJECT"; JT_ZONE="$ZONE"; JT_MASTER="$MASTER"; JT_WORKER_PREFIX="$WORKER_PREFIX"
JT_KUBECONFIG="$KUBECONFIG_FILE"; JT_API_PORT="$API_LOCAL_PORT"
function master {
  gcloud compute ssh "\$JT_MASTER" --project "\$JT_PROJECT" --zone "\$JT_ZONE" --tunnel-through-iap "\$@"
}
function worker {
  local n="\${1:?usage: worker <number>   e.g. worker 1}"; shift
  gcloud compute ssh "\${JT_WORKER_PREFIX}\${n}" --project "\$JT_PROJECT" --zone "\$JT_ZONE" --tunnel-through-iap "\$@"
}
# kubectl from this machine: opens an IAP tunnel to the API server (localhost:\$JT_API_PORT) when needed.
function k {
  if ! (exec 3<>/dev/tcp/127.0.0.1/"\$JT_API_PORT") 2>/dev/null; then
    gcloud compute start-iap-tunnel "\$JT_MASTER" 6443 --local-host-port=localhost:"\$JT_API_PORT" \\
      --project "\$JT_PROJECT" --zone "\$JT_ZONE" >/tmp/jt-iap-tunnel.log 2>&1 &
    disown
    local i; for i in \$(seq 1 20); do (exec 3<>/dev/tcp/127.0.0.1/"\$JT_API_PORT") 2>/dev/null && break; sleep 0.5; done
  fi
  KUBECONFIG="\$JT_KUBECONFIG" kubectl "\$@"
}
# <<< jhanvi-trends access <<<
EOF
ok "added master, worker and k (old 'master' alias/function removed)"

# ---------------------------------------------------------------------------
say "5/6  kubectl config ($KUBECONFIG_FILE)"
if ! command -v kubectl >/dev/null; then
  warn "kubectl is not installed here - skipped. Install it, then re-run this script to use 'k':"
  echo "    https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/"
else
  mkdir -p "$(dirname "$KUBECONFIG_FILE")"
  tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
  g compute ssh "$MASTER" --zone "$ZONE" --tunnel-through-iap --quiet \
    --command 'sudo cat /etc/kubernetes/admin.conf' > "$tmp" 2>/dev/null \
    || die "could not read /etc/kubernetes/admin.conf on $MASTER"
  grep -q 'certificate-authority-data' "$tmp" || die "admin.conf from $MASTER looks wrong"
  # Talk to the API through the local tunnel. The API certificate is issued for "kubernetes"
  # (not 127.0.0.1), so tell kubectl which name to verify.
  sed -i -E "s#^([[:space:]]*)server: https://[^[:space:]]+#\1server: https://127.0.0.1:${API_LOCAL_PORT}\n\1tls-server-name: kubernetes#" "$tmp"
  install -m 600 "$tmp" "$KUBECONFIG_FILE"
  ok "saved (cluster-admin credentials, readable only by you)"
fi

# ---------------------------------------------------------------------------
say "6/6  Cleanup"
if g compute instances describe "$MASTER" --zone "$ZONE" --format='value(metadata.items[].key)' | tr ';' '\n' | grep -qx startup-script; then
  g compute instances remove-metadata "$MASTER" --zone "$ZONE" --keys startup-script >/dev/null
  ok "removed the leftover diagnostic startup-script from $MASTER"
else
  ok "nothing to clean up"
fi

cat <<EOF

Done. Open a new terminal (or run: source ~/.bashrc), then:
  master                      SSH into $MASTER
  worker 1                    SSH into ${WORKER_PREFIX}1
  k get nodes                 kubectl from this machine
EOF
