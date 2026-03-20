
///usr/bin/env bash -c 'command -v jbang >/dev/null 2>&1 || { echo "Bootstrapping JBang..." >&2; curl -Ls https://sh.jbang.dev | bash -s - app setup --quiet ; export PATH="$HOME/.jbang/bin:$PATH"; }; exec jbang "$0" "$@"' "$0" "$@"; exit $?
//JAVA 21+
//DEPS io.quarkus.platform:quarkus-bom:3.17.5@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-kubernetes-client
//DEPS io.fabric8:kubernetes-client:6.13.4
//Q:CONFIG quarkus.banner.enabled=false
//Q:CONFIG quarkus.log.level=WARN

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@QuarkusMain
@Command(name = "demo-scenarios", mixinStandardHelpOptions = true, 
         description = "Automate demonstration of Argo Rollouts AI-powered progressive delivery scenarios")
public class demo_scenarios implements Callable<Integer>, QuarkusApplication {

    // ANSI Colors
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String RESET = "\u001B[0m";

    // Configuration
    private static final String NAMESPACE = "quarkus-demo";
    private static final String ROLLOUT_NAME = "quarkus-demo";
    
    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    CommandLine.IFactory factory;

    @Option(names = {"--auto"}, description = "Run in automatic mode (no pauses)")
    boolean autoMode = false;

    @Option(names = {"--scenario"}, description = "Run only scenario N (1, 2, or 3)")
    Integer scenario;

    @Option(names = {"--cleanup"}, description = "Cleanup resources after demo")
    boolean cleanup = false;

    @Option(names = {"--cleanup-full"}, description = "Cleanup resources including namespace")
    boolean cleanupFull = false;

    private Path scriptDir;
    private Path overlayBase;

    @Override
    public int run(String... args) {
        return new CommandLine(this, factory).execute(args);
    }

