#!/bin/bash
# vault-bootstrap.sh — one-time Vault setup for the kubernetes-agent
#
# Writes credentials to Vault KV and configures Kubernetes auth so the
# Vault Secrets Operator (VSO) can sync secrets to a K8s Secret.
#
# Prerequisites:
#   - Vault server running in openshift-gitops (deployed by ArgoCD Helm app)
#   - vault CLI installed locally
#   - oc / kubectl access to the cluster
#
# Usage:
#   export OPENAI_API_KEY="sk-..."
#   export GITHUB_TOKEN="ghp_..."
#   # Optional: REM_API_KEY, GOOGLE_API_KEY, GOOGLE_CLOUD_PROJECT
#   ./bootstrap/vault/vault-bootstrap.sh
#
# The script port-forwards to Vault automatically. No admin token is
# needed — dev mode uses a well-known root token ("root").

set -euo pipefail

NAMESPACE="${NAMESPACE:-openshift-gitops}"
VAULT_PORT="${VAULT_PORT:-8200}"

# ── Validate required inputs ────────────────────────────────────────────────

REQUIRED_VARS=(OPENAI_API_KEY GITHUB_TOKEN)
MISSING=()
for var in "${REQUIRED_VARS[@]}"; do
  [[ -z "${!var:-}" ]] && MISSING+=("$var")
done

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "Error: missing required environment variables:"
  printf '  %s\n' "${MISSING[@]}"
  echo ""
  echo "Usage:"
  echo "  export OPENAI_API_KEY=sk-..."
  echo "  export GITHUB_TOKEN=ghp_..."
  echo "  ./bootstrap/vault/vault-bootstrap.sh"
  exit 1
fi

# ── Wait for Vault to be ready ──────────────────────────────────────────────

echo "Waiting for Vault pod to be ready..."
oc wait pod -l app.kubernetes.io/name=vault \
  -n "${NAMESPACE}" \
  --for=condition=ready \
  --timeout=120s

# ── Port-forward to Vault ───────────────────────────────────────────────────

echo "Setting up port-forward to Vault..."
oc port-forward svc/vault "${VAULT_PORT}:8200" -n "${NAMESPACE}" &
PF_PID=$!
trap "kill ${PF_PID} 2>/dev/null || true" EXIT
sleep 3

export VAULT_ADDR="http://127.0.0.1:${VAULT_PORT}"
export VAULT_TOKEN="root"

# Verify connectivity
vault status > /dev/null 2>&1 || { echo "Error: cannot reach Vault at ${VAULT_ADDR}"; exit 1; }
echo "Connected to Vault at ${VAULT_ADDR}"

# ── Enable KV v2 secrets engine ─────────────────────────────────────────────

echo "Enabling KV v2 secrets engine at 'secret/'..."
vault secrets enable -path=secret kv-v2 2>/dev/null || echo "  (already enabled)"

# ── Write credentials to Vault KV ───────────────────────────────────────────

echo "Writing secrets to secret/argo-rollouts/kubernetes-agent..."
vault kv put secret/argo-rollouts/kubernetes-agent \
  openai_api_key="${OPENAI_API_KEY}" \
  rem_api_key="${REM_API_KEY:-${OPENAI_API_KEY}}" \
  google_api_key="${GOOGLE_API_KEY:-}" \
  github_token="${GITHUB_TOKEN}" \
  google_cloud_project="${GOOGLE_CLOUD_PROJECT:-}"

# ── Configure Kubernetes auth ───────────────────────────────────────────────

echo "Enabling Kubernetes auth method..."
vault auth enable kubernetes 2>/dev/null || echo "  (already enabled)"

echo "Configuring Kubernetes auth..."
vault write auth/kubernetes/config \
  kubernetes_host="https://kubernetes.default.svc:443"

echo "Writing kubernetes-agent policy..."
vault policy write kubernetes-agent - <<'POLICY'
path "secret/data/argo-rollouts/kubernetes-agent" {
  capabilities = ["read"]
}
POLICY

echo "Writing kubernetes-agent auth role..."
vault write auth/kubernetes/role/kubernetes-agent \
  bound_service_account_names=kubernetes-agent \
  bound_service_account_namespaces="${NAMESPACE}" \
  policies=kubernetes-agent \
  ttl=1h

# ── Done ────────────────────────────────────────────────────────────────────

echo ""
echo "============================================================"
echo "Vault bootstrap complete."
echo ""
echo "Vault KV:   secret/argo-rollouts/kubernetes-agent"
echo "Auth role:  kubernetes-agent (SA: kubernetes-agent, NS: ${NAMESPACE})"
echo ""
echo "The Vault Secrets Operator will sync the KV data to K8s"
echo "Secret 'kubernetes-agent' in namespace '${NAMESPACE}'."
echo ""
echo "If VSO is already running, the secret should appear shortly."
echo "Check with: oc get secret kubernetes-agent -n ${NAMESPACE}"
echo "============================================================"
