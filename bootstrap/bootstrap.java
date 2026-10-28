
///usr/bin/env bash -c 'command -v jbang >/dev/null 2>&1 || { echo "Bootstrapping JBang..." >&2; curl -Ls https://sh.jbang.dev | bash -s - app setup --quiet ; export PATH="$HOME/.jbang/bin:$PATH"; }; exec jbang "$0" "$@"' "$0" "$@"; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "bootstrap",
    mixinStandardHelpOptions = true,
    description = "Bootstrap the progressive delivery stack on an OpenShift cluster."
)
public class bootstrap implements Callable<Integer> {

    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String CYAN   = "\u001B[36m";
    private static final String RESET  = "\u001B[0m";

    @Option(
        names = {"--overlay"},
        description = "Kustomize overlay to apply: 'default' (installs GitOps operator) or 'existing-argocd' (reuses an existing Argo CD instance). Default: default.",
        defaultValue = "default"
    )
    String overlay;

    @Option(
        names = {"--skip-vault"},
        description = "Skip Vault bootstrap. Use this when managing secrets manually via a plain Kubernetes Secret instead of Vault + VSO."
    )
    boolean skipVault;

    @Option(
        names = {"--vault-only"},
        description = "Skip the stack deployment and only run the Vault bootstrap. Use this to repopulate Vault after a pod restart."
    )
    boolean vaultOnly;

    private Path bootstrapDir;

