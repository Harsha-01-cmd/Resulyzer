# ─────────────────────────────────────────────────────────────────────────────
# Resulyzer — Java CLI Dockerfile
# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage build:
#   Stage 1 (build)   — JDK + download MySQL driver + compile Java sources
#   Stage 2 (runtime) — JRE only (smaller image, no compiler)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build

# Install wget to download the MySQL JDBC driver from Maven Central
RUN apk add --no-cache wget

WORKDIR /build

# Download the MySQL Connector/J JAR during image build.
# Pinned to 9.3.0 — available on Maven Central.
RUN wget -q "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.3.0/mysql-connector-j-9.3.0.jar" \
    -O mysql-connector-j.jar

# Copy Java source files into build context
COPY src/main/java/com/resulyzer/ ./src/

# Compile all three Java source files in one pass.
#   -cp mysql-connector-j.jar  → classpath: resolves JDBC imports at compile time
#   -d out                     → compiled .class files go into ./out/
RUN mkdir -p out && \
    javac -cp "mysql-connector-j.jar" -d out \
    src/DBUtil.java src/RoleSkillDAO.java src/Main.java

# ── Build a proper fat JAR ────────────────────────────────────────────────────
# A fat JAR bundles our .class files + the MySQL driver so only one file is needed at runtime.
#
# Steps:
#   1. Create staging dir and extract MySQL connector classes into it
#   2. Copy our compiled .class files (preserving package dirs) into staging
#   3. Write a MANIFEST.MF that declares the Main-Class entry point
#   4. Package everything into resulyzer.jar
RUN mkdir -p jar-staging && \
    cd jar-staging && \
    jar xf /build/mysql-connector-j.jar && \
    # Remove the connector's own MANIFEST so we can supply ours
    rm -f META-INF/MANIFEST.MF && \
    # Copy our compiled class files (they're in com/resulyzer/ inside out/)
    cp -r /build/out/. . && \
    # Write our MANIFEST
    mkdir -p META-INF && \
    printf 'Manifest-Version: 1.0\nMain-Class: com.resulyzer.Main\n' > META-INF/MANIFEST.MF && \
    # Create the fat JAR
    jar cfm /build/resulyzer.jar META-INF/MANIFEST.MF . && \
    cd /build

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# JRE is smaller than JDK — no compiler, just the runtime.
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=build /build/resulyzer.jar .

# Default: print usage. docker-compose overrides this with actual args.
CMD ["java", "-jar", "resulyzer.jar", "--help"]
