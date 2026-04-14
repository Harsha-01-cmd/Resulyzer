package com.resulyzer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Logger;

/**
 * DBUtil.java — Database connection factory for the Resulyzer Java CLI.
 *
 * <p>Reads connection parameters from environment variables so the same
 * binary works locally and inside Docker without code changes.
 *
 * <p>Includes a retry loop because MySQL may take several seconds to
 * become ready after the Docker container starts.
 */
public final class DBUtil {

    private static final Logger logger = Logger.getLogger(DBUtil.class.getName());

    // ── Connection parameters (read from env, fall back to local defaults) ──
    private static final String URL =
            System.getenv().getOrDefault(
                    "DB_URL",
                    "jdbc:mysql://127.0.0.1:3307/resume_analyzer?useSSL=false&allowPublicKeyRetrieval=true"
            );

    private static final String USER     = System.getenv().getOrDefault("DB_USER",     "appuser");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "apppass");

    private static final int MAX_RETRIES    = 10;
    private static final int RETRY_DELAY_MS = 3_000;  // 3 seconds between retries

    // Load the JDBC driver class once when the class is first referenced
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                    "MySQL JDBC driver not found. Ensure mysql-connector-j.jar is on the classpath."
            );
        }
    }

    // Prevent instantiation — this is a utility class
    private DBUtil() {}

    /**
     * Returns an open {@link Connection} to the database.
     *
     * <p>Retries {@value MAX_RETRIES} times, waiting
     * {@value RETRY_DELAY_MS} ms between attempts, before throwing.
     *
     * @throws RuntimeException if all connection attempts fail
     */
    public static Connection getConnection() {
        Exception lastCause = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
                if (attempt > 1) {
                    logger.info("Connected to DB on attempt " + attempt);
                }
                return conn;
            } catch (Exception e) {
                lastCause = e;
                logger.warning(String.format(
                        "DB connection attempt %d/%d failed: %s — retrying in %ds",
                        attempt, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000
                ));
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException(
                "Could not connect to database after " + MAX_RETRIES + " attempts.", lastCause
        );
    }
}