    public static void main(String[] args) {
        System.exit(new CommandLine(new bootstrap()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        // Resolve paths relative to the script's own directory.
        // jbang.source.dir is set by JBang to the directory containing this script;
        // fall back to a bootstrap/ subdirectory of user.dir for IDE runs.
        String sourceDir = System.getProperty("jbang.source.dir");
        bootstrapDir = sourceDir != null
            ? Paths.get(sourceDir)
            : Paths.get(System.getProperty("user.dir")).resolve("bootstrap");

        boolean runVault = !skipVault;

        // ── Collect credentials upfront ─────────────────────────────────────
        // Prompt before any cluster work so missing values fail fast.
        String analysisApiKey = null;
        String remediationApiKey = null;
        String githubToken = null;

        if (runVault) {
            printHeader("Credentials");
            System.out.println("Credentials are written to Vault and synced to the cluster by the");
            System.out.println("Vault Secrets Operator. You need:");
            System.out.println("  - An AI API key (OpenAI, Gemini, or any OpenAI-compatible endpoint)");
            System.out.println("  - A GitHub personal access token with 'repo' scope");
            System.out.println("    https://github.com/settings/tokens");
            System.out.println();

            analysisApiKey = readEnvOrPromptSecret("ANALYSIS_API_KEY", "Analysis API key (e.g. sk-...): ", true);
            if (analysisApiKey.isBlank()) {
                printError("Analysis API key is required.");
                return 1;
            }

            remediationApiKey = readEnvOrPromptSecret("REMEDIATION_API_KEY", "Remediation API key (leave blank to reuse analysis key): ", false);
            if (remediationApiKey.isBlank()) {
                remediationApiKey = analysisApiKey;
            }

            githubToken = readEnvOrPromptSecret("GITHUB_TOKEN", "GitHub token (e.g. ghp_...): ", true);
            if (githubToken.isBlank()) {
                printError("GitHub token is required.");
                return 1;
            }
            System.out.println();
        }

        // ── Deploy the stack ─────────────────────────────────────────────────
        if (!vaultOnly) {
            Path overlayPath = bootstrapDir.resolve("overlays").resolve(overlay);
            if (!overlayPath.toFile().isDirectory()) {
                printError("Overlay '" + overlay + "' not found at " + overlayPath);
                printError("Valid overlays: default, existing-argocd");
                return 1;
            }

            printHeader("Deploying stack (overlay: " + overlay + ")");

            if (overlay.equals("default")) {
                printStep("Phase 1: installing operators...");
                runWithRetry(List.of("oc", "apply", "-k", bootstrapDir.resolve("base").toString()));

                printStep("Phase 2: waiting for Argo CD CRDs...");
                for (String crd : List.of("argocds.argoproj.io", "appprojects.argoproj.io", "applicationsets.argoproj.io")) {
                    waitForCrd(crd);
                }

                printStep("Phase 3: applying Argo CD resources...");
            }

            runWithRetry(List.of("oc", "apply", "-k", overlayPath.toString()));
            printSuccess("Stack deployed.");
        }

        // ── Vault bootstrap ──────────────────────────────────────────────────
        if (!runVault) {
            System.out.println();
            printWarning("Vault bootstrap skipped (--skip-vault).");
            printWarning("Apply credentials manually:");
            printWarning("  cp system/kubernetes-agent/secret.yaml.template system/kubernetes-agent/secret.yaml");
            printWarning("  # fill in your credentials, then:");
            printWarning("  oc apply -f system/kubernetes-agent/secret.yaml");
            return 0;
        }

        printHeader("Vault bootstrap");
        vaultBootstrap(analysisApiKey, remediationApiKey, githubToken);

        return 0;
    }

    // ── Vault bootstrap ──────────────────────────────────────────────────────

    private void vaultBootstrap(String analysisApiKey, String remediationApiKey, String githubToken) throws Exception {
        String namespace = System.getenv().getOrDefault("NAMESPACE", "openshift-gitops");
        int vaultPort = Integer.parseInt(System.getenv().getOrDefault("VAULT_PORT", "8200"));
        String vaultAddr = "http://127.0.0.1:" + vaultPort;

        printStep("Waiting for Vault pod to be ready (Argo CD may still be syncing)...");
        // oc wait fails immediately if no matching pods exist yet, so poll until
        // at least one appears before handing off to oc wait.
        while (true) {
            ProcessBuilder pb = new ProcessBuilder(
                "oc", "get", "pod", "-l", "app.kubernetes.io/name=vault", "-n", namespace);
            pb.redirectErrorStream(true);
            String output = new String(pb.start().getInputStream().readAllBytes()).trim();
            if (!output.isBlank() && !output.equals("No resources found in " + namespace + " namespace.")) break;
            System.out.println("    no Vault pod yet, waiting...");
            Thread.sleep(15_000);
        }
        exec("oc", "wait", "pod", "-l", "app.kubernetes.io/name=vault",
            "-n", namespace, "--for=condition=ready", "--timeout=300s");

        printStep("Port-forwarding to Vault on port " + vaultPort + "...");
        ProcessBuilder pfPb = new ProcessBuilder(
            "oc", "port-forward", "svc/vault", vaultPort + ":8200", "-n", namespace);
        pfPb.redirectErrorStream(true);
        Process pfProcess = pfPb.start();

        try {
            waitForVault(vaultAddr);

            printStep("Enabling KV v2 secrets engine...");
            vaultPost(vaultAddr, "sys/mounts/secret", "{\"type\":\"kv\",\"options\":{\"version\":\"2\"}}", true);

            printStep("Writing credentials to Vault KV...");
            String secretPayload = String.format(
                "{\"data\":{\"analysis_api_key\":\"%s\",\"remediation_api_key\":\"%s\",\"github_token\":\"%s\"}}",
                analysisApiKey, remediationApiKey, githubToken);
            vaultPost(vaultAddr, "secret/data/argo-rollouts/kubernetes-agent", secretPayload, false);

            printStep("Enabling Kubernetes auth method...");
            vaultPost(vaultAddr, "sys/auth/kubernetes", "{\"type\":\"kubernetes\"}", true);

            printStep("Configuring Kubernetes auth...");
            vaultPost(vaultAddr, "auth/kubernetes/config",
                "{\"kubernetes_host\":\"https://kubernetes.default.svc:443\"}", false);

            printStep("Writing policy and auth role...");
            vaultPut(vaultAddr, "sys/policies/acl/kubernetes-agent",
                "{\"policy\":\"path \\\"secret/data/argo-rollouts/kubernetes-agent\\\" { capabilities = [\\\"read\\\"] }\"}");
            vaultPost(vaultAddr, "auth/kubernetes/role/kubernetes-agent",
                String.format("{\"bound_service_account_names\":[\"kubernetes-agent\"]," +
                    "\"bound_service_account_namespaces\":[\"%s\"]," +
                    "\"policies\":[\"kubernetes-agent\"],\"ttl\":\"1h\"}", namespace), false);

            System.out.println();
            System.out.println(GREEN + "============================================================" + RESET);
            printSuccess("Vault bootstrap complete.");
            System.out.println();
            System.out.println("  Vault KV:   secret/argo-rollouts/kubernetes-agent");
            System.out.println("  Auth role:  kubernetes-agent (SA: kubernetes-agent, NS: " + namespace + ")");
            System.out.println();
            System.out.println("  The Vault Secrets Operator will sync credentials to K8s");
            System.out.println("  Secret 'kubernetes-agent' in namespace '" + namespace + "'.");
            System.out.println();
            System.out.println("  Verify with: oc get secret kubernetes-agent -n " + namespace);
            System.out.println(GREEN + "============================================================" + RESET);
        } finally {
            pfProcess.destroy();
        }
    }

    private void waitForVault(String vaultAddr) throws Exception {
        printStep("Waiting for Vault to be reachable...");
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 15; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(vaultAddr + "/v1/sys/health"))
                    .GET().build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() < 500) {
                    printSuccess("Connected to Vault at " + vaultAddr);
                    return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }
        throw new RuntimeException("Cannot reach Vault at " + vaultAddr);
    }

