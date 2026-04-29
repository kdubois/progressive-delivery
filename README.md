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

Before deploying the stack, you must create the Kubernetes agent secret manually in your cluster. The secret is **not managed by GitOps** because it contains sensitive credentials.

```shell
# Copy the template to create your secret file
cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml

# Edit with your actual credentials
vim system/kubernetes-agent/secret.yaml

# Apply the secret directly to your cluster
kubectl apply -f system/kubernetes-agent/secret.yaml
```

**Important:** The `secret.yaml` file is git-ignored for security. You must create this secret in your cluster before deploying via Argo CD.

The secret template expects these values:

```yaml
stringData:
  openai_api_key: sk-...           # Required for OpenAI-compatible models
  rem_api_key: sk-...              # Optional, for remediation agent (defaults to openai_api_key)
  google_api_key: ...              # Optional, for Gemini-based setups
  github_token: ghp_...            # Required for GitHub PR/issue creation
  google_cloud_project: ...        # Optional
```

**Credential notes:**
- `OPENAI_API_KEY`: required when using the default OpenAI-compatible configuration
- `REM_API_KEY`: optional unless you want separate credentials for remediation/code-fix flows (defaults to `OPENAI_API_KEY`)
- `GOOGLE_API_KEY`: optional, used for Gemini-based setups
- `GITHUB_TOKEN`: required if you want automatic GitHub issue or PR creation
- `GOOGLE_CLOUD_PROJECT`: optional

**Where to obtain credentials:**
- Google API key: https://aistudio.google.com/app/apikey
- OpenAI API key: https://platform.openai.com/api-keys
- GitHub token: https://github.com/settings/tokens (requires `repo` scope)

### Deploy the Stack

Now that secrets are configured, deploy the full stack.

#### Option 1: Cluster does not already have Argo CD

Deploy the full stack, including OpenShift GitOps:

```shell
until oc apply -k bootstrap/overlays/default/; do sleep 15; done
```

#### Option 2: Cluster already has a shared `openshift-gitops` Argo CD

Reuse the existing Argo CD instance without installing the GitOps operator or replacing the `ArgoCD` resource:

```shell
until oc apply -k bootstrap/overlays/existing-argocd/; do sleep 15; done
```

See [`DEPLOYMENT_EXISTING_ARGOCD.md`](DEPLOYMENT_EXISTING_ARGOCD.md) for the full reproducible procedure and verification steps.

These commands retry until successful, as some resources depend on operators being installed first. The deployment includes:

- OpenShift GitOps (Argo CD) when using [`bootstrap/overlays/default`](bootstrap/overlays/default/kustomization.yaml)
- Argo Rollouts with AI metrics plugin
- Kubernetes agent for AI analysis (with secret auto-generated from your `secrets.env`)
- Sample Quarkus application with canary configuration

### Verify Deployment

Check that all components are running:

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

**Note:** Due to a known bug in the current OpenShift GitOps RolloutManager operator, the plugin may not load on first deployment. If you see plugin-related errors during rollouts, refer to the [Plugin Not Loading](#plugin-not-loading) troubleshooting section. This issue will be fixed in the next OpenShift GitOps release.

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
sed -i 's/1.0.0/1.0.1/g' workloads/quarkus-rollouts-demo/kustomization.yaml

# Commit and push
git add .
git commit -m "Update to version 1.0.1"
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



1. Update the kustomization file to enable error scenario mode:

```shell
# Edit the kustomization file to set SCENARIO_MODE
vim workloads/quarkus-rollouts-demo/kustomization.yaml

# Add or update the patch to set SCENARIO_MODE to 'failure'
# Example:
#              env:
#              - name: SCENARIO_MODE
#                value: "failure"
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

# Change SCENARIO_MODE in the kustomization file back to 'success'
vim workloads/quarkus-rollouts-demo/kustomization.yaml

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

Model and endpoint configuration is managed in [`system/kubernetes-agent/configmap.yaml`](system/kubernetes-agent/configmap.yaml):

```yaml
data:
  # OpenAI-compatible model configuration (default)
  OPENAI_BASE_URL: "https://litellm-prod.apps.maas.redhatworkshops.io/v1"
  OPENAI_MODEL: "qwen3-14b"
  
  # Remediation model configuration (used for code fixes)
  REM_BASE_URL: "https://api.openai.com/v1"
  REM_MODEL: "gpt-4o"
  
  # Gemini model configuration
  GEMINI_MODEL: "gemini-2.5-flash"
  
  # Profile selection - determines which AI provider to use
  QUARKUS_PROFILE: "prod,openai"  # or "prod,gemini"
```

**Configuration parameters:**
- `OPENAI_MODEL`: Model name for OpenAI-compatible endpoints (default: `qwen3-14b`)
- `OPENAI_BASE_URL`: Base URL for OpenAI-compatible API (can point to LiteLLM, vLLM, or OpenAI)
- `GEMINI_MODEL`: Gemini model name when using Gemini profile (default: `gemini-2.5-flash`)
- `REM_MODEL`: Model used specifically by the remediation agent for code fixes (default: `gpt-4o`)
- `REM_BASE_URL`: Base URL for remediation model API
- `QUARKUS_PROFILE`: Set to `prod,openai` or `prod,gemini` to switch between AI providers

To switch between AI providers, edit the ConfigMap:

```shell
# Switch to Gemini
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"QUARKUS_PROFILE":"prod,gemini"}}'

# Switch to OpenAI
oc patch configmap kubernetes-agent-config -n openshift-gitops \
  --type merge -p '{"data":{"QUARKUS_PROFILE":"prod,openai"}}'

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
# Verify secret exists and contains keys
oc get secret kubernetes-agent -n openshift-gitops -o yaml

# Check agent logs for authentication errors
oc logs deployment/kubernetes-agent -n openshift-gitops | grep -i "auth\|api key"
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

Edit the agent secret to switch between Gemini and OpenAI:

```shell
oc edit secret kubernetes-agent -n openshift-gitops

# Restart agent to apply changes
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
