# Agent Instructions — progressive-delivery

GitOps manifests for the full AI-powered progressive delivery stack on OpenShift. Managed by Argo CD — never patch cluster resources directly. All changes go through Git; Argo CD syncs them to the cluster.

Do not create summary or migration documents. Update manifests and documentation in place.

## Stack

OpenShift 4.10+, Argo CD (OpenShift GitOps operator), Argo Rollouts + `rollouts-plugin-metric-ai`, Kustomize, Bash scripts.

## Project Layout

```
bootstrap/
  base/                              # Shared base (GitOps operator subscription)
  overlays/
    default/                         # Full deployment (installs Argo CD + all components)
    existing-argocd/                 # For clusters that already have Argo CD

components/
  applicationsets/
    system-appset.yaml               # Deploys: Argo Rollouts controller, kubernetes-agent
    workloads-appset.yaml            # Deploys: quarkus-rollouts-demo, canary-app
  appprojects/                       # AppProject RBAC and sync policies

system/
  progressive-delivery-controller/   # Argo Rollouts + AI plugin (RolloutManager + ConfigMap)
  kubernetes-agent/
    deployment.yaml                  # Quarkus agent (512Mi–2Gi)
    configmap.yaml                   # AI model endpoint and provider config
    secret.yaml.template             # Credentials template — NOT managed by GitOps
    rbac.yaml                        # ClusterRole: read pods/logs/events/rollouts
    service.yaml                     # ClusterIP :8080
  vault-server/
    vault-helm-app.yaml              # ArgoCD Application — deploys Vault via Helm chart (dev mode)
  vault-secrets-operator/
    vault-secrets-operator.yaml      # OLM Subscription — Red Hat-certified VSO
  vault-config/
    vault-connection.yaml            # VaultConnection — Vault server address
    vault-auth.yaml                  # VaultAuth — Kubernetes auth method config
    vault-static-secret.yaml         # VaultStaticSecret — syncs KV to K8s Secret

workloads/
  quarkus-rollouts-demo/
    base/                            # Rollout, AnalysisTemplate, services, Gateway route
    overlays/
      scenario-1-stable/             # Happy path image
      scenario-2-null-pointer/       # NPE bug image
      scenario-3-memory-leak/        # Memory leak image
  canary-app/                        # Alternate demo (argoproj/rollouts-demo)

validate-deployment.sh               # Deployment health checks
demo_scenarios.java                  # JBang orchestrator for the 3 demo scenarios
```

## Namespace Layout

| Namespace | Contents |
|---|---|
| `openshift-gitops` | Argo CD, Argo Rollouts controller + plugin, kubernetes-agent |
| `quarkus-demo` | Demo application pods (stable + canary) |

## Initial Deployment

### Option A — cluster without existing Argo CD

```bash
kubectl apply -k bootstrap/overlays/default/
```

### Option B — cluster with existing Argo CD

```bash
kubectl apply -k bootstrap/overlays/existing-argocd/
```

### Create the Secret (before or right after applying bootstrap)

The agent secret is **not** managed by GitOps. Two paths are supported:

**K8s Secret path (default):**

```bash
cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml
# Fill in: openai_api_key, google_api_key, github_token
kubectl apply -f system/kubernetes-agent/secret.yaml -n openshift-gitops
```

**Vault path (default for deployed stack):** Run `bootstrap/vault/vault-bootstrap.sh` (see the [README](README.md#vault-bootstrap)) to write credentials to Vault. The Vault Secrets Operator syncs them to the `kubernetes-agent` K8s Secret automatically.

`secret.yaml` is git-ignored. Never commit credentials.

## Making Changes

All cluster state flows through Git. The workflow is:

1. Edit the relevant manifest (e.g., update an image tag in a `kustomization.yaml`).
2. Commit and push to the branch that Argo CD is watching.
3. Argo CD syncs automatically (or force sync: `oc argo rollouts` / Argo CD UI).
4. Verify with `kubectl get pods` or the Argo CD UI.

For urgent fixes during a demo, you can force a pod restart after the image is already up-to-date:

```bash
kubectl rollout restart deployment/kubernetes-agent -n openshift-gitops
kubectl rollout restart deployment/quarkus-rollouts-demo -n quarkus-demo
```

## Switching Demo Scenarios

Update the image tag in the desired overlay's `kustomization.yaml`, then push:

```bash
# workloads/quarkus-rollouts-demo/overlays/scenario-2-null-pointer/kustomization.yaml
# Change newTag to the target scenario image tag, commit, push
```

Or use the JBang demo script to orchestrate all three scenarios:

```bash
jbang demo_scenarios.java
```

## Verifying the Stack

```bash
./validate-deployment.sh

# Individual checks
kubectl get pods -n openshift-gitops
kubectl get pods -n quarkus-demo
oc argo rollouts list rollouts -n quarkus-demo
kubectl get analysisrun -n quarkus-demo
```

## Monitoring a Rollout

```bash
oc argo rollouts get rollout quarkus-rollouts-demo -n quarkus-demo --watch

# Agent logs (AI analysis + remediation)
kubectl logs -f deployment/kubernetes-agent -n openshift-gitops

# Plugin logs (inside the Argo Rollouts controller)
kubectl logs -f deployment/argo-rollouts -n openshift-gitops | grep -E "metric-ai|analysis"
```

## Documentation Standards

- Update `README.md` when deployment steps or architecture change.
- Keep `system/kubernetes-agent/secret.yaml.template` in sync with any new required credentials.
- Professional tone, plain English, no AI-sounding phrasing.

## Resources

- [Argo CD](https://argo-cd.readthedocs.io/) | [Argo Rollouts](https://argo-rollouts.readthedocs.io/)
- [OpenShift GitOps](https://docs.openshift.com/gitops/latest/understanding_openshift_gitops/what-is-gitops.html)
- [Kustomize](https://kustomize.io/)
