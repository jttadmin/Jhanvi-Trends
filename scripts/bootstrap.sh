#!/usr/bin/env bash
# One-time cluster bootstrap for a kubeadm cluster on GCE. Run from a machine with
# kubectl + helm pointed at the cluster (e.g. the control-plane node).
#
#   ./scripts/bootstrap.sh
#
# Optional env:
#   GHCR_USER / GHCR_TOKEN   create an image pull secret if your GHCR packages are private
#   GIT_USER  / GIT_TOKEN    register the repo in Argo CD if the GitHub repo is private
set -euo pipefail
cd "$(dirname "$0")/.."

LOCAL_PATH_VERSION="v0.0.37"
ARGOCD_CHART_VERSION="10.11.6"
log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

command -v kubectl >/dev/null || { echo "kubectl is required"; exit 1; }
command -v helm >/dev/null || { echo "helm is required (https://helm.sh/docs/intro/install/)"; exit 1; }
if grep -rq -e CHANGE_ME_OWNER -e CHANGE_ME_IP deploy; then
  echo "Run ./scripts/configure.sh <github-owner> <node-ip> and push first."; exit 1
fi

log "Cluster"
kubectl get nodes -o wide

log "Default StorageClass (local-path-provisioner)"
if ! kubectl get storageclass -o jsonpath='{range .items[*]}{.metadata.annotations.storageclass\.kubernetes\.io/is-default-class}{"\n"}{end}' | grep -q true; then
  kubectl apply -f "https://raw.githubusercontent.com/rancher/local-path-provisioner/${LOCAL_PATH_VERSION}/deploy/local-path-storage.yaml"
  kubectl patch storageclass local-path -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
else
  echo "A default StorageClass already exists - keeping it."
fi

log "Namespaces and secrets"
for ns in jhanvi-trends monitoring argocd; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
done
rand() { head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c "${1:-24}"; }

if ! kubectl -n jhanvi-trends get secret jhanvi-trends-secrets >/dev/null 2>&1; then
  kubectl -n jhanvi-trends create secret generic jhanvi-trends-secrets \
    --from-literal=POSTGRES_PASSWORD="$(rand)" \
    --from-literal=MONGODB_PASSWORD="$(rand)" \
    --from-literal=JWT_SECRET="$(rand 48)"
  echo "created jhanvi-trends/jhanvi-trends-secrets"
else
  echo "jhanvi-trends/jhanvi-trends-secrets exists - keeping it (datastores already use these passwords)."
fi

if ! kubectl -n monitoring get secret grafana-admin >/dev/null 2>&1; then
  GRAFANA_PASSWORD="$(rand 16)"
  kubectl -n monitoring create secret generic grafana-admin \
    --from-literal=admin-user=admin --from-literal=admin-password="$GRAFANA_PASSWORD"
  echo "created monitoring/grafana-admin"
fi

if [[ -n "${GHCR_USER:-}" && -n "${GHCR_TOKEN:-}" ]]; then
  kubectl -n jhanvi-trends create secret docker-registry ghcr-pull \
    --docker-server=ghcr.io --docker-username="$GHCR_USER" --docker-password="$GHCR_TOKEN" \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "created jhanvi-trends/ghcr-pull - set global.imagePullSecrets: [{name: ghcr-pull}] in the chart values"
fi

log "Argo CD"
helm repo add argo https://argoproj.github.io/argo-helm >/dev/null
helm repo update argo >/dev/null
helm upgrade --install argocd argo/argo-cd -n argocd --version "$ARGOCD_CHART_VERSION" \
  -f deploy/argocd/argocd-values.yaml --wait --timeout 10m

if [[ -n "${GIT_USER:-}" && -n "${GIT_TOKEN:-}" ]]; then
  repo_url=$(grep -m1 'repoURL: https://github.com' deploy/argocd/root.yaml | awk '{print $2}')
  kubectl -n argocd create secret generic repo-jhanvi-trends \
    --from-literal=type=git --from-literal=url="$repo_url" \
    --from-literal=username="$GIT_USER" --from-literal=password="$GIT_TOKEN" \
    --dry-run=client -o yaml | kubectl label --local -f - argocd.argoproj.io/secret-type=repository -o yaml | kubectl apply -f -
fi

log "App of apps"
kubectl apply -f deploy/argocd/project.yaml -f deploy/argocd/root.yaml

host=$(grep -m1 'host: shop\.' deploy/charts/jhanvi-trends/values.yaml | awk '{print $2}')
ip=${host#shop.}; ip=${ip%.nip.io}
cat <<EOF

Bootstrap complete. Argo CD now installs the platform and the app (10-15 min on first run):
  kubectl -n argocd get applications -w

  Shop:    http://shop.${ip}.nip.io:30080
  Grafana: http://grafana.${ip}.nip.io:30080   user: admin  password: kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
  Argo CD: http://argocd.${ip}.nip.io:30080    user: admin  password: kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d

If those don't load, open the NodePorts in GCE:
  gcloud compute firewall-rules create jhanvi-trends-ingress --allow tcp:30080,tcp:30443 --target-tags <your-node-network-tag> --source-ranges <your-ip>/32
EOF
