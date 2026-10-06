/*
 * AZURE CONTAINER APPS — GESTOR DE CICLO DE VIDA (INTERACTIVO)
 * Multiplataforma (Windows · macOS · Linux)
 *
 * Port a Java de script_az.py (v2.1.0, Luciano Mengarelli), el script del proyecto de
 * referencia de la catedra, adaptado a este proyecto (puerto 8080, imagen parcial-ing-soft,
 * api-version 2026-07-01 para parar/iniciar).
 *
 * Gestiona el ciclo de vida de la app en Azure Container Apps:
 *   1) Build local + push al ACR (con telemetria OCI)
 *   2) Deploy en Azure Container Apps (perfil minimo 0.25 CPU / 0.5Gi)
 *   3) Parar / Iniciar, Listar y Eliminar instancias
 *   4) Pull de la imagen desde el ACR
 *   5) Logs en tiempo real
 *
 * El ACR existe solo mientras la app esta levantada (se borra al apagar para no generar costos):
 * las opciones 1, 2, 3b y 4 lo necesitan y avisan si no esta (correr el workflow "Levantar").
 *
 * Requisitos: JDK 25, Docker Desktop en ejecucion, Azure CLI (az) y un .env con las
 * credenciales del Service Principal (ver .env.example).
 *
 * Uso (sin compilar, Java ejecuta el archivo directo):
 *   java ScriptAz.java              # usa el .env de la carpeta
 *   java ScriptAz.java .env.prod    # otro archivo
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ScriptAz {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    static final String AZ_CMD = resolveAz();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            OUT.println();
            OUT.println(Color.error("Error inesperado: " + e));
            e.printStackTrace(OUT);
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------------
    // Resolucion del ejecutable de Azure CLI
    // ---------------------------------------------------------------------------
    // En Windows "az" es un az.cmd: Java no lo encuentra si se lo pide como "az", por eso se
    // busca la ruta de instalacion estandar (MSI / Homebrew) y despues el PATH.
    static String resolveAz() {
        List<String> candidatos = List.of(
                "C:\\Program Files\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd",
                "C:\\Program Files (x86)\\Microsoft SDKs\\Azure\\CLI2\\wbin\\az.cmd",
                "/opt/homebrew/bin/az",
                "/usr/local/bin/az");
        for (String c : candidatos) {
            if (Files.isRegularFile(Path.of(c))) {
                return c;
            }
        }
        String path = System.getenv().getOrDefault("PATH", "");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String nombre : WINDOWS ? List.of("az.cmd", "az.exe") : List.of("az")) {
                Path p = Path.of(dir, nombre);
                if (Files.isRegularFile(p)) {
                    return p.toString();
                }
            }
        }
        return "az";
    }

    static String osInfo() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }

    // ---------------------------------------------------------------------------
    // Colores ANSI y logging
    // ---------------------------------------------------------------------------
    static final class Color {
        static final String RESET = "\033[0m";
        static final String BOLD = "\033[1m";
        static final String DIM = "\033[2m";
        static final String RED = "\033[91m";
        static final String GREEN = "\033[92m";
        static final String YELLOW = "\033[93m";
        static final String BLUE = "\033[94m";
        static final String MAGENTA = "\033[95m";
        static final String CYAN = "\033[96m";

        static String ok(String t) { return GREEN + t + RESET; }
        static String warn(String t) { return YELLOW + t + RESET; }
        static String error(String t) { return RED + t + RESET; }
        static String info(String t) { return CYAN + t + RESET; }
        static String header(String t) { return BOLD + BLUE + t + RESET; }
        static String title(String t) { return BOLD + MAGENTA + t + RESET; }
    }

    static final String BANNER = """

              ╔═════════════════════════════════════════════════════════════╗
              ║    AZURE CONTAINER APPS — Gestor de Ciclo de Vida (Java)    ║
              ║                  WINDOWS · macOS · LINUX                    ║
              ╚═════════════════════════════════════════════════════════════╝
            """;

    static void logOk(String m) { OUT.println("  " + Color.ok("✓") + " " + m); }
    static void logWarn(String m) { OUT.println("  " + Color.warn("⚠") + " " + m); }
    static void logErr(String m) { OUT.println("  " + Color.error("✗") + " " + m); }
    static void logInfo(String m) { OUT.println("  " + Color.info("→") + " " + m); }
    static void logDetail(String k, String v) { OUT.println("  " + Color.DIM + k + ":" + Color.RESET + " " + v); }

    static void section(String title) {
        OUT.println();
        OUT.println(Color.header("── " + title + " " + "─".repeat(Math.max(6, 54 - title.length()))));
    }

    /** Lee una linea de la consola. Si se cierra la entrada (EOF), termina como Ctrl+C. */
    static String readLine(String prompt) {
        OUT.print(prompt);
        OUT.flush();
        try {
            String line = IN.readLine();
            if (line == null) {
                OUT.println();
                System.exit(130);
            }
            return line.strip();
        } catch (IOException e) {
            System.exit(130);
            return "";
        }
    }

    static boolean confirm(String prompt) {
        while (true) {
            String a = readLine(Color.info("?") + " " + prompt + " " + Color.DIM + "(s/n)" + Color.RESET + ": ")
                    .toLowerCase(Locale.ROOT);
            if (List.of("s", "si", "sí", "y", "yes").contains(a)) {
                return true;
            }
            if (List.of("n", "no").contains(a)) {
                return false;
            }
            OUT.println("  " + Color.warn("Respondé con s o n"));
        }
    }

    // ---------------------------------------------------------------------------
    // Ejecucion de comandos del sistema
    // ---------------------------------------------------------------------------
    record Result(int code, String out) { }

    static List<String> resolve(List<String> cmd) {
        if (!cmd.isEmpty() && cmd.get(0).equals("az")) {
            List<String> r = new ArrayList<>(cmd);
            r.set(0, AZ_CMD);
            return r;
        }
        return cmd;
    }

    /** Ejecuta un comando y captura su stdout. 127 = no existe, 124 = timeout. */
    static Result runCmd(List<String> cmd, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(resolve(cmd))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new Result(127, "");
        }
        try {
            p.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                destroyTree(p);
                return new Result(124, "");
            }
            return new Result(p.exitValue(), out.get(5, TimeUnit.SECONDS).strip());
        } catch (Exception e) {
            destroyTree(p);
            return new Result(124, "");
        }
    }

    static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Ejecuta un comando mostrando su salida en vivo en la consola. */
    static int runStream(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(resolve(cmd))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            return p.waitFor();
        } catch (IOException e) {
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /**
     * Como runStream, pero el usuario lo corta con Enter (para --follow de logs). Ctrl+C
     * cerraria todo el programa, por eso se usa Enter para volver al menu.
     */
    static int runStreamUntilEnter(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(resolve(cmd))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            while (p.isAlive()) {
                if (IN.ready()) {
                    IN.readLine();
                    destroyTree(p);
                    p.waitFor(10, TimeUnit.SECONDS);
                    return 130;
                }
                Thread.sleep(200);
            }
            return p.exitValue();
        } catch (IOException e) {
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /** En Windows az.cmd lanza un python hijo: hay que cortar tambien los descendientes. */
    static void destroyTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    // ---------------------------------------------------------------------------
    // Carga del archivo .env
    // ---------------------------------------------------------------------------
    /** Lee KEY=VALUE, ignora comentarios (#) y lineas vacias, y quita comillas simples o dobles. */
    static Map<String, String> loadEnvFile(String envPath) {
        Map<String, String> vars = new LinkedHashMap<>();
        Path path = Path.of(envPath);
        if (!Files.exists(path)) {
            OUT.println(Color.warn("⚠") + " Archivo .env no encontrado: " + envPath);
            OUT.println("  " + Color.DIM + "Copiá la plantilla: " + (WINDOWS ? "copy" : "cp")
                    + " .env.example .env" + Color.RESET);
            return vars;
        }
        logInfo("Cargando variables desde " + path.toAbsolutePath());
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    logWarn("Línea " + (i + 1) + " ignorada (sin '='): "
                            + line.substring(0, Math.min(50, line.length())));
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && value.charAt(0) == value.charAt(value.length() - 1)
                        && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                    value = value.substring(1, value.length() - 1);
                }
                vars.put(key, value);
            }
        } catch (IOException e) {
            logErr("No se pudo leer " + envPath + ": " + e.getMessage());
        }
        return vars;
    }

    // ---------------------------------------------------------------------------
    // Configuracion central
    // ---------------------------------------------------------------------------
    /**
     * Credenciales del Service Principal: siempre desde el .env. Infraestructura (RG, ACR,
     * entorno, app): se elige en cada ejecucion listando con az. Imagen: defaults del .env.
     */
    static final class Config {
        String clientId, clientSecret, tenantId, subscriptionId;
        String resourceGroup, acrName, environment, appName;
        String imageName, tag, dockerfile, buildContext, targetPort;
        String cpu, memory, minReplicas, maxReplicas;

        Config(Map<String, String> env) {
            clientId = get(env, "AZURE_CLIENT_ID", "");
            clientSecret = get(env, "AZURE_CLIENT_SECRET", "");
            tenantId = get(env, "AZURE_TENANT_ID", "");
            subscriptionId = get(env, "AZURE_SUBSCRIPTION_ID", "");

            resourceGroup = get(env, "RESOURCE_GROUP", "");
            acrName = get(env, "ACR_NAME", "");
            environment = get(env, "CONTAINERAPP_ENVIRONMENT", "");
            // Vacio a proposito: si ya existe una app en el RG, el selector la prioriza a ella
            appName = get(env, "CONTAINERAPP_NAME", "");

            imageName = get(env, "IMAGE_NAME", "parcial-ing-soft");
            tag = get(env, "IMAGE_TAG", "v1.0.0");
            dockerfile = get(env, "DOCKERFILE_PATH", "Dockerfile");
            buildContext = get(env, "BUILD_CONTEXT", ".");
            targetPort = get(env, "TARGET_PORT", "8080");

            // Perfil minimo (sin GPU, para no gastar creditos)
            cpu = get(env, "CPU", "0.25");
            memory = get(env, "MEMORY", "0.5Gi");
            minReplicas = get(env, "MIN_REPLICAS", "1");
            maxReplicas = get(env, "MAX_REPLICAS", "1");
        }

        static String get(Map<String, String> env, String key, String def) {
            String v = env.get(key);
            if (v == null || v.isEmpty()) {
                v = System.getenv(key);
            }
            return v == null || v.isEmpty() ? def : v;
        }

        String acrServer() { return acrName + ".azurecr.io"; }
        String fullImage() { return acrServer() + "/" + imageName + ":" + tag; }
        String latestImage() { return acrServer() + "/" + imageName + ":latest"; }

        List<String> validateCredentials() {
            List<String> errors = new ArrayList<>();
            Map<String, String> required = new LinkedHashMap<>();
            required.put("AZURE_CLIENT_ID", clientId);
            required.put("AZURE_CLIENT_SECRET", clientSecret);
            required.put("AZURE_TENANT_ID", tenantId);
            required.put("AZURE_SUBSCRIPTION_ID", subscriptionId);
            required.forEach((k, v) -> {
                if (v.isEmpty()) {
                    errors.add(k + " está vacío — completalo en el .env");
                }
            });
            return errors;
        }

        void show() {
            section("Configuración Actual");
            logDetail("Client ID", orWarn(clientId, "(vacío)"));
            logDetail("Client Secret", clientSecret.isEmpty() ? Color.warn("(vacío)") : "••••••••");
            logDetail("Tenant ID", orWarn(tenantId, "(vacío)"));
            logDetail("Subscription", orWarn(subscriptionId, "(vacío)"));
            logDetail("Resource Group", orWarn(resourceGroup, "(se elige al inicio)"));
            logDetail("ACR", orWarn(acrName, "(se elige al inicio)"));
            logDetail("Environment ACA", orWarn(environment, "(se elige al inicio)"));
            logDetail("Container App", appName);
            logDetail("Imagen", fullImage());
            logDetail("Dockerfile", dockerfile);
            logDetail("Puerto (ingress)", targetPort);
            logDetail("CPU / Memoria", cpu + " / " + memory + "  " + Color.DIM + "(mínimo, sin GPU)" + Color.RESET);
            logDetail("Réplicas", "min " + minReplicas + " / max " + maxReplicas);
        }

        static String orWarn(String v, String placeholder) {
            return v.isEmpty() ? Color.warn(placeholder) : v;
        }
    }

    // ---------------------------------------------------------------------------
    // Verificacion del entorno
    // ---------------------------------------------------------------------------
    static boolean checkDocker() {
        logInfo("Verificando Docker Desktop...");
        Result r = runCmd(List.of("docker", "version", "--format", "{{.Server.Version}}"), 15);
        if (r.code() == 127) {
            logErr("Docker CLI no encontrado en PATH");
            OUT.println("    https://docs.docker.com/desktop/");
            return false;
        }
        if (r.code() == 124) {
            logErr("Timeout — Docker daemon no responde");
            return false;
        }
        if (r.code() != 0) {
            logErr("Docker daemon no está accesible");
            OUT.println("    " + Color.warn("¿Docker Desktop está iniciado? Arrancalo y reintentá."));
            return false;
        }
        logOk("Docker Desktop en ejecución (Server v" + r.out() + ")");
        return true;
    }

    static boolean checkAzureCli() {
        logInfo("Verificando Azure CLI...");
        Result r = runCmd(List.of("az", "version", "-o", "json"), 30);
        if (r.code() == 127) {
            logErr("Azure CLI no encontrado en PATH");
            logDetail("Ruta intentada", AZ_CMD);
            OUT.println("    https://learn.microsoft.com/cli/azure/install-azure-cli");
            return false;
        }
        if (r.code() != 0) {
            logErr("Azure CLI no funciona correctamente");
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"azure-cli\"\\s*:\\s*\"([^\"]+)\"").matcher(r.out());
        logOk("Azure CLI " + (m.find() ? m.group(1) : "instalada"));
        return true;
    }

    // ---------------------------------------------------------------------------
    // Autenticacion Azure (Service Principal)
    // ---------------------------------------------------------------------------
    static final class AzureAuth {
        final Config config;

        AzureAuth(Config config) { this.config = config; }

        boolean login() {
            section("Autenticando en Azure (Service Principal)");
            logDetail("Client ID", config.clientId);
            logDetail("Tenant", config.tenantId);

            logInfo("az login --service-principal ...");
            Result r = runCmd(List.of("az", "login", "--service-principal",
                    "--username", config.clientId,
                    "--password", config.clientSecret,
                    "--tenant", config.tenantId,
                    "--output", "none"), 90);
            if (r.code() != 0) {
                logErr("Login con Service Principal falló — verificá AZURE_CLIENT_ID/SECRET/TENANT_ID");
                return false;
            }
            logOk("Login Service Principal exitoso");

            logInfo("Fijando suscripción " + config.subscriptionId + " ...");
            r = runCmd(List.of("az", "account", "set", "--subscription", config.subscriptionId), 30);
            if (r.code() != 0) {
                logErr("No se pudo fijar la suscripción — verificá AZURE_SUBSCRIPTION_ID");
                return false;
            }
            logOk("Suscripción activa");
            return true;
        }

        boolean acrLogin() {
            logInfo("az acr login --name " + config.acrName + " ...");
            Result r = runCmd(List.of("az", "acr", "login", "--name", config.acrName), 60);
            if (r.code() != 0) {
                logErr("No se pudo autenticar contra " + config.acrServer());
                OUT.println("    " + Color.DIM + "Verificá que el Service Principal tenga rol AcrPush o Contributor."
                        + Color.RESET);
                return false;
            }
            logOk("Login exitoso en " + config.acrServer());
            return true;
        }
    }

    // ---------------------------------------------------------------------------
    // Configuracion interactiva de la infraestructura (via Azure CLI)
    // ---------------------------------------------------------------------------
    static final class InteractiveSetup {

        static List<String> azList(List<String> cmd) {
            Result r = runCmd(cmd, 60);
            if (r.code() != 0 || r.out().isEmpty()) {
                return List.of();
            }
            return r.out().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        }

        static String ask(String label, String def) {
            String v = readLine("  " + Color.info("?") + " " + label + " " + Color.DIM + "(Enter = " + def + ")"
                    + Color.RESET + ": ");
            return v.isEmpty() ? def : v;
        }

        /** Numero = item de la lista, Enter = default, 0 = modo manual, texto = nombre directo. */
        static String pickFromList(String title, List<String> items, String def, String customLabel) {
            section(title);
            if (!items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    String marker = items.get(i).equals(def) ? "  ← default" : "";
                    OUT.println("    " + Color.BOLD + (i + 1) + Color.RESET + ". " + items.get(i) + marker);
                }
                OUT.println("    " + Color.DIM + "0. " + customLabel + Color.RESET);
            } else {
                logWarn("No se encontraron recursos (" + title + ") — escribí el nombre manualmente");
            }
            while (true) {
                String prompt = "  " + Color.info("?") + " Selección"
                        + (def.isEmpty() ? "" : " " + Color.DIM + "(Enter = " + def + ")" + Color.RESET) + ": ";
                String choice = readLine(prompt);
                if (choice.isEmpty() && !def.isEmpty()) {
                    return def;
                }
                if (!items.isEmpty() && choice.matches("\\d+")) {
                    int n = Integer.parseInt(choice);
                    if (n == 0) {
                        logWarn("Modo manual: escribí el nombre");
                        continue;
                    }
                    if (n >= 1 && n <= items.size()) {
                        return items.get(n - 1);
                    }
                }
                if (!choice.isEmpty()) {
                    return choice;
                }
                logWarn("Entrada vacía — elegí un número o escribí un nombre");
            }
        }

        static String firstOr(List<String> items, String current, String fallback) {
            if (!current.isEmpty()) {
                return current;
            }
            return items.isEmpty() ? fallback : items.get(0);
        }

        /** Resource Group → ACR → Entorno ACA → Container App → Imagen/Tag/Puerto. */
        static void configureAll(Config c) {
            section("Configuración interactiva de infraestructura (vía Azure CLI)");
            logInfo("Listando los recursos de tu cuenta con az...");

            List<String> rgs = azList(List.of("az", "group", "list", "--query", "[].name", "-o", "tsv"));
            c.resourceGroup = pickFromList("RESOURCE GROUP — listado de tu cuenta", rgs,
                    firstOr(rgs, c.resourceGroup, ""), "escribir el nombre manualmente");

            List<String> acrs = azList(List.of("az", "acr", "list", "--query", "[].name", "-o", "tsv"));
            c.acrName = pickFromList("AZURE CONTAINER REGISTRY — listado de tu cuenta", acrs,
                    firstOr(acrs, c.acrName, ""), "escribir el nombre manualmente");

            List<String> envs = azList(List.of("az", "containerapp", "env", "list",
                    "--resource-group", c.resourceGroup, "--query", "[].name", "-o", "tsv"));
            if (envs.isEmpty()) {
                logWarn("Sin entornos en '" + c.resourceGroup + "' — listando toda la suscripción...");
                envs = azList(List.of("az", "containerapp", "env", "list", "--query", "[].name", "-o", "tsv"));
            }
            c.environment = pickFromList("ENTORNO de Container Apps — listado de tu cuenta", envs,
                    firstOr(envs, c.environment, ""), "escribir el nombre manualmente");

            List<String> apps = azList(List.of("az", "containerapp", "list",
                    "--resource-group", c.resourceGroup, "--query", "[].name", "-o", "tsv"));
            c.appName = pickFromList("CONTAINER APP a gestionar (existentes en '" + c.resourceGroup + "')", apps,
                    firstOr(apps, c.appName, "app-azure-instancia"), "crear una nueva (escribir el nombre)");

            c.imageName = ask("Nombre de la imagen Docker", c.imageName.isEmpty() ? "parcial-ing-soft" : c.imageName);
            c.tag = ask("Tag / versión", c.tag.isEmpty() ? "v1.0.0" : c.tag);
            c.targetPort = ask("Puerto donde escucha el Dockerfile", c.targetPort.isEmpty() ? "8080" : c.targetPort);

            section("Infraestructura configurada");
            logDetail("Resource Group", c.resourceGroup);
            logDetail("ACR", c.acrServer());
            logDetail("Entorno", c.environment);
            logDetail("Container App", c.appName);
            logDetail("Imagen", c.fullImage());
            logDetail("Puerto", c.targetPort);
        }
    }

    // ---------------------------------------------------------------------------
    // Operaciones Docker (build con telemetria, push, pull)
    // ---------------------------------------------------------------------------
    static final class DockerOps {
        final Config config;
        final AzureAuth auth;

        DockerOps(Config config, AzureAuth auth) {
            this.config = config;
            this.auth = auth;
        }

        static String gitSha() {
            Result r = runCmd(List.of("git", "rev-parse", "--short", "HEAD"), 5);
            return r.code() == 0 ? r.out() : "unknown";
        }

        static String gitUrl() {
            Result r = runCmd(List.of("git", "remote", "get-url", "origin"), 5);
            return r.code() == 0 ? r.out() : "unknown";
        }

        /** Build local con telemetria: etiquetas OCI (estandar) y build args. */
        boolean buildWithTelemetry() {
            section("Build local de imagen Docker — " + config.fullImage());
            Path dockerfile = Path.of(config.dockerfile);
            if (!Files.exists(dockerfile)) {
                logErr("Dockerfile no encontrado: " + dockerfile.toAbsolutePath());
                return false;
            }
            String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            String sha = gitSha();
            String url = gitUrl();

            List<String> cmd = new ArrayList<>(List.of(
                    "docker", "build",
                    // Azure Container Apps solo ejecuta linux/amd64 (en Mac Apple Silicon se emula)
                    "--platform", "linux/amd64",
                    "-f", config.dockerfile,
                    "-t", config.fullImage(),
                    "-t", config.latestImage(),
                    "--build-arg", "BUILD_DATE=" + now,
                    "--build-arg", "VCS_REF=" + sha,
                    "--build-arg", "IMAGE_TAG=" + config.tag,
                    "--label", "org.opencontainers.image.title=" + config.imageName,
                    "--label", "org.opencontainers.image.version=" + config.tag,
                    "--label", "org.opencontainers.image.created=" + now,
                    "--label", "org.opencontainers.image.revision=" + sha,
                    "--label", "org.opencontainers.image.source=" + url,
                    "--label", "org.opencontainers.image.description=App " + config.imageName
                            + " - Azure Container Apps",
                    "--label", "author=ingenieria-um",
                    config.buildContext));

            logDetail("Contexto", Path.of(config.buildContext).toAbsolutePath().toString());
            logDetail("Plataforma", "linux/amd64 (requerida por Azure Container Apps)");
            logInfo("docker build ... (este paso puede tardar varios minutos)");
            OUT.println(Color.DIM + "─".repeat(60) + Color.RESET);

            int code = runStream(cmd);
            if (code != 0) {
                logErr("Build falló (código " + code + ")");
                return false;
            }
            logOk("Build completado");
            printImageTelemetry();
            return true;
        }

        void printImageTelemetry() {
            section("Telemetría de la imagen");
            Result size = inspect("{{.Size}}");
            if (size.code() == 0 && !size.out().isEmpty()) {
                logDetail("Tamaño", String.format(Locale.ROOT, "%.2f MB", Long.parseLong(size.out()) / 1024.0 / 1024.0));
            }
            Result created = inspect("{{.Created}}");
            if (created.code() == 0) {
                logDetail("Creada", created.out());
            }
            Result digest = inspect("{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}");
            if (digest.code() == 0 && !digest.out().isEmpty()) {
                logDetail("Digest", digest.out());
            }
            Result labels = inspect("{{range $k, $v := .Config.Labels}}{{$k}}={{$v}}\n{{end}}");
            if (labels.code() == 0) {
                labels.out().lines()
                        .filter(l -> l.startsWith("org.opencontainers"))
                        .forEach(l -> {
                            int eq = l.indexOf('=');
                            String key = l.substring(0, eq);
                            logDetail(key.substring(key.lastIndexOf('.') + 1), l.substring(eq + 1));
                        });
            }
        }

        Result inspect(String format) {
            return runCmd(List.of("docker", "image", "inspect", config.fullImage(), "--format", format), 15);
        }

        boolean push() {
            section("Push a Azure Container Registry — " + config.acrServer());
            for (String image : List.of(config.fullImage(), config.latestImage())) {
                logInfo("docker push " + image);
                int code = runStream(List.of("docker", "push", image));
                if (code != 0) {
                    logErr("Push falló (código " + code + ")");
                    return false;
                }
                logOk("Imagen publicada: " + image);
            }
            return true;
        }

        /** Confirma con az que el tag quedo en el ACR (az acr repository show-tags / list). */
        boolean verifyInRegistry() {
            section("Verificación de la subida al ACR");
            Result r = runCmd(List.of("az", "acr", "repository", "show-tags", "--name", config.acrName,
                    "--repository", config.imageName, "-o", "tsv"), 60);
            List<String> tags = Arrays.asList(r.out().split("\\s+"));
            if (r.code() == 0 && tags.contains(config.tag)) {
                logOk("Verificado: " + config.fullImage() + " está en el ACR");
                logDetail("Tags en el repositorio", String.join(", ", tags));
                return true;
            }
            Result repos = runCmd(List.of("az", "acr", "repository", "list", "--name", config.acrName, "-o", "tsv"), 60);
            if (repos.code() == 0) {
                logDetail("Repositorios en el ACR",
                        repos.out().isEmpty() ? "(ninguno)" : String.join(", ", repos.out().split("\\s+")));
            }
            logWarn("Verificación manual: az acr repository list --name " + config.acrName);
            return false;
        }

        /** Pull de la imagen desde el ACR y ejecucion local opcional. */
        boolean pull() {
            section("Pull desde Azure Container Registry — " + config.fullImage());
            if (!requireAcr(config) || !auth.acrLogin()) {
                return false;
            }
            logInfo("docker pull " + config.fullImage());
            int code = runStream(List.of("docker", "pull", config.fullImage()));
            if (code != 0) {
                logErr("Pull falló (código " + code + ")");
                return false;
            }
            logOk("Imagen descargada localmente");
            Result size = inspect("{{.Size}}");
            if (size.code() == 0 && !size.out().isEmpty()) {
                logDetail("Tamaño local", String.format(Locale.ROOT, "%.2f MB", Long.parseLong(size.out()) / 1024.0 / 1024.0));
            }

            // pull solo descarga la imagen: el contenedor aparece en Docker Desktop al ejecutarla
            if (confirm("¿Ejecutar la imagen localmente ahora? (aparece en Docker Desktop → Containers)")) {
                String name = config.imageName + "-local";
                String ports = config.targetPort + ":" + config.targetPort;
                logInfo("docker run -d --rm --name " + name + " -p " + ports + " ...");
                OUT.println("  " + Color.DIM + "La app necesita JWT_SECRET y GROQ_API_KEY: se pasan con --env-file .env"
                        + Color.RESET);
                code = runStream(List.of("docker", "run", "-d", "--rm", "--name", name, "-p", ports,
                        "--env-file", ".env", config.fullImage()));
                if (code != 0) {
                    logWarn("No se pudo levantar el contenedor — usá el comando manual");
                    return false;
                }
                logOk("Contenedor '" + name + "' corriendo");
                OUT.println("  " + Color.ok("🌐 LOCAL:") + " " + Color.BOLD + "http://localhost:" + config.targetPort
                        + Color.RESET);
                OUT.println("  " + Color.DIM + "Para frenarlo: docker stop " + name + " (con --rm se elimina solo)"
                        + Color.RESET);
                return true;
            }
            logInfo("Podés ejecutarla manualmente con:");
            OUT.println("    " + Color.DIM + "docker run --rm -p " + config.targetPort + ":" + config.targetPort
                    + " --env-file .env " + config.fullImage() + Color.RESET);
            return true;
        }
    }

    // ---------------------------------------------------------------------------
    // Operaciones Azure Container Apps
    // ---------------------------------------------------------------------------
    static final class ContainerAppsOps {
        static final String API_VERSION = "2026-07-01";
        final Config config;
        Boolean nativeStartStop;

        ContainerAppsOps(Config config) { this.config = config; }

        boolean exists() {
            return runCmd(List.of("az", "containerapp", "show", "--name", config.appName,
                    "--resource-group", config.resourceGroup), 30).code() == 0;
        }

        String getFqdn() {
            Result r = runCmd(List.of("az", "containerapp", "show", "--name", config.appName,
                    "--resource-group", config.resourceGroup,
                    "--query", "properties.configuration.ingress.fqdn", "-o", "tsv"), 30);
            return r.code() == 0 ? r.out() : "";
        }

        /**
         * Si la app no existe la crea (ingress externo, perfil minimo, 1 replica); si existe
         * aplica las credenciales del registry y actualiza imagen y recursos. Al final, si
         * quedo detenida, la inicia e imprime la URL publica.
         */
        boolean deploy() {
            section("Deploy en Azure Container Apps — " + config.appName);
            if (config.resourceGroup.isEmpty() || config.acrName.isEmpty() || config.environment.isEmpty()) {
                logErr("Infraestructura incompleta (resource group / ACR / environment)");
                return false;
            }
            if (!requireAcr(config)) {
                return false;
            }
            // Credenciales del ACR: las del usuario admin (igual que el pipeline). No se usa el
            // Service Principal: su secreto vence y tiene permisos sobre todo el resource group.
            // La contrasena cambia cada vez que se recrea el ACR, por eso se lee en cada deploy.
            Result cred = runCmd(List.of("az", "acr", "credential", "show", "--name", config.acrName,
                    "--query", "passwords[0].value", "-o", "tsv"), 60);
            if (cred.code() != 0 || cred.out().isEmpty()) {
                logErr("No se pudieron leer las credenciales admin del ACR (¿usuario admin habilitado?)");
                return false;
            }
            String username = config.acrName;
            String password = cred.out();

            List<String> cmd;
            if (!exists()) {
                logInfo("La app '" + config.appName + "' no existe → CREAR");
                cmd = List.of("az", "containerapp", "create",
                        "--name", config.appName,
                        "--resource-group", config.resourceGroup,
                        "--environment", config.environment,
                        "--image", config.fullImage(),
                        "--registry-server", config.acrServer(),
                        "--registry-username", username,
                        "--registry-password", password,
                        "--ingress", "external",
                        "--target-port", config.targetPort,
                        "--cpu", config.cpu,
                        "--memory", config.memory,
                        "--min-replicas", config.minReplicas,
                        "--max-replicas", config.maxReplicas);
            } else {
                logInfo("La app '" + config.appName + "' ya existe → ACTUALIZAR");
                // az containerapp update no acepta --registry-*: se aplican antes con registry set
                logInfo("az containerapp registry set ... (usuario admin del ACR)");
                int code = runStream(List.of("az", "containerapp", "registry", "set",
                        "--name", config.appName,
                        "--resource-group", config.resourceGroup,
                        "--server", config.acrServer(),
                        "--username", username,
                        "--password", password));
                if (code != 0) {
                    logErr("No se pudieron aplicar las credenciales admin del ACR");
                    return false;
                }
                cmd = List.of("az", "containerapp", "update",
                        "--name", config.appName,
                        "--resource-group", config.resourceGroup,
                        "--image", config.fullImage(),
                        "--cpu", config.cpu,
                        "--memory", config.memory,
                        "--min-replicas", config.minReplicas,
                        "--max-replicas", config.maxReplicas);
            }

            logDetail("Comando", String.join(" ", cmd.stream().filter(a -> !a.equals(password)).toList()));
            logInfo("Ejecutando (puede tardar 1-2 minutos)...");
            int code = runStream(cmd);
            if (code != 0) {
                logErr("Deploy falló (código " + code + ")");
                return false;
            }
            logOk("Deploy completado");

            // create/update no reanudan una app detenida con la accion stop
            if (getRunningStatus().equalsIgnoreCase("stopped")) {
                logInfo("La app está detenida — iniciándola para dejarla sirviendo...");
                if (!startAction()) {
                    logWarn("No se pudo iniciar la app — probá la opción 3 → b");
                } else if (!waitStatus("Running")) {
                    logWarn("Acción start aceptada — las réplicas levantan en ~30-60s");
                }
            }

            String fqdn = getFqdn();
            if (!fqdn.isEmpty()) {
                OUT.println();
                OUT.println("  " + Color.ok("🌐 URL PÚBLICA:") + " " + Color.BOLD + "https://" + fqdn + Color.RESET);
                OUT.println("  " + Color.DIM + "(puerto interno " + config.targetPort + " expuesto como HTTP/HTTPS)"
                        + Color.RESET);
                OUT.println();
            } else {
                logWarn("No se pudo obtener el FQDN — consultalo con `az containerapp show`");
            }
            return true;
        }

        String actionUri(String action) {
            return "https://management.azure.com/subscriptions/" + config.subscriptionId
                    + "/resourceGroups/" + config.resourceGroup
                    + "/providers/Microsoft.App/containerApps/" + config.appName
                    + "/" + action + "?api-version=" + API_VERSION;
        }

        /**
         * Detiene la app con la accion stop de Microsoft.App: `az containerapp stop` si la CLI lo
         * trae; si no, la misma accion por REST con `az rest`. Escalar a 0 replicas no es valido
         * (--max-replicas tiene que estar entre 1 y 1000) y no equivale a stop.
         */
        boolean stop() {
            section("Deteniendo servicio — " + config.appName);
            if (!exists()) {
                logErr("La app '" + config.appName + "' no existe");
                return false;
            }
            if (!confirm("¿Detener '" + config.appName + "'?")) {
                logInfo("Operación cancelada");
                return true;
            }
            int code;
            if (nativeStartStopAvailable()) {
                logInfo("az containerapp stop ...");
                code = runStream(List.of("az", "containerapp", "stop", "--name", config.appName,
                        "--resource-group", config.resourceGroup));
            } else {
                logInfo("La CLI instalada no trae `containerapp stop` — invocando la acción stop vía REST API (az rest)");
                logDetail("REST", "POST .../containerApps/" + config.appName + "/stop");
                code = runStream(List.of("az", "rest", "--method", "post", "--uri", actionUri("stop"),
                        "--output", "none"));
            }
            if (code != 0) {
                logErr("No se pudo detener la app");
                return false;
            }
            if (waitStatus("Stopped")) {
                logOk("Servicio detenido — runningStatus: Stopped (no consume créditos)");
            } else {
                logWarn("Acción aceptada por Azure — runningStatus aún no es Stopped");
            }
            return true;
        }

        String getRunningStatus() {
            Result r = runCmd(List.of("az", "containerapp", "show", "--name", config.appName,
                    "--resource-group", config.resourceGroup,
                    "--query", "properties.runningStatus", "-o", "tsv"), 30);
            return r.code() == 0 ? r.out() : "";
        }

        boolean startAction() {
            if (nativeStartStopAvailable()) {
                logInfo("az containerapp start ...");
                return runStream(List.of("az", "containerapp", "start", "--name", config.appName,
                        "--resource-group", config.resourceGroup)) == 0;
            }
            logInfo("La CLI instalada no trae `containerapp start` — invocando la acción start vía REST API (az rest)");
            logDetail("REST", "POST .../containerApps/" + config.appName + "/start");
            return runStream(List.of("az", "rest", "--method", "post", "--uri", actionUri("start"),
                    "--output", "none")) == 0;
        }

        boolean nativeStartStopAvailable() {
            if (nativeStartStop == null) {
                nativeStartStop = runCmd(List.of("az", "containerapp", "start", "--help"), 30).code() == 0;
            }
            return nativeStartStop;
        }

        boolean waitStatus(String expected) {
            for (int i = 0; i < 12; i++) {
                if (getRunningStatus().equalsIgnoreCase(expected)) {
                    return true;
                }
                sleep(5);
            }
            return false;
        }

        /**
         * Si la app esta "Stopped" la reanuda con la accion start (update NO la reanuda). Si fue
         * detenida escalando a 0 replicas, restaura las replicas min/max del .env.
         */
        boolean start() {
            section("Iniciando servicio — " + config.appName);
            if (!exists()) {
                logErr("La app '" + config.appName + "' no existe");
                return false;
            }
            if (getRunningStatus().equalsIgnoreCase("stopped")) {
                // Al iniciar, la app baja la imagen del ACR: sin ACR la revision no levanta
                if (!requireAcr(config)) {
                    return false;
                }
                if (!startAction()) {
                    logErr("No se pudo iniciar la app");
                    return false;
                }
                if (waitStatus("Running")) {
                    logOk("Servicio iniciado — runningStatus: Running");
                } else {
                    logWarn("Acción aceptada por Azure — las réplicas levantan en ~30-60s");
                }
                return true;
            }
            logInfo("az containerapp update --min-replicas " + config.minReplicas + " --max-replicas "
                    + config.maxReplicas + " ...");
            int code = runStream(List.of("az", "containerapp", "update", "--name", config.appName,
                    "--resource-group", config.resourceGroup,
                    "--min-replicas", config.minReplicas, "--max-replicas", config.maxReplicas));
            if (code != 0) {
                logErr("No se pudo iniciar la app");
                return false;
            }
            logOk("Servicio iniciado — " + config.minReplicas + "/" + config.maxReplicas + " réplicas");
            return true;
        }

        boolean listApps() {
            section("Container Apps en el grupo '" + config.resourceGroup + "'");
            // Claves ASCII: con tildes el query falla en Windows (az.cmd pasa por cmd.exe)
            Result r = runCmd(List.of("az", "containerapp", "list", "--resource-group", config.resourceGroup,
                    "--query", "[].{Name:name,Status:properties.runningStatus,"
                            + "CPU:properties.template.containers[0].resources.cpu,"
                            + "Memory:properties.template.containers[0].resources.memory,"
                            + "MinReplicas:properties.template.scale.minReplicas,"
                            + "FQDN:properties.configuration.ingress.fqdn}",
                    "-o", "table"), 60);
            if (r.code() != 0) {
                logErr("No se pudo listar las Container Apps");
                return false;
            }
            if (r.out().isEmpty()) {
                logWarn("No hay Container Apps en el grupo '" + config.resourceGroup + "'");
                return false;
            }
            OUT.println(r.out());
            return true;
        }

        /** Lista las apps, pide el nombre y elimina con confirmacion. */
        boolean deleteApp() {
            section("Eliminar Container App");
            Result r = runCmd(List.of("az", "containerapp", "list", "--resource-group", config.resourceGroup,
                    "--query", "[].name", "-o", "tsv"), 60);
            if (r.code() != 0 || r.out().isEmpty()) {
                logErr("No se pudo obtener la lista de apps");
                return false;
            }
            List<String> names = r.out().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            OUT.println("  " + Color.DIM + "Apps disponibles en '" + config.resourceGroup + "':" + Color.RESET);
            for (int i = 0; i < names.size(); i++) {
                String marker = names.get(i).equals(config.appName) ? " ← actual" : "";
                OUT.println("    " + (i + 1) + ". " + Color.BOLD + names.get(i) + Color.RESET + marker);
            }
            String selection = readLine(Color.info("?") + " Nombre de la app a eliminar " + Color.DIM
                    + "(Enter = cancelar)" + Color.RESET + ": ");
            if (selection.isEmpty()) {
                logInfo("Eliminación cancelada");
                return true;
            }
            if (!names.contains(selection)) {
                logErr("'" + selection + "' no existe en el grupo '" + config.resourceGroup + "'");
                return false;
            }
            if (!confirm("⚠ Esto es IRREVERSIBLE. ¿Eliminar '" + selection + "'?")) {
                logInfo("Eliminación cancelada");
                return true;
            }
            int code = runStream(List.of("az", "containerapp", "delete", "--name", selection,
                    "--resource-group", config.resourceGroup, "--yes"));
            if (code != 0) {
                logErr("Fallo la eliminación");
                return false;
            }
            logOk("'" + selection + "' eliminada correctamente");
            return true;
        }

        /** Logs de consola en vivo; Enter corta el streaming y vuelve al menu. */
        boolean streamLogs() {
            section("Logs en tiempo real — " + config.appName);
            logInfo("Presioná Enter para volver al menú principal");
            OUT.println(Color.DIM + "─".repeat(60) + Color.RESET);
            if (!exists()) {
                logErr("La app '" + config.appName + "' no existe");
                return false;
            }
            int code = runStreamUntilEnter(List.of("az", "containerapp", "logs", "show",
                    "--name", config.appName, "--resource-group", config.resourceGroup,
                    "--type", "console", "--format", "text", "--follow"));
            if (code == 130) {
                OUT.println("  " + Color.info("→") + " Streaming finalizado — volviendo al menú");
                return true;
            }
            if (code != 0) {
                logErr("No se pudo conectar a los logs (código " + code + ")");
                return false;
            }
            return true;
        }
    }

    /**
     * El ACR existe solo mientras la app esta levantada (se borra al apagar porque el plan Basic
     * cobra por dia). Las opciones que lo necesitan (1 build/push, 2 deploy, 3b iniciar, 4 pull)
     * lo verifican antes y explican que hacer si no esta.
     */
    static boolean requireAcr(Config c) {
        if (c.acrName.isEmpty()) {
            logErr("No hay un ACR elegido — usá la opción 6 (Reconfigurar)");
            return false;
        }
        if (runCmd(List.of("az", "acr", "show", "--name", c.acrName, "--output", "none"), 60).code() == 0) {
            return true;
        }
        logErr("El ACR '" + c.acrName + "' no existe: está apagado para no generar costos.");
        OUT.println("    " + Color.warn("Corré el workflow 'Levantar' en GitHub (Actions → Levantar → Run workflow),"));
        OUT.println("    " + Color.warn("que crea el ACR, sube la imagen y deja la app prendida. Después volvé a esta opción."));
        return false;
    }

    static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------
    // Menu interactivo
    // ---------------------------------------------------------------------------
    static void printMenu() {
        OUT.println();
        OUT.println("  " + Color.title("╔══════════════════════════════════════════════╗"));
        OUT.println("  " + Color.title("║   MENÚ PRINCIPAL — Azure Container Apps      ║"));
        OUT.println("  " + Color.title("╚══════════════════════════════════════════════╝"));
        OUT.println();
        OUT.println("  " + Color.BOLD + "1" + Color.RESET + ") " + Color.info("Construir y Subir") + "   build local + push al ACR (telemetría)");
        OUT.println("  " + Color.BOLD + "2" + Color.RESET + ") " + Color.info("Deploy") + "             crear/actualizar Container App + URL pública");
        OUT.println("  " + Color.BOLD + "3" + Color.RESET + ") " + Color.info("Parar / Iniciar / Listar / Eliminar") + "   gestión de instancias");
        OUT.println("  " + Color.BOLD + "4" + Color.RESET + ") " + Color.info("Pull") + "               bajar imagen del ACR a la máquina local");
        OUT.println("  " + Color.BOLD + "5" + Color.RESET + ") " + Color.info("Logs y Telemetría") + "   streaming de trazas en tiempo real");
        OUT.println("  " + Color.BOLD + "6" + Color.RESET + ") " + Color.info("Reconfigurar") + "        volver a elegir infraestructura vía az CLI");
        OUT.println("  " + Color.BOLD + "0" + Color.RESET + ") " + Color.error("Salir") + "              (opción para cerrar sesión de az CLI)");
        OUT.println();
    }

    static void manageInstances(ContainerAppsOps ops) {
        while (true) {
            OUT.println();
            OUT.println("  " + Color.title("── Gestión de Instancias ──"));
            OUT.println("  " + Color.BOLD + "a" + Color.RESET + ") " + Color.info("Parar servicio") + "    detener la app (no consume créditos)");
            OUT.println("  " + Color.BOLD + "b" + Color.RESET + ") " + Color.info("Iniciar servicio") + "  reanudar la app detenida");
            OUT.println("  " + Color.BOLD + "c" + Color.RESET + ") " + Color.info("Listar instancias") + "  tabla de apps del grupo de recursos");
            OUT.println("  " + Color.BOLD + "d" + Color.RESET + ") " + Color.info("Eliminar instancia") + "  prompt de selección + confirmación");
            OUT.println("  " + Color.BOLD + "0" + Color.RESET + ") " + Color.error("Volver al menú principal"));

            String choice = readLine("\n  " + Color.info("Selección") + ": ").toLowerCase(Locale.ROOT);
            switch (choice) {
                case "a" -> ops.stop();
                case "b" -> ops.start();
                case "c" -> ops.listApps();
                case "d" -> ops.deleteApp();
                case "0" -> { return; }
                default -> logWarn("Opción inválida — usá a, b, c, d o 0");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Punto de entrada
    // ---------------------------------------------------------------------------
    static void run(String[] args) {
        OUT.println(Color.header(BANNER));
        OUT.println("  " + Color.DIM + "Iniciado: "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.now())
                + "  |  " + osInfo() + Color.RESET);

        // 1. Cargar .env (ruta opcional por argumento)
        String envPath = args.length > 0 ? args[0] : ".env";
        Map<String, String> envVars = loadEnvFile(envPath);
        if (envVars.isEmpty()) {
            logErr("Sin variables de configuración — copiá .env.example a .env y completalo");
            System.exit(1);
        }
        Config config = new Config(envVars);

        // 2. Validar credenciales del Service Principal
        List<String> errors = config.validateCredentials();
        if (!errors.isEmpty()) {
            OUT.println();
            OUT.println(Color.error("✗ Errores de configuración:"));
            errors.forEach(e -> OUT.println("    • " + e));
            OUT.println("  " + Color.DIM + "Los 4 valores salen de: az ad sp create-for-rbac (ver DEPLOY.md)" + Color.RESET);
            System.exit(1);
        }

        // 3. Verificar entorno (Docker + Azure CLI)
        section("Verificación de entorno");
        if (!checkDocker() || !checkAzureCli()) {
            System.exit(1);
        }

        // 4. Login con Service Principal + configuracion interactiva via az CLI
        AzureAuth auth = new AzureAuth(config);
        if (!auth.login()) {
            System.exit(1);
        }
        InteractiveSetup.configureAll(config);

        // 5. Resumen de configuracion
        config.show();

        // 6. Loop del menu principal
        DockerOps dockerOps = new DockerOps(config, auth);
        ContainerAppsOps acaOps = new ContainerAppsOps(config);

        while (true) {
            printMenu();
            String choice = readLine("  " + Color.info("Opción") + ": ");
            switch (choice) {
                case "1" -> {
                    // Build local con telemetria + push inmediato al ACR + verificacion.
                    // El ACR se verifica antes del build para no esperar minutos y fallar en el push.
                    if (!requireAcr(config)) {
                        logWarn("Build omitido");
                    } else if (dockerOps.buildWithTelemetry()) {
                        if (auth.acrLogin() && dockerOps.push()) {
                            dockerOps.verifyInRegistry();
                        }
                    } else {
                        logWarn("Build falló — push omitido");
                    }
                }
                case "2" -> acaOps.deploy();
                case "3" -> manageInstances(acaOps);
                case "4" -> dockerOps.pull();
                case "5" -> acaOps.streamLogs();
                case "6" -> InteractiveSetup.configureAll(config);
                case "0" -> {
                    if (confirm("¿Cerrar la sesión de Azure CLI (az logout) antes de salir?")) {
                        logInfo("az logout ...");
                        if (runCmd(List.of("az", "logout"), 30).code() == 0) {
                            logOk("Sesión de Azure CLI cerrada");
                        } else {
                            logWarn("No se pudo cerrar la sesión (puede que ya estuviera cerrada)");
                        }
                    }
                    OUT.println();
                    OUT.println(Color.ok("👋 ¡Hasta luego!"));
                    OUT.println();
                    return;
                }
                default -> logWarn("Opción inválida — elegí entre 0 y 6");
            }
        }
    }
}
