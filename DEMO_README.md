# Argo Rollouts AI-Powered Progressive Delivery Demo

This directory contains an automated demo script that showcases AI-powered progressive delivery with Argo Rollouts.

## Overview

The demo script (`demo_scenarios.java`) is a JBang-powered Quarkus application that automates the demonstration of three different deployment scenarios:

1. **Scenario 1: Happy Path** - Stable deployment with successful rollout
2. **Scenario 2: NullPointerException Bug** - Bug detection with automatic rollback and PR creation
3. **Scenario 3: Memory Leak** - Performance issue detection with automatic rollback and Issue creation

## Prerequisites

### Required Tools

1. **JBang** - For running the script
   ```bash
   # macOS
   brew install jbangdev/tap/jbang
   
   # Linux
   curl -Ls https://sh.jbang.dev | bash -s - app setup
   
   # Windows
   choco install jbang
   ```

2. **kubectl** - Kubernetes CLI
   ```bash
   # macOS
   brew install kubectl
   
   # Linux
   curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
   ```

3. **kubectl argo rollouts plugin**
   ```bash
   curl -LO https://github.com/argoproj/argo-rollouts/releases/latest/download/kubectl-argo-rollouts-$(uname -s | tr '[:upper:]' '[:lower:]')-amd64
   chmod +x kubectl-argo-rollouts-*
   sudo mv kubectl-argo-rollouts-* /usr/local/bin/kubectl-argo-rollouts
   ```

### Kubernetes Cluster Requirements

- A running Kubernetes cluster (local or remote)
- Argo Rollouts installed:
  ```bash
  kubectl create namespace argo-rollouts
  kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml
  ```
- The AI agent and metric plugin deployed (see main README)

## Usage

### Basic Usage

Run all three scenarios interactively:
```bash
cd progressive-delivery
./demo_scenarios.java
```

The script will pause between scenarios, allowing you to observe the results.

### Command-Line Options

```bash
./demo_scenarios.java [OPTIONS]

Options:
  --auto              Run in automatic mode (no pauses between scenarios)
  --scenario N        Run only scenario N (1, 2, or 3)
  --cleanup           Cleanup resources after demo
  --cleanup-full      Cleanup resources including namespace
  -h, --help          Show help message
```

### Examples

**Run all scenarios automatically:**
```bash
./demo_scenarios.java --auto
```

**Run only scenario 2 (NullPointer bug):**
```bash
./demo_scenarios.java --scenario 2
```

**Run with cleanup:**
```bash
./demo_scenarios.java --auto --cleanup
```

**Run specific scenario with full cleanup:**
```bash
./demo_scenarios.java --scenario 3 --cleanup-full
```

## What the Demo Does

### Scenario 1: Happy Path (Stable Deployment)

**Duration:** ~2 minutes

**What happens:**
1. Deploys a stable version of the Quarkus application
2. Argo Rollouts starts a canary deployment (20% traffic)
3. AI agent analyzes metrics from the canary
4. All health checks pass
5. AI agent approves the deployment
6. Canary is promoted to 100% (stable)

**Expected Output:**
```
✓ Scenario 1 completed in 120 seconds
AI Decision: PROCEED - Canary is healthy, promoting to stable
```

### Scenario 2: NullPointerException Bug

**Duration:** ~1.5 minutes

**What happens:**
1. Deploys a version with a NullPointerException bug
2. Argo Rollouts starts a canary deployment
3. Error rate increases in the canary pods
4. AI agent detects the bug through log analysis
5. AI agent triggers automatic rollback
6. AI agent creates a GitHub Pull Request with the fix

**Expected Output:**
```
✗ Scenario 2 completed in 90 seconds
AI Decision: ROLLBACK - Bug detected in canary deployment
→ Check GitHub for new PR with fix
```

**GitHub PR Contents:**
- Fix for the NullPointerException
- Updated code with null checks
- Explanation of the issue and solution

### Scenario 3: Memory Leak

**Duration:** ~2 minutes

