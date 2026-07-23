#!/usr/bin/env bash
# Bootstrap the progressive delivery stack on an OpenShift cluster.
#
# Usage:
#   ./bootstrap.sh                     # installs GitOps operator + full stack
#   ./bootstrap.sh existing-argocd     # skips operator install, reuses existing ArgoCD
#
# The script handles the chicken-and-egg problem: AppProject, ApplicationSet,
# and ArgoCD CRs can only be applied after their CRDs exist, which requires the
# operators to finish installing first.

set -euo pipefail

OVERLAY="${1:-default}"
BOOTSTRAP_DIR="$(cd "$(dirname "$0")" && pwd)"
OVERLAY_PATH="${BOOTSTRAP_DIR}/overlays/${OVERLAY}"

if [[ ! -d "${OVERLAY_PATH}" ]]; then
  echo "ERROR: overlay '${OVERLAY}' not found at ${OVERLAY_PATH}" >&2
  exit 1
fi

echo "==> Applying overlay: ${OVERLAY}"

if [[ "${OVERLAY}" == "default" ]]; then
  # Phase 1: install the operators only (Subscriptions).
  # Retry until the base resources (ClusterRoleBinding + Subscriptions) are accepted.
  echo "--> Phase 1: installing operators..."
  until oc apply -k "${BOOTSTRAP_DIR}/base/"; do
    echo "    waiting for API server to accept operator resources..."
    sleep 15
  done

  # Phase 2: wait for the GitOps operator to install its CRDs.
  # oc wait fails immediately when the resource doesn't exist yet, so poll
  # until each CRD appears before handing off to oc wait.
  echo "--> Phase 2: waiting for Argo CD CRDs..."
  for crd in argocds.argoproj.io appprojects.argoproj.io applicationsets.argoproj.io; do
    echo "    waiting for CRD ${crd}..."
    until oc get crd "${crd}" &>/dev/null; do sleep 10; done
    oc wait crd/"${crd}" --for=condition=Established --timeout=300s
  done

  # Phase 3: apply the full overlay (ArgoCD instance, AppProjects, ApplicationSets).
  echo "--> Phase 3: applying Argo CD resources..."
fi

until oc apply -k "${OVERLAY_PATH}/"; do
  echo "    waiting for resources to be accepted..."
  sleep 15
done

echo "==> Bootstrap complete."
