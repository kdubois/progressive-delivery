# Progressive Delivery with Argo Rollouts and AI-Powered Analysis

This repository demonstrates progressive delivery on OpenShift using Argo Rollouts with an AI-powered metrics plugin for automated canary analysis. The setup includes a complete GitOps workflow with Argo CD, OpenShift Routes for traffic management, and an autonomous Kubernetes agent that analyzes deployments using AI.

## Overview

The AI metrics plugin integrates with Argo Rollouts to provide intelligent canary analysis. During rollouts, an autonomous agent fetches logs from stable and canary pods, analyzes them using AI models (Gemini or OpenAI), and decides whether to promote or abort the deployment. When issues are detected, the agent can automatically create GitHub issues with detailed diagnostics.

### Architecture

```
Argo Rollouts (with AI plugin) 
    ↓ (A2A protocol)
Kubernetes Agent (Quarkus + LangChain4j)
    ↓ (fetches logs via Kubernetes API)
Application Pods (stable + canary)
```

The plugin delegates all AI operations to the agent, keeping the plugin itself lightweight and focused on metrics collection.

## Prerequisites

- OpenShift cluster v4.10 or later
- [Argo Rollouts kubectl plugin](https://argoproj.github.io/argo-rollouts/installation/#kubectl-plugin-installation)
- Google API key (for Gemini) or OpenAI API key
- GitHub personal access token with `repo` scope

This setup is designed for testing and demonstration purposes. Do not deploy to production clusters without proper review and hardening.

## Setup

### Fork the Repository

Fork this repository and update the ApplicationSet configurations in [`components/applicationsets`](components/applicationsets) to point to your fork. You'll need to change the `repoURL` fields in both `system-appset.yaml` and `workloads-appset.yaml`.

### Configure Secrets (Before Deployment)

The Kubernetes agent secret is **not managed by GitOps** because it contains sensitive credentials. Two paths are supported:

#### Option A — Kubernetes Secret (default, no Vault required)

```shell
# Copy the template and fill in your credentials
cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml
vim system/kubernetes-agent/secret.yaml

# Apply to your cluster
kubectl apply -f system/kubernetes-agent/secret.yaml
```

The template expects these values:

```yaml
stringData:
  openai_api_key: sk-...           # Required for OpenAI-compatible models
  rem_api_key: sk-...              # Optional — defaults to openai_api_key
  google_api_key: ...              # Optional — for Gemini-based setups
  github_token: ghp_...            # Required for GitHub PR/issue creation
  google_cloud_project: ...        # Optional
```

**Where to obtain credentials:**
- Google API key: https://aistudio.google.com/app/apikey
- OpenAI API key: https://platform.openai.com/api-keys
- GitHub token: https://github.com/settings/tokens (requires `repo` scope)

#### Option B — Vault + Vault Secrets Operator (recommended)

The stack includes HashiCorp Vault (deployed via the official Helm chart) and the Red Hat-certified Vault Secrets Operator (VSO). VSO syncs credentials from Vault KV to a Kubernetes Secret automatically, so the agent reads a standard K8s Secret without needing Vault-specific code.

After the stack is deployed, run the one-time [bootstrap script](#vault-bootstrap) to write credentials and configure Kubernetes auth:

```shell
export ANALYSIS_API_KEY="sk-..."
export GITHUB_TOKEN="ghp_..."
./bootstrap/vault/vault-bootstrap.sh
```

VSO then creates and keeps the `kubernetes-agent` Secret in sync. See the [Vault Bootstrap](#vault-bootstrap) section for details.

**Important:** `secret.yaml` is git-ignored. When using Vault, the Secret is managed by VSO — do not create it manually.

### Deploy the Stack

Now that secrets are configured, deploy the full stack.

#### Option 1: Cluster does not already have Argo CD

Deploy the full stack, including OpenShift GitOps and the Vault operator:

```shell
./bootstrap/bootstrap.sh
```

The script installs the operators first, waits for their CRDs to become established, then applies the Argo CD instance, AppProjects, and ApplicationSets.

#### Option 2: Cluster already has a shared `openshift-gitops` Argo CD

Reuse the existing Argo CD instance without installing the GitOps operator or replacing the `ArgoCD` resource:

```shell
./bootstrap/bootstrap.sh existing-argocd
```

See [`DEPLOYMENT_EXISTING_ARGOCD.md`](DEPLOYMENT_EXISTING_ARGOCD.md) for the full reproducible procedure and verification steps.

The deployment includes:

- OpenShift GitOps (Argo CD) when using [`bootstrap/overlays/default`](bootstrap/overlays/default/kustomization.yaml)
- HashiCorp Vault (official Helm chart, dev mode) + Vault Secrets Operator (Red Hat-certified)
- Argo Rollouts with AI metrics plugin
- Kubernetes agent for AI analysis
- Sample Quarkus application with canary configuration

#### Vault Bootstrap

Once the stack is up and Vault is ready, run the one-time bootstrap script to write credentials and configure Kubernetes auth. The script handles port-forwarding automatically:

```shell
export ANALYSIS_API_KEY="sk-..."
export GITHUB_TOKEN="ghp_..."
# export REMEDIATION_API_KEY="..."    # optional, defaults to ANALYSIS_API_KEY

./bootstrap/vault/vault-bootstrap.sh
```

The script:
1. Waits for the Vault pod to be ready
2. Port-forwards to Vault (dev mode root token is `root`)
3. Enables the KV v2 secrets engine and writes your credentials
4. Configures Kubernetes auth with a policy and role for the `kubernetes-agent` ServiceAccount

After the script completes, the Vault Secrets Operator syncs the credentials to a K8s Secret named `kubernetes-agent`. Verify with:

```shell
oc get secret kubernetes-agent -n openshift-gitops
```

The VSO `VaultStaticSecret` is configured to automatically restart the kubernetes-agent Deployment when secrets change in Vault.

**Dev mode note:** Vault runs in dev mode with in-memory storage. If the Vault pod restarts, re-run the bootstrap script to repopulate the secrets.

### Verify Deployment

You will have to wait a while before all components have been deployed. Once that's done, make sure you applied the secret from above, and then check that all components are running:

```shell
# Verify Argo Rollouts
oc get pods -n openshift-gitops | grep argo-rollouts

# Verify Kubernetes agent
oc get pods -n openshift-gitops | grep kubernetes-agent

# Test agent health
oc port-forward -n openshift-gitops svc/kubernetes-agent 8080:8080 &
curl http://localhost:8080/q/health
# Expected: {"status":"UP",...}

# Confirm plugin is loaded
oc logs deployment/argo-rollouts -n openshift-gitops | grep -i "download.*metric-ai"
# Expected: "Downloading plugin argoproj-labs/metric-ai from: ..."
# Expected: "Download complete, it took X.XXs"

# If the plugin is not loaded, see the "Plugin Not Loading" section in Troubleshooting
```

**Note:** If the plugin does not load on first deployment, this may be due to a timing issue with the RolloutManager operator. If you see plugin-related errors during rollouts, refer to the [Plugin Not Loading](#plugin-not-loading) troubleshooting section for a simple workaround (restart the argo-rollouts pod).

### Access the Application

Get the application route URL and open it in your browser:

```shell
export APP_URL=$(oc get route quarkus-demo -n quarkus-demo -o jsonpath='{.spec.host}')
firefox https://$APP_URL
```

You should see the sample Quarkus application dashboard showing the current deployment status and rollout information.

## Testing Progressive Delivery

### Trigger a Rollout

Update the application image version in the kustomization file:

```shell
# Example: change from version 1.0.0 to 1.0.1
sed -i 's/main/v1.stable/g' workloads/quarkus-rollouts-demo/base/rollouts.yaml

# Commit and push
git add .
git commit -m "Update to stable version"
git push
```

### Monitor the Rollout

Watch the rollout progress:

```shell
oc argo rollouts get rollout quarkus-demo -n quarkus-demo --watch
```

During each canary step, the AI analysis occurs:

1. Plugin sends analysis request to the Kubernetes agent
2. Agent fetches logs from stable and canary pods
3. Agent analyzes logs using the configured AI model
4. Agent returns a promote or abort recommendation
5. Rollout proceeds or aborts based on the analysis

View analysis results:

```shell
# List all analysis runs
oc get analysisrun -n quarkus-demo

# View specific analysis details
oc get analysisrun <name> -n quarkus-demo -o yaml
```

### Test Auto-Rollback


The sample application includes a failure mode that can be triggered by setting the `SCENARIO_MODE` environment variable. To test automatic rollback:



1. Update the kustomization file to enable error scenario mode. We have already provided a few images with null pointer or memory leak issues:

```shell
# Edit the rollouts file to point to one of these images:
vim workloads/quarkus-rollouts-demo/workloads/base/rollout.yaml


# Commit and push the change
git add .
git commit -m "Enable error scenario for rollback test"
git push
```

2. Start a new rollout by updating the application version
3. Watch as the AI agent detects the errors in the canary pods and aborts the rollout

4. The deployment automatically rolls back to the stable version

```shell
# Monitor the rollback

oc argo rollouts get rollout quarkus-demo -n quarkus-demo --watch

```


To fix and retry after the rollback:


```shell

# Change SCENARIO_MODE in the kustomization file back to a stable version
vim workloads/quarkus-rollouts-demo/workloads/base/rollout.yaml

# Commit and push
git add .
git commit -m "Disable error scenario"
git push

# Or manually retry the rollout

oc argo rollouts retry rollout quarkus-demo -n quarkus-demo
```

## Configuration

### Plugin Registration

The AI metrics plugin is registered in the Argo Rollouts ConfigMap at [`system/progressive-delivery-controller/argo-rollouts-configmap.yaml`](system/progressive-delivery-controller/argo-rollouts-configmap.yaml):

```yaml
data:
  metricProviderPlugins: |-
    - name: argoproj-labs/metric-ai
      location: file:///home/argo-rollouts/rollouts-plugin-metric-ai
```

The plugin binary is included in the custom Argo Rollouts image specified in [`system/progressive-delivery-controller/rolloutmanager.yaml`](system/progressive-delivery-controller/rolloutmanager.yaml).

### Analysis Template

The AnalysisTemplate at [`workloads/quarkus-rollouts-demo/analysistemplate-ai-agent.yaml`](workloads/quarkus-rollouts-demo/analysistemplate-ai-agent.yaml) configures how the plugin analyzes deployments:

```yaml
spec:
  metrics:
    - name: ai-analysis
      provider:
        plugin:
          argoproj-labs/metric-ai:
            agentUrl: http://kubernetes-agent:8080
            stableLabel: role=stable
            canaryLabel: role=canary
            githubUrl: https://github.com/your-org/your-repo
            baseBranch: main
            extraPrompt: "Additional context for AI analysis"
```

### Agent Deployment

The Kubernetes agent runs as a separate service in the `openshift-gitops` namespace. Configuration is in [`system/kubernetes-agent/deployment.yaml`](system/kubernetes-agent/deployment.yaml). The agent requires RBAC permissions to access pod logs across namespaces.

#### ConfigMap Configuration

Model and endpoint configuration is managed in [`system/kubernetes-agent/configmap.yaml`](system/kubernetes-agent/configmap.yaml).

**Note:** The ConfigMap contains environment-specific endpoints. Update these values to match your environment before deployment.

```yaml
data:
  # Analysis model configuration (any OpenAI-compatible endpoint)
  ANALYSIS_BASE_URL: "https://litellm-prod.apps.maas.redhatworkshops.io/v1"
  ANALYSIS_MODEL: "qwen3-14b"
  
  # Remediation model configuration (used for code fixes)
  REMEDIATION_BASE_URL: "https://api.openai.com/v1"
  REMEDIATION_MODEL: "gpt-4o"
  
  QUARKUS_PROFILE: "prod"
```

**Configuration parameters:**
- `ANALYSIS_MODEL`: Model name for the analysis agent (any OpenAI-compatible model)
- `ANALYSIS_BASE_URL`: Base URL for analysis model API (can point to LiteLLM, vLLM, Gemini, or OpenAI)
- `REMEDIATION_MODEL`: Model used by the remediation agent for code fixes (defaults to ANALYSIS_MODEL)
- `REMEDIATION_BASE_URL`: Base URL for remediation model API (defaults to ANALYSIS_BASE_URL)

To switch between AI providers, edit the ConfigMap:

```shell
# Switch to Gemini (via OpenAI-compatible endpoint)
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"ANALYSIS_BASE_URL":"https://generativelanguage.googleapis.com/v1beta/openai/","ANALYSIS_MODEL":"gemini-2.5-flash"}}'

# Switch to a local vLLM/LiteLLM endpoint
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"ANALYSIS_BASE_URL":"http://vllm-service:8000/v1","ANALYSIS_MODEL":"qwen3-14b"}}'

# Restart agent to apply changes
oc rollout restart deployment/kubernetes-agent -n openshift-gitops
```


## Troubleshooting

### Plugin Not Loading

If you encounter the error "plugin argoproj-labs/metric-ai not configured in configmap", this is due to a known bug in the OpenShift GitOps RolloutManager operator where plugin configuration doesn't always sync properly to the Argo Rollouts controller on initial deployment.

**Workaround (temporary - fixed in next OpenShift GitOps release):**

```shell
# Delete the Argo Rollouts pod to force plugin reinitialization
oc delete pod -n openshift-gitops -l app.kubernetes.io/name=argo-rollouts

# Wait for the new pod to be ready
oc wait --for=condition=ready pod -n openshift-gitops -l app.kubernetes.io/name=argo-rollouts --timeout=60s

# Verify the plugin was downloaded successfully
oc logs -n openshift-gitops -l app.kubernetes.io/name=argo-rollouts | grep -i "download.*metric-ai"
# Expected: "Downloading plugin argoproj-labs/metric-ai from: ..."
# Expected: "Download complete, it took X.XXs"

# Retry any failed rollouts
oc argo rollouts retry rollout quarkus-demo -n quarkus-demo
```

**General plugin troubleshooting:**

```shell
# Check ConfigMap configuration
oc get configmap argo-rollouts-config -n openshift-gitops -o yaml

# Check Rollouts controller logs
oc logs deployment/argo-rollouts -n openshift-gitops | grep -i plugin
```

### Agent Connection Issues

```shell
# Verify agent is running
oc get pods -n openshift-gitops | grep kubernetes-agent

# Check agent logs
oc logs deployment/kubernetes-agent -n openshift-gitops

# Test connectivity from Rollouts controller
oc exec -it deployment/argo-rollouts -n openshift-gitops -- \
  curl http://kubernetes-agent:8080/q/health
```

### Analysis Failures

```shell
# Check AnalysisTemplate configuration
oc get analysistemplate ai-analysis-agent -n quarkus-demo -o yaml

# Verify pod labels match selectors
oc get pods -n quarkus-demo --show-labels

# Check agent can access logs
oc logs deployment/kubernetes-agent -n openshift-gitops | grep -i "fetching logs"
```

### API Key Issues

```shell
# Verify secret exists and contains keys (synced by VSO from Vault)
oc get secret kubernetes-agent -n openshift-gitops -o yaml

# Check agent logs for authentication errors
oc logs deployment/kubernetes-agent -n openshift-gitops | grep -i "auth\|api key"
```

If the secret does not exist, check VSO status:

```shell
# Check VaultStaticSecret sync status
oc get vaultstaticsecret -n openshift-gitops
oc describe vaultstaticsecret kubernetes-agent-secret -n openshift-gitops

# Check VSO controller logs
oc logs deployment/vault-secrets-operator-controller-manager -n openshift-operators | tail -20
```

### Enable Debug Logging

```shell
# Enable debug logging for Rollouts controller
oc set env deployment/argo-rollouts LOG_LEVEL=debug -n openshift-gitops

# Enable debug logging for agent
oc set env deployment/kubernetes-agent QUARKUS_LOG_LEVEL=DEBUG -n openshift-gitops

# View logs
oc logs -f deployment/argo-rollouts -n openshift-gitops
oc logs -f deployment/kubernetes-agent -n openshift-gitops
```

## Advanced Configuration

### Switching AI Models

Update `QUARKUS_PROFILE` in the ConfigMap:

```shell
# Switch to Gemini
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"QUARKUS_PROFILE":"prod,gemini"}}'

# Switch to OpenAI
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"QUARKUS_PROFILE":"prod,openai"}}'

# Restart agent to apply
oc rollout restart deployment/kubernetes-agent -n openshift-gitops
```

### Custom Analysis Prompts

Add application-specific context to improve analysis accuracy:

```yaml
extraPrompt: |
  This is a payment processing service.
  Focus on transaction errors and database connection issues.
  Temporary network errors during startup are acceptable.
  Ignore UI-related warnings.
```

### GitHub Integration

When configured with a GitHub URL and token, the agent can automatically create issues when deployments fail. Issues include:

- Detailed error analysis
- Log excerpts showing the problem
- Recommended remediation steps
- Links to relevant documentation

## Additional Resources

- [Argo Rollouts Plugin README](../rollouts-plugin-metric-ai/README.md)
- [Kubernetes Agent README](../kubernetes-agent/README.md)
- [Argo Rollouts Documentation](https://argoproj.github.io/argo-rollouts/)
- [OpenShift GitOps Documentation](https://docs.openshift.com/gitops/)
