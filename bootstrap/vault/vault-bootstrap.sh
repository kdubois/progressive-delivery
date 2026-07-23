#!/bin/bash
# vault-bootstrap.sh — one-time Vault setup for the kubernetes-agent
#
# Configures Vault Kubernetes auth and writes credentials to the KV path
# that the agent reads at startup when QUARKUS_PROFILE includes "vault".
#
# Run this ONCE before deploying, after the stack is up:
#   export VAULT_ADDR="https://vault.example.com"
#   export VAULT_ADMIN_TOKEN="<root-or-admin-token>"
#   export OPENAI_API_KEY="sk-..."
#   export GITHUB_TOKEN="ghp_..."
#   ./bootstrap/vault/vault-bootstrap.sh
#
# Optional variables:
#   REM_API_KEY        (defaults to OPENAI_API_KEY)
#   GOOGLE_API_KEY
#   GOOGLE_CLOUD_PROJECT
#   NAMESPACE          (defaults to openshift-gitops)

set -euo pipefail

NAMESPACE="${NAMESPACE:-openshift-gitops}"

# ── Validate required inputs ────────────────────────────────────────────────

REQUIRED_VARS=(VAULT_ADDR VAULT_ADMIN_TOKEN OPENAI_API_KEY GITHUB_TOKEN)
MISSING=()
for var in "${REQUIRED_VARS[@]}"; do
  [[ -z "${!var:-}" ]] && MISSING+=("$var")
done

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "Error: missing required environment variables:"
  printf '  %s\n' "${MISSING[@]}"
  echo ""
  echo "Export them before running this script:"
  echo "  export VAULT_ADDR=https://vault.example.com"
  echo "  export VAULT_ADMIN_TOKEN=<root-or-admin-token>"
  echo "  export OPENAI_API_KEY=sk-..."
  echo "  export GITHUB_TOKEN=ghp_..."
  exit 1
fi

# ── Write credentials to Vault KV ───────────────────────────────────────────

echo "✓ Writing secrets to Vault KV path secret/argo-rollouts/kubernetes-agent"
vault kv put secret/argo-rollouts/kubernetes-agent \
  openai_api_key="${OPENAI_API_KEY}" \
  rem_api_key="${REM_API_KEY:-${OPENAI_API_KEY}}" \
  google_api_key="${GOOGLE_API_KEY:-}" \
  github_token="${GITHUB_TOKEN}" \
  google_cloud_project="${GOOGLE_CLOUD_PROJECT:-}"

# ── Create the vault-bootstrap-config ConfigMap ──────────────────────────────

echo "✓ Creating vault-bootstrap-config ConfigMap"
kubectl create configmap vault-bootstrap-config \
  --from-literal=VAULT_ADDR="${VAULT_ADDR}" \
  -n "${NAMESPACE}" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Create the vault-admin-token Secret (used only by the bootstrap Job) ────

echo "✓ Creating vault-admin-token Secret"
kubectl create secret generic vault-admin-token \
  --from-literal=token="${VAULT_ADMIN_TOKEN}" \
  -n "${NAMESPACE}" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Delete any previous Job run so we can re-apply cleanly ──────────────────

kubectl delete job vault-bootstrap -n "${NAMESPACE}" --ignore-not-found

# ── Apply and run the bootstrap Job ──────────────────────────────────────────

echo "✓ Applying vault-bootstrap Job"
kubectl apply -k "$(dirname "$0")"

echo "✓ Waiting for vault-bootstrap Job to complete (timeout 3m)"
kubectl wait job/vault-bootstrap \
  --for=condition=complete \
  --timeout=180s \
  -n "${NAMESPACE}"

echo ""
kubectl logs job/vault-bootstrap -n "${NAMESPACE}"

# ── Clean up the admin token — it is no longer needed ───────────────────────

echo ""
echo "✓ Deleting vault-admin-token Secret (no longer needed)"
kubectl delete secret vault-admin-token -n "${NAMESPACE}"

# ── Update VAULT_ADDR in the agent ConfigMap ─────────────────────────────────

echo "✓ Patching kubernetes-agent-config with VAULT_ADDR"
kubectl patch configmap kubernetes-agent-config \
  --type merge \
  -p "{\"data\":{\"VAULT_ADDR\":\"${VAULT_ADDR}\"}}" \
  -n "${NAMESPACE}"

echo ""
echo "═══════════════════════════════════════════════════════"
echo "Vault bootstrap complete."
echo ""
echo "To activate Vault as the credential source, update"
echo "system/kubernetes-agent/configmap.yaml:"
echo ""
echo "  QUARKUS_PROFILE: \"prod,openai,vault\"  # or prod,gemini,vault"
echo ""
echo "Then commit, push, and restart the agent:"
echo "  kubectl rollout restart deployment/kubernetes-agent -n ${NAMESPACE}"
echo "═══════════════════════════════════════════════════════"