    @Override
    public Integer call() throws Exception {
        scriptDir = Paths.get(System.getProperty("user.dir"));
        overlayBase = scriptDir.resolve("workloads/quarkus-rollouts-demo/overlays");

        printHeader("Argo Rollouts AI-Powered Progressive Delivery Demo");
        
        printInfo("This demo showcases:");
        System.out.println("  • Automated canary deployments with Argo Rollouts");
        System.out.println("  • AI-powered analysis of deployment health");
        System.out.println("  • Automatic rollback on issues");
        System.out.println("  • GitHub integration for bug fixes and issues");
        System.out.println();

        if (!checkPrerequisites()) {
            return 1;
        }

        try {
            if (scenario != null) {
                runScenario(scenario);
            } else {
                demoScenario1();
                demoScenario2();
                demoScenario3();
            }

            if (cleanup || cleanupFull) {
                cleanupResources();
            }

            printHeader("Demo Complete!");
            printSuccess("All scenarios demonstrated successfully!");
            System.out.println();
            printInfo("Summary:");
            System.out.println("  • Scenario 1: Stable deployment - " + GREEN + "SUCCESS" + RESET);
            System.out.println("  • Scenario 2: Bug detection - " + RED + "ROLLBACK" + RESET + " + PR created");
            System.out.println("  • Scenario 3: Performance issue - " + RED + "ROLLBACK" + RESET + " + Issue created");
            System.out.println();
            printWarning("Next Steps:");
            System.out.println("  1. Check your GitHub repository for PRs and Issues");
            System.out.println("  2. Review the AI analysis results");
            System.out.println("  3. Explore the Argo Rollouts dashboard");
            System.out.println();

            return 0;
        } catch (Exception e) {
            printError("Demo failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void runScenario(int num) throws Exception {
        switch (num) {
            case 1 -> demoScenario1();
            case 2 -> demoScenario2();
            case 3 -> demoScenario3();
            default -> throw new IllegalArgumentException("Invalid scenario: " + num);
        }
    }

    private boolean checkPrerequisites() {
        printHeader("Checking Prerequisites");
        boolean allGood = true;

        // Check kubectl
        if (commandExists("kubectl")) {
            printSuccess("kubectl is installed");
        } else {
            printError("kubectl is not installed");
            allGood = false;
        }

        // Check kubectl argo rollouts plugin
        if (commandExists("kubectl-argo-rollouts")) {
            printSuccess("kubectl argo rollouts plugin is installed");
        } else {
            printError("kubectl argo rollouts plugin is not installed");
            printWarning("Install with: curl -LO https://github.com/argoproj/argo-rollouts/releases/latest/download/kubectl-argo-rollouts-linux-amd64");
            allGood = false;
        }

        // Check cluster access
        try {
            String version = kubernetesClient.getKubernetesVersion().getGitVersion();
            printSuccess("Kubernetes cluster is accessible (version: " + version + ")");
            String context = kubernetesClient.getConfiguration().getCurrentContext().getName();
            printInfo("Current context: " + context);
        } catch (Exception e) {
            printError("Cannot access Kubernetes cluster: " + e.getMessage());
            allGood = false;
        }

        // Check if namespace exists
        try {
            Namespace ns = kubernetesClient.namespaces().withName(NAMESPACE).get();
            if (ns != null) {
                printSuccess("Namespace '" + NAMESPACE + "' exists");
            } else {
                printWarning("Namespace '" + NAMESPACE + "' does not exist (will be created)");
            }
        } catch (Exception e) {
            printWarning("Could not check namespace: " + e.getMessage());
        }

        // Check if Argo Rollouts CRD is installed
        try {
            boolean hasCrd = kubernetesClient.apiextensions().v1().customResourceDefinitions()
                .list().getItems().stream()
                .anyMatch(crd -> crd.getMetadata().getName().equals("rollouts.argoproj.io"));
            
            if (hasCrd) {
                printSuccess("Argo Rollouts CRD is installed");
            } else {
                printError("Argo Rollouts is not installed in the cluster");
                printWarning("Install with: kubectl create namespace argo-rollouts && kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml");
                allGood = false;
            }
        } catch (Exception e) {
            printWarning("Could not check Argo Rollouts CRD: " + e.getMessage());
        }

        if (!allGood) {
            printError("Prerequisites check failed. Please install missing components.");
            return false;
        }

        printSuccess("All prerequisites satisfied!");
        return true;
    }

    private void demoScenario1() throws Exception {
        printHeader("Scenario 1: Happy Path (Stable Deployment)");
        printInfo("Description:");
        System.out.println("  • Deploys a stable version of the application");
        System.out.println("  • All health checks pass");
        System.out.println("  • AI analysis confirms deployment is healthy");
        System.out.println("  • Canary is promoted to stable");
        System.out.println();
        System.out.println(CYAN + "Expected Duration:" + RESET + " ~2 minutes");
        System.out.println(CYAN + "Expected Outcome:" + RESET + " " + GREEN + "SUCCESS" + RESET + " - Rollout completes successfully");

        waitForUser();

        Instant start = Instant.now();
        deployScenario("scenario-1-stable");
        watchRollout();
        showRolloutStatus();
        showAnalysis();
        long duration = Duration.between(start, Instant.now()).getSeconds();

        System.out.println();
        printSuccess("Scenario 1 completed in " + duration + " seconds");
        System.out.println(GREEN + "AI Decision: PROCEED" + RESET + " - Canary is healthy, promoting to stable");
        System.out.println();
    }

    private void demoScenario2() throws Exception {
        printHeader("Scenario 2: NullPointerException Bug");
        printInfo("Description:");
        System.out.println("  • Deploys a version with a NullPointerException bug");
        System.out.println("  • Error rate increases in canary");
        System.out.println("  • AI analysis detects the bug");
        System.out.println("  • Rollout is automatically aborted");
        System.out.println("  • AI creates a GitHub PR with the fix");
        System.out.println();
        System.out.println(CYAN + "Expected Duration:" + RESET + " ~1.5 minutes");
        System.out.println(CYAN + "Expected Outcome:" + RESET + " " + RED + "ROLLBACK" + RESET + " - Bug detected, rollout aborted");

        waitForUser();

        Instant start = Instant.now();
        deployScenario("scenario-2-null-pointer");
        watchRollout();
        showRolloutStatus();
        showAnalysis();
        long duration = Duration.between(start, Instant.now()).getSeconds();

        System.out.println();
        printError("Scenario 2 completed in " + duration + " seconds");
        System.out.println(RED + "AI Decision: ROLLBACK" + RESET + " - Bug detected in canary deployment");
        checkGitHubActivity("pr");
    }

    private void demoScenario3() throws Exception {
        printHeader("Scenario 3: Memory Leak");
        printInfo("Description:");
        System.out.println("  • Deploys a version with a memory leak");
        System.out.println("  • Memory usage increases over time");
        System.out.println("  • AI analysis detects performance degradation");
        System.out.println("  • Rollout is automatically aborted");
        System.out.println("  • AI creates a GitHub Issue with investigation steps");
        System.out.println();
        System.out.println(CYAN + "Expected Duration:" + RESET + " ~2 minutes");
        System.out.println(CYAN + "Expected Outcome:" + RESET + " " + RED + "ROLLBACK" + RESET + " - Performance issue detected");

        waitForUser();

        Instant start = Instant.now();
        deployScenario("scenario-3-memory-leak");
        watchRollout();
        showRolloutStatus();
        showAnalysis();
        long duration = Duration.between(start, Instant.now()).getSeconds();

        System.out.println();
        printError("Scenario 3 completed in " + duration + " seconds");
        System.out.println(RED + "AI Decision: ROLLBACK" + RESET + " - Performance issue detected in canary");
        checkGitHubActivity("issue");
    }

    private void deployScenario(String scenario) throws Exception {
        Path overlayPath = overlayBase.resolve(scenario);
        
        System.out.println();
        printInfo("Deploying scenario: " + scenario);
        printInfo("Using overlay: " + overlayPath);

        if (!overlayPath.toFile().exists()) {
            throw new IllegalArgumentException("Overlay directory not found: " + overlayPath);
        }

        // Apply the kustomization using kubectl
        ProcessBuilder pb = new ProcessBuilder("kubectl", "apply", "-k", overlayPath.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("  " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode == 0) {
            printSuccess("Scenario deployed successfully");
        } else {
            throw new RuntimeException("Failed to deploy scenario (exit code: " + exitCode + ")");
        }

        // Wait for resources to be created
        Thread.sleep(2000);
    }

    private void watchRollout() throws Exception {
        System.out.println();
        printInfo("Watching rollout progress...");
        printInfo("This will take approximately 2-3 minutes...");
        System.out.println();

        // Start kubectl argo rollouts watch in background
        ProcessBuilder pb = new ProcessBuilder("kubectl", "argo", "rollouts", "get", "rollout", 
                                              ROLLOUT_NAME, "-n", NAMESPACE, "--watch");
        pb.redirectErrorStream(true);
        Process watchProcess = pb.start();

        // Monitor in a separate thread
        Thread watchThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(watchProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                // Ignore
            }
        });
        watchThread.start();

        // Wait for rollout to complete or timeout
        int timeout = 180; // 3 minutes
        int elapsed = 0;
        int interval = 5;

        while (elapsed < timeout) {
            try {
                // Check rollout status using Kubernetes client
                var rollout = kubernetesClient.genericKubernetesResources("argoproj.io/v1alpha1", "Rollout")
                    .inNamespace(NAMESPACE)
                    .withName(ROLLOUT_NAME)
                    .get();

                if (rollout != null) {
                    var status = rollout.get("status");
                    if (status instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        var statusMap = (java.util.Map<String, Object>) status;
                        String phase = (String) statusMap.get("phase");
                        
                        if ("Healthy".equals(phase) || "Degraded".equals(phase)) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // Continue waiting
            }

            Thread.sleep(interval * 1000L);
            elapsed += interval;
        }

        // Stop the watch process
        watchProcess.destroy();
        watchThread.interrupt();
        
        System.out.println();
    }

    private void showRolloutStatus() throws Exception {
        System.out.println();
        printInfo("Current Rollout Status:");
        
        ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "rollout", ROLLOUT_NAME, 
                                              "-n", NAMESPACE, "-o", "wide");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        
        process.waitFor();
        System.out.println();
    }

    private void showAnalysis() throws Exception {
        System.out.println();
        printHeader("Analysis Results");

        ProcessBuilder pb = new ProcessBuilder("kubectl", "get", "analysisrun", 
                                              "-n", NAMESPACE, "-o", "wide");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        process.waitFor();
        System.out.println();
    }

    private void checkGitHubActivity(String type) {
        System.out.println();
        printHeader("GitHub Integration Check");

        if ("pr".equals(type)) {
            printInfo("Expected: AI agent should create a Pull Request with bug fix");
            printWarning("→ Check your GitHub repository for a new PR");
            printInfo("PR should contain:");
            System.out.println("  • Fix for the NullPointerException");
            System.out.println("  • Updated code with null checks");
            System.out.println("  • Explanation of the issue");
        } else if ("issue".equals(type)) {
            printInfo("Expected: AI agent should create a GitHub Issue");
            printWarning("→ Check your GitHub repository for a new Issue");
            printInfo("Issue should contain:");
            System.out.println("  • Description of the memory leak");
            System.out.println("  • Investigation steps");
            System.out.println("  • Recommendations for fixing");
        }
        System.out.println();
    }

    private void cleanupResources() throws Exception {
        printHeader("Cleaning Up Resources");

        printInfo("Deleting rollout and related resources...");
        executeCommand("kubectl", "delete", "rollout", ROLLOUT_NAME, "-n", NAMESPACE, "--ignore-not-found=true");

        printInfo("Deleting services...");
        executeCommand("kubectl", "delete", "service", "-n", NAMESPACE, "-l", "app=quarkus-demo", "--ignore-not-found=true");

        printInfo("Deleting analysis runs...");
        executeCommand("kubectl", "delete", "analysisrun", "-n", NAMESPACE, "--all", "--ignore-not-found=true");

        printInfo("Deleting analysis templates...");
        executeCommand("kubectl", "delete", "analysistemplate", "-n", NAMESPACE, "--all", "--ignore-not-found=true");

        if (cleanupFull) {
            printWarning("Deleting namespace...");
            executeCommand("kubectl", "delete", "namespace", NAMESPACE, "--ignore-not-found=true");
        }

        printSuccess("Cleanup complete!");
    }

    private void executeCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    private void waitForUser() throws IOException {
        if (!autoMode) {
            System.out.println();
            System.out.println(YELLOW + "Press Enter to continue..." + RESET);
            System.in.read();
        } else {
            System.out.println(CYAN + "Auto mode: continuing in 3 seconds..." + RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean commandExists(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // Utility methods for colored output
    private void printHeader(String message) {
        System.out.println();
        System.out.println(BLUE + "========================================" + RESET);
        System.out.println(BLUE + message + RESET);
        System.out.println(BLUE + "========================================" + RESET);
        System.out.println();
    }

    private void printSuccess(String message) {
        System.out.println(GREEN + "✓ " + message + RESET);
    }

    private void printError(String message) {
        System.out.println(RED + "✗ " + message + RESET);
    }

    private void printWarning(String message) {
        System.out.println(YELLOW + "⚠ " + message + RESET);
    }

    private void printInfo(String message) {
        System.out.println(CYAN + "ℹ " + message + RESET);
    }
}
