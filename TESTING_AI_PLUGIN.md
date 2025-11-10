# Testing the AI Metrics Plugin with Argo Rollouts

This guide explains how to test the AI metrics plugin that's already integrated into your progressive-delivery setup.

## Current Setup

The AI metrics plugin is already configured in your deployment:

1. **Custom Argo Rollouts Image**: Uses `ghcr.io/carlossg/rollouts-plugin-metric-ai:latest` which includes the plugin
2. **Plugin Configuration**: Configured in [`plugin-config-patch.yaml`](system/progressive-delivery-controller/plugin-config-patch.yaml)
3. **Example Workload**: A canary deployment with AI analysis in [`workloads/canary-app/`](workloads/canary-app/)

## Prerequisites

Before testing, you need:

1. **Google API Key** for Gemini AI
2. **GitHub Token** (optional, for automatic PR creation on failures)
3. **Kubernetes cluster** with:
   - Argo Rollouts installed (via progressive-delivery)
   - Istio service mesh (for traffic routing)
   - Prometheus (for metrics)

## Step 1: Configure Secrets

Create your secret file from the template:

```bash
# Copy the template
cp progressive-delivery/system/progressive-delivery-controller/secret.yaml.template \
   progressive-delivery/system/progressive-delivery-controller/secret.yaml

# Edit the secret file
vi progressive-delivery/system/progressive-delivery-controller/secret.yaml
```

Replace the placeholder values with your actual credentials:
```yaml
stringData:
  GOOGLE_API_KEY: "your-actual-google-api-key"
  GITHUB_TOKEN: "your-actual-github-token"  # Optional
  AUTO_PR_ENABLED: "false"  # Set to "true" to enable automatic PR creation
```

**Note:** The `secret.yaml` file is in `.gitignore` and will not be committed to the repository.

## Step 2: Deploy the Progressive Delivery Stack

Deploy the entire stack using ArgoCD or kubectl:

### Option A: Using ArgoCD (Recommended)

If you have ArgoCD installed:

```bash
# Apply the bootstrap configuration
kubectl apply -k progressive-delivery/bootstrap/overlays/default/

# Wait for ArgoCD to sync
kubectl get applications -n openshift-gitops
```

### Option B: Using kubectl directly

```bash
# Deploy the system components (including Argo Rollouts with AI plugin)
kubectl apply -k progressive-delivery/system/progressive-delivery-controller/

# Wait for Argo Rollouts to be ready
kubectl wait --for=condition=available --timeout=300s \
  deployment/argo-rollouts -n argo-rollouts

# Verify the plugin is loaded
kubectl logs -n argo-rollouts deployment/argo-rollouts | grep "metric-ai"
```

## Step 3: Deploy the Canary Application

Deploy the example canary application:

```bash
# Deploy the canary app with AI analysis
kubectl apply -k progressive-delivery/workloads/canary-app/

# Verify the rollout is created
kubectl get rollout -n canary
kubectl get analysistemplate -n canary
```

## Step 4: Trigger a Rollout

Update the rollout to trigger a canary deployment:

```bash
# Update the image to trigger a rollout
kubectl argo rollouts set image rollouts-demo \
  rollouts-demo=argoproj/rollouts-demo:yellow \
  -n canary

# Watch the rollout progress
kubectl argo rollouts get rollout rollouts-demo -n canary --watch
```

## Step 5: Monitor the AI Analysis

Watch the AI analysis in action:

```bash
# View the analysis run
kubectl get analysisrun -n canary

# Get detailed analysis results
kubectl describe analysisrun -n canary <analysisrun-name>

# View plugin logs
kubectl logs -n argo-rollouts deployment/argo-rollouts | grep -E "metric-ai|AI metric|plugin"
```

## Understanding the AI Analysis

The AI plugin will:

1. **Collect logs** from both stable and canary pods
2. **Analyze with Gemini AI** to compare behavior
3. **Return verdict**: 
   - `Successful` if canary looks good
   - `Failed` if issues detected
4. **Optional**: Create GitHub PR with proposed fixes (if `AUTO_PR_ENABLED=true`)

### Analysis Template Configuration

The analysis template at [`workloads/canary-app/analysistemplate-ai.yaml`](workloads/canary-app/analysistemplate-ai.yaml) uses:

