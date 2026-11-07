# Agent Instructions — progressive-delivery

GitOps manifests for the full AI-powered progressive delivery stack on OpenShift. Managed by Argo CD — never patch cluster resources directly. All changes go through Git; Argo CD syncs them to the cluster.

Do not create summary or migration documents. Update manifests and documentation in place.

## Stack

OpenShift 4.20+, Argo CD (OpenShift GitOps operator), Argo Rollouts + `rollouts-plugin-metric-ai`, Kustomize, JBang scripts.

## Project Layout

```
bootstrap/
  bootstrap.java                     # JBang script — deploys stack + Vault bootstrap
  base/                              # Shared base (GitOps operator subscription)
  overlays/
    default/                         # Full deployment (installs Argo CD + all components)
    existing-argocd/                 # For clusters that already have Argo CD

components/
  applicationsets/
    system-appset.yaml               # Deploys: Argo Rollouts controller, kubernetes-agent, remediation-agent
    workloads-appset.yaml            # Deploys: quarkus-rollouts-demo, canary-app
  appprojects/                       # AppProject RBAC and sync policies

system/
  progressive-delivery-controller/   # Argo Rollouts + AI plugin (RolloutManager + ConfigMap)
  kubernetes-agent/
    deployment.yaml                  # Quarkus agent (512Mi–2Gi)
    configmap.yaml                   # AI model endpoint and provider config
    secret.yaml.template             # Credentials template — NOT managed by GitOps
    rbac.yaml                        # ClusterRole: read pods/logs/events/rollouts; Role: read own secret, pause rollouts
    service.yaml                     # ClusterIP :8080
  remediation-agent/
    remediation-agent.yaml           # A2A remediation service
    sandbox-namespace.yaml           # Namespace and SCC for sandbox pods
    vault-secrets.yaml               # GitHub token and model key from Vault
  agent-sandbox/                     # Sandboxed containers operator subscription
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
    base/                            # Rollout, AnalysisTemplate, services, OpenShift Route
    overlays/
      scenario-1-stable/             # Happy path image
      scenario-2-null-pointer/       # NPE bug image
      scenario-3-memory-leak/        # Memory leak image
      scenario-4-latency/            # Progressive latency regression image
  canary-app/                        # Alternate demo (argoproj/rollouts-demo)
```

## Namespace Layout

| Namespace | Contents |
|---|---|
| `openshift-gitops` | Argo CD, Argo Rollouts controller + plugin, kubernetes-agent, remediation-agent |
| `quarkus-demo` | Demo application pods (stable + canary) |

## Initial Deployment

Run the JBang bootstrap script from the repo root. It prompts for credentials upfront, deploys the stack, and configures Vault automatically.

```bash
./bootstrap/bootstrap.java                            # full install (includes Argo CD)
./bootstrap/bootstrap.java --overlay existing-argocd  # reuse existing Argo CD
./bootstrap/bootstrap.java --skip-vault               # skip Vault, use plain K8s Secret
./bootstrap/bootstrap.java --vault-only               # re-run Vault bootstrap only
```

The script accepts `ANALYSIS_API_KEY` and `GITHUB_TOKEN` as environment variables to skip their prompts.

**Vault path (standard):** Credentials are written to `secret/argo-rollouts/kubernetes-agent` in Vault KV v2. The Vault Secrets Operator (`VaultStaticSecret` in `system/vault-config/`) syncs them to the `kubernetes-agent` K8s Secret and automatically restarts the agent Deployment. Re-run with `--vault-only` if the Vault pod restarts (dev mode, in-memory storage).

**Plain K8s Secret path:** Use `--skip-vault`, then apply credentials manually:

```bash
cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml
# Fill in ANALYSIS_API_KEY, GITHUB_TOKEN
kubectl apply -f system/kubernetes-agent/secret.yaml -n openshift-gitops
```

`secret.yaml` is git-ignored (`.agentignore` + `.gitignore`). Never commit credentials.

## Making Changes

All cluster state flows through Git. The workflow is:

1. Edit the relevant manifest (e.g., update an image tag in a `kustomization.yaml`).
2. Commit and push to the branch that Argo CD is watching.
3. Argo CD syncs automatically (or force sync: `oc argo rollouts` / Argo CD UI).
4. Verify with `kubectl get pods` or the Argo CD UI.

For urgent fixes during a demo, you can force a pod restart after the image is already up-to-date:

```bash
kubectl rollout restart deployment/kubernetes-agent -n openshift-gitops
kubectl rollout restart deployment/remediation-agent -n openshift-gitops
```

## Switching Demo Scenarios

Update the image line in the base rollout manifest, then push. The ApplicationSet
deploys `workloads/quarkus-rollouts-demo/` directly; the overlays are not
synced, so editing an overlay does nothing.

```bash
# workloads/quarkus-rollouts-demo/base/rollout.yaml
# Comment the active image line, uncomment the scenario you want, commit, push
```

## Fast Sync Tuning (per-cluster state)

Argo CD learns about new commits from its git poll or from the GitHub webhook
the bootstrap configures. Both live on the cluster, not in Git:

- `timeout.reconciliation: 15s` in the `argocd-cm` ConfigMap (default: 3 min).
  The application controller reads it at startup — restart
  `statefulset/openshift-gitops-application-controller` after changing it.
- A GitHub webhook on this repository pointing at
  `https://<openshift-gitops-server-route>/api/webhook`, with its secret
  matching `webhook.github.secret` in the `argocd-secret` Secret.

These clusters are short-lived. When a new cluster replaces an old one, the
webhook still points at the dead cluster's route and its deliveries fail while
the hook keeps looking healthy in the GitHub settings — re-run the bootstrap
to repoint it. Manual repair:

```bash
# poll interval
oc patch cm argocd-cm -n openshift-gitops -p '{"data":{"timeout.reconciliation":"15s"}}'
oc rollout restart statefulset/openshift-gitops-application-controller -n openshift-gitops

# webhook (find the id first: gh api repos/kdubois/progressive-delivery/hooks)
SECRET=$(oc get secret argocd-secret -n openshift-gitops -o jsonpath='{.data.webhook\.github\.secret}' | base64 -d)
ROUTE=$(oc get route openshift-gitops-server -n openshift-gitops -o jsonpath='{.spec.host}')
gh api -X PATCH repos/kdubois/progressive-delivery/hooks/<id> \
  -f "config[url]=https://${ROUTE}/api/webhook" -f "config[secret]=${SECRET}"
```

Verify deliveries with `gh api repos/kdubois/progressive-delivery/hooks/<id>/deliveries`
(`status_code` 200 and `status` OK).

## Verifying the Stack

```bash
kubectl get pods -n openshift-gitops
kubectl get pods -n quarkus-demo
oc argo rollouts list rollouts -n quarkus-demo
kubectl get analysisrun -n quarkus-demo
```

## Monitoring a Rollout

```bash
oc argo rollouts get rollout quarkus-demo -n quarkus-demo --watch

# Agent logs (AI analysis + remediation)
kubectl logs -f deployment/kubernetes-agent -n openshift-gitops

# Remediation logs
kubectl logs -f deployment/remediation-agent -n openshift-gitops

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
