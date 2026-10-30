#!/usr/bin/env bash
# Preflight checks for deploying Jhanvi Trends on a kubeadm cluster running on GCE VMs.
# Run on the control-plane node (or anywhere kubectl points at the cluster):
#   ./scripts/preflight.sh
# Paste the output back if anything is FAIL or WARN.
set -uo pipefail

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
warn() { printf '  \033[33mWARN\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILED=1; }
FAILED=0
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

section "Tools"
for t in kubectl helm git; do
  if command -v "$t" >/dev/null; then pass "$t: $($t version --client 2>/dev/null | head -1 || $t --version | head -1)"; else fail "$t not installed"; fi
done
kubectl version 2>/dev/null | grep -i server || fail "cannot reach the API server (check ~/.kube/config)"

section "Nodes"
kubectl get nodes -o wide
notready=$(kubectl get nodes --no-headers | awk '$2!="Ready"' | wc -l)
[ "$notready" -eq 0 ] && pass "all nodes Ready" || fail "$notready node(s) not Ready"
workers=$(kubectl get nodes --no-headers -l '!node-role.kubernetes.io/control-plane' | wc -l)
[ "$workers" -ge 2 ] && pass "$workers worker node(s)" || warn "$workers worker node(s); 2+ recommended (or untaint the control plane)"

section "Capacity (allocatable on schedulable nodes)"
kubectl get nodes -l '!node-role.kubernetes.io/control-plane' -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.allocatable.cpu}{"\t"}{.status.allocatable.memory}{"\n"}{end}' |
awk -F'\t' '
  function cpu(v){ return v ~ /m$/ ? substr(v,1,length(v)-1)/1000 : v+0 }
  function mem(v){ if (v ~ /Ki$/) return substr(v,1,length(v)-2)/1048576; if (v ~ /Mi$/) return substr(v,1,length(v)-2)/1024; if (v ~ /Gi$/) return substr(v,1,length(v)-2)+0; return v/1073741824 }
  { c+=cpu($2); m+=mem($3); printf "  %-28s %5.1f vCPU %6.1f GiB\n", $1, cpu($2), mem($3) }
  END { printf "  total                        %5.1f vCPU %6.1f GiB\n", c, m;
        if (c>=6 && m>=12) print "  \033[32mPASS\033[0m enough for app + observability (~6 vCPU / 12 GiB)";
        else print "  \033[33mWARN\033[0m below ~6 vCPU / 12 GiB: pods may stay Pending (use e2-standard-4 workers or set loadgen.enabled=false and reduce replicas)" }'

section "Pod network (CNI)"
cni=$(kubectl get pods -A --no-headers 2>/dev/null | grep -oE 'calico|flannel|cilium|weave' | sort -u | tr '\n' ' ')
[ -n "$cni" ] && pass "CNI detected: $cni" || fail "no CNI pods found (install Calico/Flannel/Cilium)"
kubectl get pods -A --no-headers | awk '$4!="Running" && $4!="Completed"{print "  not running:", $1"/"$2, $4}'

section "Cluster DNS"
if kubectl run sv-dns-check --rm -i --restart=Never --image=busybox:1.36 --command -- nslookup kubernetes.default >/tmp/sv-dns.txt 2>&1; then
  pass "CoreDNS resolves service names"
else
  fail "DNS lookup from a pod failed:"; sed 's/^/    /' /tmp/sv-dns.txt | tail -5
fi

section "Cross-node pod traffic (needs GCE firewall to allow CNI traffic between nodes)"
nodes=($(kubectl get nodes -l '!node-role.kubernetes.io/control-plane' -o jsonpath='{.items[*].metadata.name}'))
if [ "${#nodes[@]}" -ge 2 ]; then
  kubectl run sv-net-a --image=busybox:1.36 --restart=Never --overrides="{\"spec\":{\"nodeName\":\"${nodes[0]}\"}}" -- sh -c 'httpd -f -p 8080 -h /tmp' >/dev/null
  kubectl wait --for=condition=Ready pod/sv-net-a --timeout=60s >/dev/null 2>&1
  ip=$(kubectl get pod sv-net-a -o jsonpath='{.status.podIP}')
  if kubectl run sv-net-b --rm -i --restart=Never --image=busybox:1.36 --overrides="{\"spec\":{\"nodeName\":\"${nodes[1]}\"}}" -- \
       sh -c "wget -q -T 5 -O /dev/null http://$ip:8080/ 2>&1; echo rc=\$?" 2>/dev/null | grep -qE 'rc=0|404'; then
    pass "pod on ${nodes[1]} reached pod on ${nodes[0]}"
  else
    fail "pods on different nodes cannot talk. Allow internal traffic between nodes in the VPC firewall:
         Calico (IPIP):  --allow tcp,udp,icmp,ipip   Flannel/Calico VXLAN: --allow tcp,udp,icmp
         e.g. gcloud compute firewall-rules create k8s-internal --network <vpc> --allow tcp,udp,icmp,ipip --source-ranges <node-subnet>,<pod-cidr>"
  fi
  kubectl delete pod sv-net-a --now >/dev/null 2>&1
else
  warn "fewer than 2 workers, skipped"
fi

section "Outbound internet from pods (image pulls, nip.io not needed inside)"
if kubectl run sv-egress --rm -i --restart=Never --image=busybox:1.36 -- wget -q -T 8 -O /dev/null https://ghcr.io >/dev/null 2>&1; then
  pass "pods can reach ghcr.io"
else
  warn "pods could not reach ghcr.io; nodes need an external IP or Cloud NAT to pull images"
fi

section "Storage"
if kubectl get storageclass -o jsonpath='{range .items[*]}{.metadata.annotations.storageclass\.kubernetes\.io/is-default-class}{"\n"}{end}' | grep -q true; then
  pass "default StorageClass: $(kubectl get sc | awk '/default/{print $1}')"
else
  warn "no default StorageClass yet (bootstrap.sh installs local-path-provisioner)"
fi

section "Ports"
used=$(kubectl get svc -A -o jsonpath='{range .items[*]}{range .spec.ports[*]}{.nodePort}{"\n"}{end}{end}' | grep -E '^(30080|30443)$' || true)
[ -z "$used" ] && pass "NodePorts 30080/30443 are free" || warn "NodePort(s) already used: $used (change them in deploy/platform/traefik.yaml)"

section "Repo configuration"
if grep -rq CHANGE_ME deploy 2>/dev/null; then warn "placeholders remain - run ./scripts/configure.sh <github-owner> <node-external-ip>"; else pass "placeholders filled in"; fi
grep -m1 'host: shop\.' deploy/charts/jhanvi-trends/values.yaml 2>/dev/null | sed 's/^ */  shop host: /'

echo
[ "$FAILED" -eq 0 ] && echo "Preflight finished: no blocking problems." || echo "Preflight finished with FAILs - fix those first."
exit $FAILED
