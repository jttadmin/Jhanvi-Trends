#!/usr/bin/env bash
# Fill in the repo placeholders, then commit and push.
#   ./scripts/configure.sh <github-owner> <dockerhub-namespace> <node-external-ip>
# Example: ./scripts/configure.sh jttadmin jhanvitrends 34.93.10.20
set -euo pipefail

usage="usage: $0 <github-owner> <dockerhub-namespace> <node-external-ip>"
owner="${1:?$usage}"
hub="${2:?$usage}"
ip="${3:?$usage}"
hub_lc="$(echo "$hub" | tr '[:upper:]' '[:lower:]')"   # Docker image names must be lowercase

cd "$(dirname "$0")/.."
files=$(grep -rl -e CHANGE_ME_OWNER -e CHANGE_ME_DOCKERHUB -e CHANGE_ME_IP deploy .github || true)
[ -z "$files" ] && { echo "Nothing to replace - already configured."; exit 0; }

for f in $files; do
  sed -i.bak \
    -e "s#github.com/CHANGE_ME_OWNER/#github.com/${owner}/#g" \
    -e "s#docker.io/CHANGE_ME_DOCKERHUB#docker.io/${hub_lc}#g" \
    -e "s#CHANGE_ME_IP#${ip}#g" "$f"
  rm -f "$f.bak"
  echo "updated $f"
done

cat <<EOF

Done. Images: docker.io/${hub_lc}/jhanvi-trends-<service>
URLs once deployed (the external IP of the node running Traefik):
  Shop:    http://shop.${ip}.nip.io
  Grafana: http://grafana.${ip}.nip.io
  Argo CD: http://argocd.${ip}.nip.io

Next: git add -A && git commit -m "Configure for my cluster" && git push
EOF
