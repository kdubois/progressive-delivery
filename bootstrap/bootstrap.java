
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String githubToken = null;
        String typesafeApiKey = null;
        String analysisSecondOpinionApiKey = null;
        String llmApiKey = null;

        if (runVault) {
            printHeader("Credentials");
            System.out.println("Credentials are written to Vault and synced to the cluster by the");
            System.out.println("Vault Secrets Operator. You need:");
            System.out.println("  - An AI API key (OpenAI, Gemini, or any OpenAI-compatible endpoint)");
            System.out.println("  - A GitHub personal access token with 'repo' scope");
            System.out.println("    https://github.com/settings/tokens");
            System.out.println();
            System.out.println("  Optional, read from the environment without prompting (empty disables");
            System.out.println("  the feature):");
            System.out.println("  - TYPESAFE_API_KEY - decision-model key for the analysis grader");
            System.out.println("  - ANALYSIS_SECOND_OPINION_API_KEY - key for the second opinion escalation tier");
            System.out.println("  - LLM_API_KEY - LLM key for the remediation sandbox (llm_api_key)");
            System.out.println();

            analysisApiKey = readEnvOrPromptSecret("ANALYSIS_API_KEY", "Analysis API key (e.g. sk-...): ", true);
            if (analysisApiKey.isBlank()) {
                printError("Analysis API key is required.");
                return 1;
            }

            githubToken = readEnvOrPromptSecret("GITHUB_TOKEN", "GitHub token (e.g. ghp_...): ", true);
            if (githubToken.isBlank()) {
                printError("GitHub token is required.");
                return 1;
            }
            typesafeApiKey = System.getenv().getOrDefault("TYPESAFE_API_KEY", "");
            analysisSecondOpinionApiKey = System.getenv().getOrDefault("ANALYSIS_SECOND_OPINION_API_KEY", "");
            llmApiKey = System.getenv().getOrDefault("LLM_API_KEY", "");
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

        // ── Argo CD sync tuning ──────────────────────────────────────────────
        argocdTuning(githubToken);

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
        vaultBootstrap(analysisApiKey, githubToken, typesafeApiKey, analysisSecondOpinionApiKey, llmApiKey);

        return 0;
    }

    // ── Argo CD sync tuning ─────────────────────────────────────────────────

    /**
     * Makes GitOps syncs fast enough for live demos on a fresh cluster:
     * a short git poll interval, and a GitHub webhook so pushes to this repo
     * trigger a sync immediately. Both are per-cluster state, so they are set
     * here rather than in the repo.
     *
     * <p>The webhook is repointed, not just created: these clusters are
     * short-lived, and the webhook left behind by a previous cluster keeps
     * pointing at its dead route while still looking healthy in the GitHub
     * repo settings. Creating a new one would not fix that, so an existing
     * hook is updated to this cluster's route and secret.
     */
    private void argocdTuning(String githubToken) throws Exception {
        String namespace = System.getenv().getOrDefault("NAMESPACE", "openshift-gitops");

        printStep("Setting Argo CD git poll interval to 15s...");
        exec("oc", "patch", "configmap", "argocd-cm", "-n", namespace,
            "-p", "{\"data\":{\"timeout.reconciliation\":\"15s\"}}");
        restartApplicationController(namespace);

        if (githubToken == null || githubToken.isBlank()) {
            printWarning("No GitHub token available, skipping the webhook (poll-only syncs).");
            return;
        }

        printStep("Configuring the GitHub webhook for instant syncs...");
        String route = execForOutput("oc", "get", "route", "openshift-gitops-server", "-n", namespace,
            "-o", "jsonpath={.spec.host}");
        if (route == null || route.isBlank()) {
            printWarning("Argo CD route not found, skipping the webhook.");
            return;
        }
        String secret = execForOutput("openssl", "rand", "-hex", "20");
        exec("oc", "patch", "secret", "argocd-secret", "-n", namespace,
            "-p", "{\"stringData\":{\"webhook.github.secret\":\"" + secret.trim() + "\"}}");

        String payload = String.format(
            "{\"name\":\"web\",\"active\":true,\"events\":[\"push\"],"
            + "\"config\":{\"url\":\"https://%s/api/webhook\",\"content_type\":\"json\",\"secret\":\"%s\"}}",
            route.trim(), secret.trim());
        try {
            long hookId = findWebhookId(githubGet(githubToken, "repos/" + githubRepo() + "/hooks"));
            if (hookId > 0) {
                githubPatch(githubToken, "repos/" + githubRepo() + "/hooks/" + hookId, payload);
                printSuccess("Webhook repointed at this cluster: pushes to " + githubRepo() + " sync Argo CD instantly.");
            } else {
                githubPost(githubToken, "repos/" + githubRepo() + "/hooks", payload);
                printSuccess("Webhook created: pushes to " + githubRepo() + " sync Argo CD instantly.");
            }
        } catch (Exception e) {
            // the webhook is an optimization over the git poll interval; a token
            // without webhook scope (e.g. a fine-grained PAT) must not block the
            // rest of the bootstrap
            printWarning("GitHub webhook not configured (" + e.getMessage() + "). "
                + "Syncs fall back to the git poll interval.");
        }
    }

    /**
     * The application controller reads the reconciliation timeout at startup.
     * On a re-run over a live cluster it must be restarted to pick the 15s
     * interval up; on a fresh cluster it has not started yet and reads the
     * patched value on first boot, so a missing controller is not an error.
     */
    private void restartApplicationController(String namespace) {
        try {
            String controller = execForOutput("oc", "get", "statefulset",
                "-l", "app.kubernetes.io/component=application-controller",
                "-o", "name", "-n", namespace);
            if (controller == null || controller.isBlank()) {
                System.out.println("  Controller not started yet — it will read the interval at first boot.");
                return;
            }
            exec("oc", "rollout", "restart", "statefulset",
                "-l", "app.kubernetes.io/component=application-controller", "-n", namespace);
        } catch (Exception e) {
            printWarning("Could not restart the application controller (" + e.getMessage()
                + "). The 15s interval applies after its next restart.");
        }
    }

    /**
     * The id of the first webhook whose URL targets an Argo CD
     * {@code /api/webhook} endpoint — typically the hook a previous
     * cluster's bootstrap registered — or 0 when none exists.
     */
    private long findWebhookId(String hooksJson) {
        Matcher m = Pattern.compile("\"id\":(\\d+)[^}]*?\"config\":\\{[^}]*?\"url\":\"[^\"]*/api/webhook\"")
            .matcher(hooksJson);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private String githubRepo() {
        // the repo this bootstrap lives in; override for forks
        return System.getenv().getOrDefault("GITHUB_REPO", "kdubois/progressive-delivery");
    }

    /** Runs a command and returns its stdout, failing on a non-zero exit. */
    private String execForOutput(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (exit " + exit + "): " + String.join(" ", command));
        }
        return output;
    }

    private String githubSend(String method, String token, String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/" + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("GitHub " + method + " " + path + " failed: "
                + resp.statusCode() + " " + resp.body());
        }
        return resp.body();
    }

    private String githubGet(String token, String path) throws Exception {
        return githubSend("GET", token, path, null);
    }

    private void githubPatch(String token, String path, String body) throws Exception {
        githubSend("PATCH", token, path, body);
    }

    private void githubPost(String token, String path, String body) throws Exception {
        githubSend("POST", token, path, body);
    }

    // ── Vault bootstrap ──────────────────────────────────────────────────────

    private void vaultBootstrap(String analysisApiKey, String githubToken, String typesafeApiKey,
            String analysisSecondOpinionApiKey, String llmApiKey) throws Exception {
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

            printStep("Writing agent credentials to Vault KV...");
            // all keys the kubernetes-agent secret needs; the Vault Secrets Operator
            // replaces the whole secret on sync, so every key must be present here.
            // Empty values disable the optional features (grader, escalation).
            String agentPayload = String.format(
                "{\"data\":{\"analysis_api_key\":\"%s\",\"typesafe_api_key\":\"%s\",\"analysis_second_opinion_api_key\":\"%s\"}}",
                analysisApiKey, typesafeApiKey, analysisSecondOpinionApiKey);
            vaultPost(vaultAddr, "secret/data/argo-rollouts/kubernetes-agent", agentPayload, false);

            printStep("Writing remediation credentials to Vault KV...");
            String remediationPayload = String.format(
                "{\"data\":{\"github_token\":\"%s\",\"llm_api_key\":\"%s\"}}",
                githubToken, llmApiKey);
            vaultPost(vaultAddr, "secret/data/argo-rollouts/remediation-agent", remediationPayload, false);

            printStep("Enabling Kubernetes auth method...");
            vaultPost(vaultAddr, "sys/auth/kubernetes", "{\"type\":\"kubernetes\"}", true);

            printStep("Configuring Kubernetes auth...");
            vaultPost(vaultAddr, "auth/kubernetes/config",
                "{\"kubernetes_host\":\"https://kubernetes.default.svc:443\"}", false);

            printStep("Writing policies and auth roles...");
            vaultPut(vaultAddr, "sys/policies/acl/kubernetes-agent",
                "{\"policy\":\"path \\\"secret/data/argo-rollouts/kubernetes-agent\\\" { capabilities = [\\\"read\\\"] }\"}");
            vaultPost(vaultAddr, "auth/kubernetes/role/kubernetes-agent",
                String.format("{\"bound_service_account_names\":[\"kubernetes-agent\"]," +
                    "\"bound_service_account_namespaces\":[\"%s\"]," +
                    "\"policies\":[\"kubernetes-agent\"],\"ttl\":\"1h\"}", namespace), false);
            // the remediation syncs authenticate as the remediation-agent SA in
            // openshift-gitops (service deployment) and the default SA in the
            // sandbox namespace (sandbox pods)
            vaultPut(vaultAddr, "sys/policies/acl/remediation-agent",
                "{\"policy\":\"path \\\"secret/data/argo-rollouts/remediation-agent\\\" { capabilities = [\\\"read\\\"] }\"}");
            vaultPost(vaultAddr, "auth/kubernetes/role/remediation-agent",
                String.format("{\"bound_service_account_names\":[\"remediation-agent\",\"default\"]," +
                    "\"bound_service_account_namespaces\":[\"%s\",\"remediation\"]," +
                    "\"policies\":[\"remediation-agent\"],\"ttl\":\"1h\"}", namespace), false);

            System.out.println();
            System.out.println(GREEN + "============================================================" + RESET);
            printSuccess("Vault bootstrap complete.");
            System.out.println();
            System.out.println("  Vault KV:   secret/argo-rollouts/kubernetes-agent");
            System.out.println("  Vault KV:   secret/argo-rollouts/remediation-agent");
            System.out.println("  Auth roles: kubernetes-agent, remediation-agent");
            System.out.println();
            System.out.println("  The Vault Secrets Operator will sync credentials to K8s");
            System.out.println("  Secrets 'kubernetes-agent' and 'remediation-agent'.");
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
