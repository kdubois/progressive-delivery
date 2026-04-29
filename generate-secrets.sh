#!/bin/bash
set -euo pipefail

# Generate secrets.env from environment variables
# This allows using environment variables already stored on the machine
# Usage:
#   export OPENAI_API_KEY="sk-..."
#   export GITHUB_TOKEN="ghp_..."
#   ./generate-secrets.sh
#   kubectl apply -k bootstrap/overlays/default/

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_FILE="${SCRIPT_DIR}/secrets.env"

# Check required environment variables
REQUIRED_VARS=("OPENAI_API_KEY" "GITHUB_TOKEN")
MISSING_VARS=()

for var in "${REQUIRED_VARS[@]}"; do
  if [[ -z "${!var:-}" ]]; then
    MISSING_VARS+=("$var")
  fi
done

if [[ ${#MISSING_VARS[@]} -gt 0 ]]; then
  echo "Error: Missing required environment variables:"
  printf '  %s\n' "${MISSING_VARS[@]}"
  echo ""
  echo "Please set them before running this script:"
  echo "  export OPENAI_API_KEY='sk-...'"
  echo "  export GITHUB_TOKEN='ghp_...'"
  echo ""
  echo "Or copy secrets.env.example to secrets.env and edit manually:"
  echo "  cp secrets.env.example secrets.env"
  echo "  vim secrets.env"
  exit 1
fi

# Generate secrets.env file
cat > "${OUTPUT_FILE}" <<EOF
# Auto-generated from environment variables
# Generated at: $(date -u +"%Y-%m-%d %H:%M:%S UTC")

# OpenAI API Key (for qwen3-14b - used by DiagnosticAgent and AnalysisAgent)
OPENAI_API_KEY=${OPENAI_API_KEY}

# Remediation API Key (used by RemediationAgent only)
REM_API_KEY=${REM_API_KEY:-${OPENAI_API_KEY}}

# Gemini API Key (if using Gemini profile)
GOOGLE_API_KEY=${GOOGLE_API_KEY:-}

# GitHub Token (for creating PRs and issues)
GITHUB_TOKEN=${GITHUB_TOKEN}

# Google Cloud Project (optional)
GOOGLE_CLOUD_PROJECT=${GOOGLE_CLOUD_PROJECT:-}
EOF

echo "✓ Generated secrets.env from environment variables"
echo ""
echo "Next steps:"
echo "  kubectl apply -k bootstrap/overlays/default/"
echo ""
echo "To verify the secret will be created correctly:"
echo "  kubectl kustomize system/kubernetes-agent/"
