import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuthServer {
    private static final int PORT = 8081;
    private static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000; // 24 heures
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int PBKDF2_KEY_LEN = 256;

    private static final Path DATA_DIR = Paths.get("data");
    private static final Path USERS_FILE = DATA_DIR.resolve("users.db");
    private static final SecureRandom RANDOM = new SecureRandom();

    // username (minuscules) -> "PseudoOriginal:saltHex:hashHex"
    private static final Map<String, String> users = new ConcurrentHashMap<>();
    // token -> Session(username, expiresAt)
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static class Session {
        final String username;
        final long expiresAt;

        Session(String username, long expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        loadUsers();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Toutes les routes passent par le wrapper global (erreurs + headers de sécurité)
        server.createContext("/api/register", wrap(AuthServer::handleRegister));
        server.createContext("/api/login", wrap(AuthServer::handleLogin));
        server.createContext("/api/me", wrap(AuthServer::handleMe));
        server.createContext("/api/logout", wrap(AuthServer::handleLogout));
        server.createContext("/api/tasks", wrap(AuthServer::handleTasks));
        server.createContext("/", wrap(AuthServer::handleStatic));

        server.start();
        System.out.println("StudyPlanner lancé sur http://localhost:" + PORT);
    }

    // Wrapper global : ajoute les headers de sécurité et capture toute exception imprévue
    private static HttpHandler wrap(HttpHandler handler) {
        return ex -> {
            addSecurityHeaders(ex);
            try {
                handler.handle(ex);
            } catch (Exception e) {
                e.printStackTrace();
                if (ex.getResponseCode() == -1) {
                    sendJson(ex, 500, "{\"error\":\"Erreur interne du serveur.\"}");
                }
            } finally {
                ex.close();
            }
        };
    }

    private static void addSecurityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
    }

    // --- Authentification ---

    private static void handleRegister(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Méthode non autorisée.\"}");
            return;
        }
        String body = readBody(ex);
        String username = extractJson(body, "username").trim();
        String password = extractJson(body, "password");

        if (!username.matches("[a-zA-Z0-9_-]{3,24}") || password.length() < 4) {
            sendJson(ex, 400, "{\"error\":\"Pseudo (3-24 car. alphanumériques) et mot de passe (4 car. min) requis.\"}");
            return;
        }

        String key = username.toLowerCase();
        String record = username + ":" + hashPassword(password);

        // Opération atomique : évite la race condition si deux requêtes arrivent en même temps
        if (users.putIfAbsent(key, record) != null) {
            sendJson(ex, 409, "{\"error\":\"Ce pseudo est déjà utilisé.\"}");
            return;
        }
        saveUser(key, record);

        String token = createSession(username);
        sendJson(ex, 201, "{\"token\":\"" + token + "\",\"username\":\"" + escapeJson(username) + "\"}");
    }

    private static void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"Méthode non autorisée.\"}");
            return;
        }
        String body = readBody(ex);
        String username = extractJson(body, "username").trim();
        String password = extractJson(body, "password");

        String stored = users.get(username.toLowerCase());
        if (stored == null) {
            sendJson(ex, 401, "{\"error\":\"Identifiants incorrects.\"}");
            return;
        }

        String[] parts = stored.split(":", 3);
        if (parts.length != 3 || !verifyPassword(password, parts[1], parts[2])) {
            sendJson(ex, 401, "{\"error\":\"Identifiants incorrects.\"}");
            return;
        }

        String token = createSession(parts[0]);
        sendJson(ex, 200, "{\"token\":\"" + token + "\",\"username\":\"" + escapeJson(parts[0]) + "\"}");
    }

    private static void handleMe(HttpExchange ex) throws IOException {
        String username = requireUser(ex);
        if (username == null) return;
        sendJson(ex, 200, "{\"username\":\"" + escapeJson(username) + "\"}");
    }

    private static void handleLogout(HttpExchange ex) throws IOException {
        String token = getToken(ex);
        if (token != null) sessions.remove(token);
        sendJson(ex, 200, "{\"ok\":true}");
    }

    // --- Persistance des tâches côté serveur (/api/tasks) ---

    private static void handleTasks(HttpExchange ex) throws IOException {
        String username = requireUser(ex);
        if (username == null) return;

        Path taskFile = DATA_DIR.resolve("tasks_" + username.toLowerCase() + ".json");
        String method = ex.getRequestMethod();

        if ("GET".equals(method)) {
            String json = Files.exists(taskFile)
                    ? Files.readString(taskFile, StandardCharsets.UTF_8)
                    : "[]";
            sendJson(ex, 200, json);
        } else if ("PUT".equals(method)) {
            String body = readBody(ex).trim();
            if (!body.startsWith("[") || !body.endsWith("]") || body.length() > 500_000) {
                sendJson(ex, 400, "{\"error\":\"Format de tâches invalide.\"}");
                return;
            }
            Files.writeString(taskFile, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            sendJson(ex, 200, "{\"ok\":true}");
        } else {
            sendJson(ex, 405, "{\"error\":\"Méthode non autorisée.\"}");
        }
    }

    // --- Fichiers statiques ---

    private static void handleStatic(HttpExchange ex) throws IOException {
        String reqPath = ex.getRequestURI().getPath();
        if ("/".equals(reqPath)) reqPath = "/index.html";

        // Empêche l'accès direct au dossier data/ ou au code source Java
        if (reqPath.startsWith("/data/") || reqPath.endsWith(".java") || reqPath.endsWith(".class") || reqPath.endsWith(".db")) {
            sendText(ex, 403, "403 - Accès interdit");
            return;
        }

        Path base = Paths.get("").toAbsolutePath().normalize();
        Path file = base.resolve(reqPath.substring(1)).normalize();
        if (!file.startsWith(base) || !Files.exists(file) || Files.isDirectory(file)) {
            sendText(ex, 404, "404 - Fichier introuvable");
            return;
        }

        String mime = "application/octet-stream";
        if (reqPath.endsWith(".html")) mime = "text/html; charset=utf-8";
        else if (reqPath.endsWith(".css")) mime = "text/css; charset=utf-8";
        else if (reqPath.endsWith(".js")) mime = "application/javascript; charset=utf-8";

        byte[] content = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, content.length);
        ex.getResponseBody().write(content);
    }

    // --- Sessions & Sécurité ---

    private static String createSession(String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(username, System.currentTimeMillis() + SESSION_TTL_MS));
        return token;
    }

    private static String requireUser(HttpExchange ex) throws IOException {
        String token = getToken(ex);
        if (token == null) {
            sendJson(ex, 401, "{\"error\":\"Non connecté.\"}");
            return null;
        }
        Session s = sessions.get(token);
        if (s == null || s.isExpired()) {
            sessions.remove(token);
            sendJson(ex, 401, "{\"error\":\"Session expirée, veuillez vous reconnecter.\"}");
            return null;
        }
        return s.username;
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt);
        return toHex(salt) + ":" + toHex(hash);
    }

    private static boolean verifyPassword(String password, String saltHex, String expectedHashHex) {
        byte[] salt = fromHex(saltHex);
        byte[] actual = pbkdf2(password, salt);
        byte[] expected = fromHex(expectedHashHex);
        return MessageDigest.isEqual(actual, expected); // Comparaison en temps constant
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LEN);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Utilitaires ---

    private static void loadUsers() throws IOException {
        if (!Files.exists(USERS_FILE)) return;
        for (String line : Files.readAllLines(USERS_FILE, StandardCharsets.UTF_8)) {
            String[] parts = line.split("=", 2);
            if (parts.length == 2) users.put(parts[0], parts[1]);
        }
    }

    private static synchronized void saveUser(String key, String value) throws IOException {
        Files.writeString(USERS_FILE, key + "=" + value + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String getToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
    }

    private static String extractJson(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}