# Running Jhanvi Trends on GKE

The GKE cluster, Traefik, cert-manager, Argo CD and the observability stack (Prometheus, Grafana, Loki, Tempo and the OTel gateway) are built by the infra repo, [`jttadmin/jhanvi-trends-infra`](https://github.com/jttadmin/jhanvi-trends-infra). That repo's Argo CD syncs `argocd/apps-gke/` from this repo, which deploys the store chart with `values.yaml` + `values-gke.yaml`.

| | kubeadm (`deploy/argocd/apps`) | GKE (`argocd/apps-gke`) |
|---|---|---|
| Chart values | `values.yaml` | `values.yaml` + `values-gke.yaml` |
| Traces | node-local collector `<node IP>:4317` | `otel-gateway.observability:4317` |
| Storage | local-path | `standard-rwo` (GCE PD) |
| Placement | anywhere | services → `workload=apps`, data stores → `workload=stateful` |
| TLS issuer | `letsencrypt-production` | `letsencrypt-prod` |
| LGTM stack | deployed from this repo | owned by the infra repo |

CI keeps updating image tags in `values.yaml`, so both setups get every new build.

## One-time: app secret

Run this before the first sync. Without it, pods wait in `CreateContainerConfigError` and then start on their own once the secret exists.

```bash
kubectl --context gke-dev create namespace jhanvi-trends --dry-run=client -o yaml | kubectl --context gke-dev apply -f -
rand() { openssl rand -base64 "${1:-24}" | tr -d '/+=' | cut -c1-"${1:-24}"; }
kubectl --context gke-dev -n jhanvi-trends create secret generic jhanvi-trends-secrets \
  --from-literal=POSTGRES_PASSWORD="$(rand)" \
  --from-literal=MONGODB_PASSWORD="$(rand)" \
  --from-literal=JWT_SECRET="$(rand 48)"
```

Keep the secret once the data stores have data. Postgres and MongoDB only read the password on first start.

## Check it

```bash
kubectl --context gke-dev -n argocd get applications
kubectl --context gke-dev -n jhanvi-trends get pods -o wide
kubectl --context gke-dev -n jhanvi-trends get pvc
kubectl --context gke-dev -n jhanvi-trends get certificate
```

The store is at https://shop.bhumaji.com. Its dashboards are in Grafana under the **Jhanvi Trends** folder.
