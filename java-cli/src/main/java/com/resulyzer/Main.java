package com.resulyzer;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.*;
import java.util.regex.Pattern;

/**
 * Main.java — Resulyzer Java CLI Entry Point
 *
 * <p>Usage:
 * <pre>
 *   java -jar resulyzer.jar &lt;resume-file&gt; &lt;job-role&gt;
 *   Example: java -jar resulyzer.jar /resumes/alice.pdf "Backend"
 * </pre>
 *
 * <p>Flow:
 * <ol>
 *   <li>Validate command-line arguments and file existence.</li>
 *   <li>POST the resume to the Python ML API → get predicted role + resume text.</li>
 *   <li>Query MySQL for required skills of the target role.</li>
 *   <li>Match skills using word-boundary regex (avoids "java" matching "javascript").</li>
 *   <li>Print a formatted report to stdout.</li>
 * </ol>
 */
public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final String ML_API_URL =
            System.getenv().getOrDefault("ML_API_URL", "http://localhost:5000");

    private static final int HTTP_CONNECT_TIMEOUT_MS = 10_000;  // 10 s
    private static final int HTTP_READ_TIMEOUT_MS    = 60_000;  // 60 s (large PDFs take time)
    private static final int MAX_API_RETRIES         = 5;
    private static final int API_RETRY_DELAY_MS      = 3_000;

    public static void main(String[] args) {
        // ── Argument parsing ──────────────────────────────────────────────
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println("Resulyzer Java CLI");
            System.out.println("Usage:  java -jar resulyzer.jar <resume-file> <job-role>");
            System.out.println("Roles:  Backend | Frontend | Data Analyst | DevOps | Full Stack | ML Engineer");
            System.out.println();
            System.out.println("Upload resumes via the web UI at http://localhost:5000");
            System.exit(0);
        }
        if (args.length < 2) {
            System.err.println("Error: provide both <resume-file> and <job-role>");
            System.err.println("Usage: java -jar resulyzer.jar <resume-file> <job-role>");
            System.exit(1);
        }

        String resumePath = args[0];

        // Support multi-word roles passed as separate args: "Data Analyst"
        StringBuilder roleBuilder = new StringBuilder(args[1]);
        for (int i = 2; i < args.length; i++) {
            roleBuilder.append(" ").append(args[i]);
        }
        String targetRole = roleBuilder.toString().trim();

        // ── Validate file ─────────────────────────────────────────────────
        File resumeFile = new File(resumePath);
        if (!resumeFile.exists() || !resumeFile.isFile()) {
            System.err.println("Error: File not found: " + resumePath);
            System.exit(1);
        }

        printBanner(resumeFile.getName(), targetRole);

        // ── Step 1: Call ML API ───────────────────────────────────────────
        String mlJson = callMlApi(resumeFile);
        if (mlJson == null) {
            System.err.println("Error: Could not reach the ML service at " + ML_API_URL);
            System.err.println("Make sure the Python ML container is running.");
            System.exit(1);
        }

        String predictedRole = extractJsonString(mlJson, "predicted_role");
        String resumeText    = extractJsonString(mlJson, "resume_text");

        System.out.printf("  ML Predicted Role : %s%n", predictedRole != null ? predictedRole : "Unknown");
        System.out.printf("  Target Role       : %s%n%n", targetRole);

        // ── Step 2: Load skills from DB ───────────────────────────────────
        Map<String, Double> skills = RoleSkillDAO.getSkillsByRole(targetRole);

        if (skills.isEmpty()) {
            System.out.printf("Warning: No skills found for role '%s'.%n", targetRole);
            System.out.println("Check the role name or the role_skills table in MySQL.");
            System.exit(0);
        }

        // ── Step 3: Match skills ──────────────────────────────────────────
        List<String> matched = new ArrayList<>();
        List<String> missing  = new ArrayList<>();
        double totalWeight   = 0.0;
        double matchedWeight = 0.0;

        String textLower = (resumeText != null) ? resumeText.toLowerCase() : "";

        for (Map.Entry<String, Double> entry : skills.entrySet()) {
            String skill  = entry.getKey();
            double weight = entry.getValue();
            totalWeight += weight;

            if (skillPresent(skill, textLower)) {
                matchedWeight += weight;
                matched.add(skill);
            } else {
                missing.add(skill);
            }
        }

        int score = totalWeight > 0 ? (int) ((matchedWeight / totalWeight) * 100) : 0;

        // ── Step 4: Print report ──────────────────────────────────────────
        printReport(score, matched, missing, predictedRole, targetRole);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers — HTTP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Uploads the resume file to the Python ML API via HTTP multipart/form-data.
     *
     * <p>Retries up to {@value MAX_API_RETRIES} times in case the service
     * is still starting up inside Docker.
     *
     * @param file  The resume file to upload.
     * @return  Raw JSON response body, or {@code null} on failure.
     */
    private static String callMlApi(File file) {
        String boundary = "----ResultBoundary" + System.currentTimeMillis();

        for (int attempt = 1; attempt <= MAX_API_RETRIES; attempt++) {
            try {
                URL url = new URL(ML_API_URL + "/analyze");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);

                // Write multipart body manually (Java has no built-in multipart library)
                try (OutputStream out = conn.getOutputStream()) {
                    // -- Part header
                    write(out, "--" + boundary + "\r\n");
                    write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n");
                    write(out, "Content-Type: application/octet-stream\r\n\r\n");
                    // -- File bytes
                    Files.copy(file.toPath(), out);
                    // -- End boundary
                    write(out, "\r\n--" + boundary + "--\r\n");
                }

                int status = conn.getResponseCode();
                InputStream responseStream = (status >= 200 && status < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();

                String body = readStream(responseStream);
                conn.disconnect();

                if (status >= 200 && status < 300) {
                    return body;
                } else {
                    logger.warning("ML API returned HTTP " + status + ": " + body);
                    return null;
                }

            } catch (ConnectException e) {
                System.out.printf("ML service not ready (attempt %d/%d). Retrying in %ds…%n",
                        attempt, MAX_API_RETRIES, API_RETRY_DELAY_MS / 1000);
                sleep(API_RETRY_DELAY_MS);
            } catch (Exception e) {
                logger.warning("HTTP error on attempt " + attempt + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers — Skill matching
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Checks whether a skill keyword is present in the resume text using
     * whole-word matching.
     *
     * <p>For example, {@code skillPresent("java", "javascript developer")}
     * returns {@code false}, while {@code skillPresent("java", "java developer")}
     * returns {@code true}.
     *
     * @param skill      Skill keyword (already lower-cased in DB seed).
     * @param textLower  Lower-cased resume text.
     */
    private static boolean skillPresent(String skill, String textLower) {
        if (skill == null || skill.isBlank() || textLower.isBlank()) return false;

        // Short acronyms (≤3 chars) or multi-word skills: plain substring match
        if (skill.length() <= 3 || skill.contains(" ")) {
            return textLower.contains(skill);
        }

        // Single long word: word-boundary regex
        String pattern = "\\b" + Pattern.quote(skill) + "\\b";
        return Pattern.compile(pattern).matcher(textLower).find();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers — JSON
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Minimal JSON string value extractor; avoids a 3rd-party dependency
     * while still being correct for the simple flat JSON our API returns.
     *
     * <p>Example: extractJsonString("{\"key\":\"value\"}", "key") → "value"
     */
    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        idx += searchKey.length();
        // Skip whitespace
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length() || json.charAt(idx) != '"') return null;

        idx++; // skip opening quote
        // Handle escaped characters
        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '\\' && idx + 1 < json.length()) {
                sb.append(json.charAt(idx + 1));
                idx += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                idx++;
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers — Output formatting
    // ══════════════════════════════════════════════════════════════════════

    private static void printBanner(String filename, String role) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║       RESULYZER — Resume Analyzer    ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("  File : " + filename);
        System.out.println("  Role : " + role);
        System.out.println();
    }

    private static void printReport(int score, List<String> matched, List<String> missing,
                                    String predictedRole, String targetRole) {
        System.out.println("────────────────────────────────────────");
        System.out.printf(" Selection Score : %d%%%n", score);
        System.out.println("────────────────────────────────────────");

        System.out.printf("%nMatched Skills (%d):%n", matched.size());
        matched.forEach(s -> System.out.println("  ✓  " + s));

        System.out.printf("%nMissing Skills (%d):%n", missing.size());
        missing.forEach(s -> System.out.println("  ✗  " + s));

        // Tip if ML prediction differs from user's target role
        if (predictedRole != null && !predictedRole.equalsIgnoreCase(targetRole)) {
            System.out.printf(
                    "%n💡 Tip: The ML model predicted '%s'—consider also reviewing that role.%n",
                    predictedRole
            );
        }

        System.out.println("\n════════════════════════════════════════");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers — I/O utilities
    // ══════════════════════════════════════════════════════════════════════

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes("UTF-8"));
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
