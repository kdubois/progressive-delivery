# Progressive Delivery with Argo Rollouts and AI-Powered Analysis

This repository demonstrates progressive delivery with OpenShift GitOps using Argo Rollouts with an AI-powered metrics plugin for automated canary analysis. The setup includes a complete GitOps workflow with Argo CD, OpenShift Routes for traffic management, and an autonomous Kubernetes agent that analyzes deployments using AI.

## Overview

The AI metrics plugin integrates with Argo Rollouts to provide intelligent canary analysis. During rollouts, an autonomous agent fetches logs from stable and canary pods, analyzes them using AI models (Gemini or OpenAI), and decides whether to promote or abort the deployment. When issues are detected, the agent can automatically create GitHub issues with detailed diagnostics.

```
Argo Rollouts (with AI plugin)
    ↓ (A2A protocol)
Kubernetes Agent (Quarkus + LangChain4j)
    ↓ (fetches logs via Kubernetes API)
Application Pods (stable + canary)
```

## Prerequisites

- OpenShift cluster v4.20 or later
- [Argo Rollouts kubectl plugin](https://argoproj.github.io/argo-rollouts/installation/#kubectl-plugin-installation)
- [JBang](https://www.jbang.dev/) (auto-installed by the bootstrap script if missing)
- An AI API key — OpenAI, Gemini, or any OpenAI-compatible endpoint
- A GitHub personal access token with `repo` scope ([create one](https://github.com/settings/tokens))

## Setup

### 1. Fork and configure the repository

Fork this repository, then update the `repoURL` fields in both ApplicationSet files to point to your fork:

- [`components/applicationsets/system-appset.yaml`](components/applicationsets/system-appset.yaml)
- [`components/applicationsets/workloads-appset.yaml`](components/applicationsets/workloads-appset.yaml)

Update the agent ConfigMap at [`system/kubernetes-agent/configmap.yaml`](system/kubernetes-agent/configmap.yaml) with your model endpoints:

```yaml
data:
  ANALYSIS_BASE_URL: "https://api.openai.com/v1"
  ANALYSIS_MODEL: "gpt-4o"
  REMEDIATION_BASE_URL: "https://api.openai.com/v1"  # defaults to ANALYSIS_BASE_URL if omitted
  REMEDIATION_MODEL: "gpt-4o"                         # defaults to ANALYSIS_MODEL if omitted
```

Any OpenAI-compatible endpoint works here — LiteLLM, vLLM, Gemini, etc.

### 2. Run the bootstrap script

The bootstrap script deploys the full stack and then optionally configures a HashiCorp Vault instance with your credentials. It prompts for everything it needs upfront before touching the cluster.

**If your cluster does not already have Argo CD:**

```shell
./bootstrap/bootstrap.java
```

(Note: You don't need to have Java installed, this is a [JBang](https://www.jbang.dev/) script that self-installs dependencies)

**If your cluster already has a shared `openshift-gitops` Argo CD instance:**

```shell
./bootstrap/bootstrap.java --overlay existing-argocd
```

See [`DEPLOYMENT_EXISTING_ARGOCD.md`](DEPLOYMENT_EXISTING_ARGOCD.md) for full details on the existing-argocd path.

Flow of the script:
1. Prompt for your AI API key, remediation API key (optional), and GitHub token
2. Install the OpenShift GitOps and Vault operators (default overlay only)
3. Wait for the Argo CD CRDs to be established
4. Apply the Argo CD instance, AppProjects, and ApplicationSets
5. (Optionally) Wait for Vault to be ready, then write your credentials and configure Kubernetes auth

After it completes, Argo CD syncs the rest of the stack: Argo Rollouts, the AI plugin, the Kubernetes agent, and the sample application.

#### What gets deployed

- (Optional) HashiCorp Vault + Vault Secrets Operator — syncs credentials to a K8s Secret
- Argo Rollouts with the AI metrics plugin
- Kubernetes agent for AI-powered canary analysis
- Sample (Quarkus-based) application with canary rollout configuration

#### Managing secrets without Vault

If you prefer a plain Kubernetes Secret instead of Vault:

```shell
./bootstrap/bootstrap.java --skip-vault
```

Then apply credentials manually:

```shell
cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml
# fill in your credentials
oc apply -f system/kubernetes-agent/secret.yaml
```

The template expects:

```yaml
stringData:
  openai_api_key: sk-...      # required
  github_token: ghp_...       # required
  rem_api_key: sk-...         # optional — defaults to openai_api_key
```

> `secret.yaml` is git-ignored and never managed by GitOps.

#### Re-running the Vault bootstrap

Vault runs in dev mode with in-memory storage. If the Vault pod restarts, credentials are lost. Re-run just the Vault setup:

```shell
./bootstrap/bootstrap.java --vault-only
```

Credentials can also be passed via environment variables to skip the prompts:

```shell
ANALYSIS_API_KEY=sk-... GITHUB_TOKEN=ghp_... ./bootstrap/bootstrap.java
```

### 3. Verify the deployment

Argo CD will take a few minutes to sync everything. Once it's done:

```shell
# Argo Rollouts controller
oc get pods -n openshift-gitops | grep argo-rollouts

# Kubernetes agent
oc get pods -n openshift-gitops | grep kubernetes-agent

# Agent health check
oc port-forward -n openshift-gitops svc/kubernetes-agent 8080:8080 &
curl http://localhost:8080/q/health

# Confirm the plugin loaded
oc logs deployment/argo-rollouts -n openshift-gitops | grep -i "download.*metric-ai"
```

**Note:** If the plugin does not load on first deployment, this may be due to a timing issue with the RolloutManager operator. If you see plugin-related errors during rollouts, refer to the [Plugin Not Loading](#plugin-not-loading) troubleshooting section for a simple workaround (restart the argo-rollouts pod).

### 4. Access the application

```shell
export APP_URL=$(oc get route quarkus-demo -n quarkus-demo -o jsonpath='{.spec.host}')
open https://$APP_URL
```
You should see the sample Quarkus application dashboard showing the current deployment status and rollout information.

## Testing progressive delivery

### Trigger a rollout

Edit the image tag in [`workloads/quarkus-rollouts-demo/base/rollouts.yaml`](workloads/quarkus-rollouts-demo/base/rollouts.yaml), commit, and push. Argo CD detects the change and starts the rollout.

e.g. 
```shell
# Example: change from version 1.0.0 to 1.0.1
sed -i 's/main/v1.stable/g' workloads/quarkus-rollouts-demo/base/rollouts.yaml

# Commit and push
git add .
git commit -m "Update to stable version"
git push

```shell
oc argo rollouts get rollout quarkus-demo -n quarkus-demo --watch
```

During each canary step the plugin sends logs to the agent, which returns a promote or abort decision.

```shell
# List analysis runs
oc get analysisrun -n quarkus-demo

# Inspect a run
oc get analysisrun <name> -n quarkus-demo -o yaml
```

### Test auto-rollback

The sample application ships several pre-built images with injected failure modes (null pointer exceptions, memory leaks). Point the rollout at one of them to see the agent detect the problem and abort:

```shell
vim workloads/quarkus-rollouts-demo/base/rollouts.yaml
git add . && git commit -m "test: trigger rollback scenario" && git push
oc argo rollouts get rollout quarkus-demo -n quarkus-demo --watch
```

To recover, revert the image tag and push, or retry manually:

```shell
oc argo rollouts retry rollout quarkus-demo -n quarkus-demo
```

## Configuration

### Switching AI models at runtime

```shell
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"ANALYSIS_BASE_URL":"https://generativelanguage.googleapis.com/v1beta/openai/","ANALYSIS_MODEL":"gemini-2.5-flash"}}'
oc rollout restart deployment/kubernetes-agent -n openshift-gitops
```

### Custom analysis prompts

Add application-specific context to the AnalysisTemplate at [`workloads/quarkus-rollouts-demo/analysistemplate-ai-agent.yaml`](workloads/quarkus-rollouts-demo/analysistemplate-ai-agent.yaml):

```yaml
argoproj-labs/metric-ai:
  agentUrl: http://kubernetes-agent:8080
  extraPrompt: |
    This is a payment processing service.
    Ignore transient network errors during startup.
    Abort on any transaction failure or database connection error.
```

## Troubleshooting

### Plugin not loading

If rollouts fail with "plugin argoproj-labs/metric-ai not configured in configmap", this is a known timing bug in the OpenShift GitOps RolloutManager operator. Restart the controller to fix it:

```shell
oc delete pod -n openshift-gitops -l app.kubernetes.io/name=argo-rollouts
oc wait --for=condition=ready pod -n openshift-gitops -l app.kubernetes.io/name=argo-rollouts --timeout=60s
oc argo rollouts retry rollout quarkus-demo -n quarkus-demo
```

### Agent not starting

```shell
oc logs deployment/kubernetes-agent -n openshift-gitops

# Verify the secret exists (synced from Vault by VSO)
oc get secret kubernetes-agent -n openshift-gitops

# If the secret is missing, check VSO sync status
oc describe vaultstaticsecret kubernetes-agent-secret -n openshift-gitops
oc logs deployment/vault-secrets-operator-controller-manager -n openshift-operators | tail -20
```

### Analysis failures

```shell
# Check pod labels match the selectors in the AnalysisTemplate
oc get pods -n quarkus-demo --show-labels

# Check agent logs for errors
oc logs deployment/kubernetes-agent -n openshift-gitops | grep -i "fetching logs\|auth\|api key"
```

### Enable debug logging

```shell
oc set env deployment/argo-rollouts LOG_LEVEL=debug -n openshift-gitops
oc set env deployment/kubernetes-agent QUARKUS_LOG_LEVEL=DEBUG -n openshift-gitops
```

## Additional resources

- [Argo Rollouts plugin README](https://github.com/argoproj-labs/rollouts-plugin-metric-ai)
- [Kubernetes agent README](https://github.com/kdubois/kubernetes-aiops-agent)
- [Argo Rollouts documentation](https://argoproj.github.io/argo-rollouts/)
- [OpenShift GitOps documentation](https://docs.openshift.com/gitops/)