    private void vaultPost(String vaultAddr, String path, String body, boolean ignoreConflict) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + "/v1/" + path))
            .header("X-Vault-Token", "root")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (!ignoreConflict && resp.statusCode() >= 400) {
            throw new RuntimeException("Vault POST " + path + " failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    private void vaultPut(String vaultAddr, String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + "/v1/" + path))
            .header("X-Vault-Token", "root")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Vault PUT " + path + " failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    // ── Cluster helpers ──────────────────────────────────────────────────────

    private void waitForCrd(String crd) throws Exception {
        System.out.println("    waiting for CRD " + crd + "...");
        while (true) {
            ProcessBuilder pb = new ProcessBuilder("oc", "get", "crd", crd);
            pb.redirectErrorStream(true);
            if (pb.start().waitFor() == 0) break;
            Thread.sleep(10_000);
        }
        exec("oc", "wait", "crd/" + crd, "--for=condition=Established", "--timeout=300s");
    }

    private void runWithRetry(List<String> command) throws Exception {
        while (true) {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            if (pb.start().waitFor() == 0) return;
            System.out.println(YELLOW + "    waiting for resources to be accepted..." + RESET);
            Thread.sleep(15_000);
        }
    }

    private void exec(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (exit " + exit + "): " + String.join(" ", command));
        }
    }

    // ── Credential prompt ────────────────────────────────────────────────────

    private String readEnvOrPromptSecret(String envVar, String prompt, boolean required) throws IOException {
        String existing = System.getenv(envVar);
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        if (existing != null && !existing.isBlank()) {
            System.out.print(prompt + "[" + envVar + " already set, press Enter to use it]: ");
            String input = reader.readLine();
            return (input == null || input.isBlank()) ? existing : input.trim();
        }
        System.out.print(prompt);
        String line = reader.readLine();
        return line == null ? "" : line.trim();
    }

    // ── Output helpers ───────────────────────────────────────────────────────

    private void printHeader(String message) {
        System.out.println();
        System.out.println(CYAN + "==> " + message + RESET);
    }

    private void printStep(String message) {
        System.out.println("--> " + message);
    }

    private void printSuccess(String message) {
        System.out.println(GREEN + "✓ " + message + RESET);
    }

    private void printWarning(String message) {
        System.out.println(YELLOW + "⚠ " + message + RESET);
    }

    private void printError(String message) {
        System.err.println(RED + "✗ " + message + RESET);
    }
}