**What happens:**
1. Deploys a version with a memory leak
2. Argo Rollouts starts a canary deployment
3. Memory usage increases over time in canary pods
4. AI agent detects performance degradation
5. AI agent triggers automatic rollback
6. AI agent creates a GitHub Issue with investigation steps

**Expected Output:**
```
✗ Scenario 3 completed in 120 seconds
AI Decision: ROLLBACK - Performance issue detected in canary
→ Check GitHub for new Issue with investigation steps
```

**GitHub Issue Contents:**
- Description of the memory leak symptoms
- Investigation steps and recommendations
- Suggested fixes and monitoring improvements

## Demo Script Features

### Interactive Mode (Default)

- Pauses between scenarios for observation
- Displays detailed information about each step
- Waits for user confirmation before proceeding

### Automatic Mode (`--auto`)

- Runs all scenarios without pauses
- Useful for CI/CD demonstrations
- 3-second delay between scenarios

### Colorful Output

The script uses ANSI colors for better readability:
- 🔵 **Blue** - Headers and section titles
- 🟢 **Green** - Success messages and positive outcomes
- 🔴 **Red** - Errors and rollback decisions
- 🟡 **Yellow** - Warnings and important notices
- 🔵 **Cyan** - Information and descriptions

### Real-time Monitoring

- Shows rollout progress in real-time
- Displays AnalysisRun results
- Shows current rollout status
- Monitors for GitHub activity

### Prerequisites Checking

Before running scenarios, the script verifies:
- kubectl is installed and configured
- kubectl argo rollouts plugin is available
- Kubernetes cluster is accessible
- Argo Rollouts CRD is installed
- Namespace exists or can be created

## Troubleshooting

### JBang Not Found

If you get "command not found: jbang":
```bash
# Ensure JBang is in your PATH
export PATH="$HOME/.jbang/bin:$PATH"

# Or reinstall JBang
curl -Ls https://sh.jbang.dev | bash -s - app setup
```

### Kubernetes Connection Issues

If the script can't connect to your cluster:
```bash
# Check your kubeconfig
kubectl cluster-info

# Verify current context
kubectl config current-context

# Switch context if needed
kubectl config use-context <context-name>
```

### Argo Rollouts Not Found

If Argo Rollouts CRD is missing:
```bash
# Install Argo Rollouts
kubectl create namespace argo-rollouts
kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml

# Verify installation
kubectl get crd rollouts.argoproj.io
```

### Rollout Stuck

If a rollout gets stuck:
```bash
# Check rollout status
kubectl argo rollouts get rollout quarkus-demo -n quarkus-demo

# Check analysis runs
kubectl get analysisrun -n quarkus-demo

# Abort rollout if needed
kubectl argo rollouts abort quarkus-demo -n quarkus-demo

# Cleanup and retry
./demo_scenarios.java --cleanup-full
```

## Architecture

The demo script uses:
- **JBang** - For zero-setup Java scripting
- **Quarkus** - For fast startup and low memory footprint
- **Fabric8 Kubernetes Client** - For Kubernetes API interactions
- **Picocli** - For command-line argument parsing

## Next Steps

After running the demo:

1. **Review GitHub Activity**
   - Check for new Pull Requests (Scenario 2)
   - Check for new Issues (Scenario 3)

2. **Explore Argo Rollouts Dashboard**
   ```bash
   kubectl argo rollouts dashboard
   ```

3. **View Application Dashboard**
   - Access the Quarkus demo application UI
   - Monitor real-time metrics and rollout progress

4. **Customize Scenarios**
   - Modify the Kustomize overlays in `workloads/quarkus-rollouts-demo/overlays/`
   - Adjust analysis templates for different thresholds
   - Create your own scenarios

## Related Documentation

- [Main README](../README.md) - Overall project documentation
- [Quarkus Demo App](../argo-rollouts-quarkus-demo/README.md) - Application details
- [Kubernetes Agent](../kubernetes-agent/README.md) - AI agent implementation
- [Argo Rollouts Documentation](https://argoproj.github.io/argo-rollouts/) - Official docs

## Support

For issues or questions:
- Open an issue in the GitHub repository
- Check the troubleshooting section above
- Review the Argo Rollouts documentation