```yaml
metrics:
  - name: ai-log-analysis
    provider:
      plugin:
        argoproj-labs/metric-ai:
          model: gemini-2.0-flash-exp
          stablePodLabel: "app=rollouts-demo,revision=stable"
          canaryPodLabel: "app=rollouts-demo,revision=canary"
          baseBranch: main
          githubUrl: https://github.com/kdubois/progressive-delivery
          extraPrompt: "Focus on error rates and performance. This is a critical production deployment."
```

## Advanced: Using Agent Mode

For more sophisticated analysis, you can deploy the Kubernetes Agent and use agent mode:

### Deploy the Kubernetes Agent

```bash
# Deploy the agent
kubectl apply -k kubernetes-agent/deployment/

# Verify it's running
kubectl get pods -n argo-rollouts | grep kubernetes-agent
```

### Update Analysis Template for Agent Mode

Create a new analysis template with agent mode:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: ai-analysis-agent
  namespace: canary
spec:
  args:
  - name: namespace
  - name: canary-pod
  metrics:
  - name: ai-log-analysis
    provider:
      plugin:
        argoproj-labs/metric-ai:
          analysisMode: agent
          namespace: "{{args.namespace}}"
          podName: "{{args.canary-pod}}"
          model: gemini-2.0-flash-exp
          baseBranch: main
          githubUrl: https://github.com/kdubois/progressive-delivery
```

## Troubleshooting

### Check Plugin Status

```bash
# Verify plugin is loaded
kubectl logs -n argo-rollouts deployment/argo-rollouts | grep "Loading plugins"

# Check for plugin errors
kubectl logs -n argo-rollouts deployment/argo-rollouts | grep -i error
```

### Enable Debug Logging

The deployment already has `LOG_LEVEL=debug` enabled. To see more details:

```bash
# Follow logs in real-time
kubectl logs -f -n argo-rollouts deployment/argo-rollouts
```

### Common Issues

1. **"Plugin not found"**: Ensure the custom image is being used
   ```bash
   kubectl get deployment argo-rollouts -n argo-rollouts -o yaml | grep image:
   ```

2. **"API key not found"**: Check the secret is mounted correctly
   ```bash
   kubectl get secret argo-rollouts -n argo-rollouts -o yaml
   ```

3. **Analysis fails immediately**: Check pod labels match the template
   ```bash
   kubectl get pods -n canary --show-labels
   ```

## Testing Different Scenarios

### Test 1: Successful Canary (Blue → Green)

```bash
kubectl argo rollouts set image rollouts-demo \
  rollouts-demo=argoproj/rollouts-demo:green -n canary
```

### Test 2: Problematic Canary (Blue → Red with errors)

```bash
kubectl argo rollouts set image rollouts-demo \
  rollouts-demo=argoproj/rollouts-demo:red -n canary
```

The AI should detect issues in the red version and fail the analysis.

### Test 3: Manual Promotion

```bash
# Promote manually if needed
kubectl argo rollouts promote rollouts-demo -n canary
```

### Test 4: Abort Rollout

```bash
# Abort if analysis fails
kubectl argo rollouts abort rollouts-demo -n canary
```

## Viewing Results

### Via kubectl

```bash
# Get analysis run details
kubectl get analysisrun -n canary -o yaml

# View the metric results
kubectl get analysisrun <name> -n canary -o jsonpath='{.status.metricResults}'
```

### Via Argo Rollouts Dashboard

```bash
# Port-forward to the dashboard
kubectl port-forward -n argo-rollouts svc/argo-rollouts-dashboard 3100:3100

# Open in browser
open http://localhost:3100
```

## Next Steps

1. **Customize the Analysis**: Modify [`analysistemplate-ai.yaml`](workloads/canary-app/analysistemplate-ai.yaml) to adjust:
   - Pod labels
   - AI model
   - Extra prompts for specific focus areas
   - GitHub repository URL

2. **Add More Metrics**: Combine AI analysis with traditional metrics (success rate, latency, etc.)

3. **Enable Auto-PR**: Set `AUTO_PR_ENABLED: "true"` to automatically create PRs with fixes

4. **Deploy Agent Mode**: Use the Kubernetes Agent for more sophisticated analysis

## Resources

- [AI Plugin README](../rollouts-plugin-metric-ai/README.md)
- [Argo Rollouts Documentation](https://argoproj.github.io/argo-rollouts/)
- [Analysis Template Examples](../rollouts-plugin-metric-ai/config/rollouts-examples/)