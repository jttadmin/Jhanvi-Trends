#!/usr/bin/env bash
# Fill in the repo placeholders, then commit and push.
#   ./scripts/configure.sh <github-owner> <node-external-ip>
# Example: ./scripts/configure.sh srinivasbhumaji 34.93.10.20
set -euo pipefail

owner="${1:?usage: $0 <github-owner> <node-external-ip>}"
ip="${2:?usage: $0 <github-owner> <node-external-ip>}"
owner_lc="$(echo "$owner" | tr '[:upper:]' '[:lower:]')"   # GHCR image paths must be lowercase

cd "$(dirname "$0")/.."
files=$(grep -rl -e CHANGE_ME_OWNER -e CHANGE_ME_IP deploy .github || true)
[ -z "$files" ] && { echo "Nothing to replace - already configured."; exit 0; }

for f in $files; do
  sed -i.bak \
    -e "s#ghcr.io/jttadmin#ghcr.io/${owner_lc}#g" \
    -e "s#github.com/jttadmin/#github.com/${owner}/#g" \
    -e "s#CHANGE_ME_IP#${ip}#g" "$f"
  rm -f "$f.bak"
  echo "updated $f"
done

cat <<EOF

Done. URLs once deployed (NodePort 30080 on any node):
  Shop:    http://shop.${ip}.nip.io:30080
  Grafana: http://grafana.${ip}.nip.io:30080
  Argo CD: http://argocd.${ip}.nip.io:30080

Next: git add -A && git commit -m "Configure for my cluster" && git push
EOF
