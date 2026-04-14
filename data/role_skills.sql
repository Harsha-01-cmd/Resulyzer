-- =============================================================================
-- Resulyzer - Database Initialization Script
-- Creates the resume_analyzer database and populates the role_skills table.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS resume_analyzer;
USE resume_analyzer;

-- role_skills table: stores each skill and its importance weight for each role.
-- weight values are floats; higher = more important for the role
CREATE TABLE IF NOT EXISTS role_skills (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    role      VARCHAR(100) NOT NULL,
    skill     VARCHAR(100) NOT NULL,
    weight    DOUBLE       NOT NULL DEFAULT 1.0,
    UNIQUE KEY uq_role_skill (role, skill)
);

-- resumes table: optional persistence of analyzed resumes (for history/audit)
CREATE TABLE IF NOT EXISTS resume_results (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    filename        VARCHAR(255)  NOT NULL,
    predicted_role  VARCHAR(100),
    target_role     VARCHAR(100),
    selection_score INT,
    analyzed_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ─────────────────────────────────────────────────────────────────────────────
-- SEED DATA
-- ─────────────────────────────────────────────────────────────────────────────

-- Backend Developer
INSERT INTO role_skills (role, skill, weight) VALUES
('Backend', 'java',          2.5),
('Backend', 'python',        2.0),
('Backend', 'spring',        2.5),
('Backend', 'spring boot',   3.0),
('Backend', 'jdbc',          1.8),
('Backend', 'hibernate',     1.8),
('Backend', 'mysql',         2.0),
('Backend', 'postgresql',    2.0),
('Backend', 'mongodb',       1.8),
('Backend', 'rest api',      2.5),
('Backend', 'graphql',       2.0),
('Backend', 'microservices', 2.5),
('Backend', 'docker',        2.0),
('Backend', 'kubernetes',    1.8),
('Backend', 'aws',           2.0),
('Backend', 'redis',         1.8),
('Backend', 'kafka',         1.8),
('Backend', 'unit testing',  1.6),
('Backend', 'git',           1.5);

-- Frontend Developer
INSERT INTO role_skills (role, skill, weight) VALUES
('Frontend', 'html',             2.0),
('Frontend', 'css',              2.0),
('Frontend', 'javascript',       3.0),
('Frontend', 'typescript',       2.5),
('Frontend', 'react',            3.0),
('Frontend', 'next.js',          2.5),
('Frontend', 'angular',          2.5),
('Frontend', 'vue',              2.5),
('Frontend', 'redux',            2.0),
('Frontend', 'webpack',          1.8),
('Frontend', 'responsive design',2.0),
('Frontend', 'tailwind',         2.0),
('Frontend', 'sass',             1.8),
('Frontend', 'accessibility',    1.8),
('Frontend', 'jest',             1.8),
('Frontend', 'figma',            1.5),
('Frontend', 'performance',      1.8),
('Frontend', 'git',              1.5);

-- Data Analyst
INSERT INTO role_skills (role, skill, weight) VALUES
('Data Analyst', 'python',             2.5),
('Data Analyst', 'sql',               3.0),
('Data Analyst', 'pandas',            2.5),
('Data Analyst', 'numpy',             2.0),
('Data Analyst', 'matplotlib',        1.8),
('Data Analyst', 'tableau',           2.5),
('Data Analyst', 'power bi',          2.5),
('Data Analyst', 'excel',             2.0),
('Data Analyst', 'statistics',        2.5),
('Data Analyst', 'data visualization',2.0),
('Data Analyst', 'machine learning',  2.0),
('Data Analyst', 'jupyter',           1.8),
('Data Analyst', 'r',                 1.8),
('Data Analyst', 'data cleaning',     2.0),
('Data Analyst', 'a/b testing',       1.8),
('Data Analyst', 'google analytics',  1.6);

-- DevOps Engineer
INSERT INTO role_skills (role, skill, weight) VALUES
('DevOps', 'docker',        3.0),
('DevOps', 'kubernetes',    3.0),
('DevOps', 'terraform',     2.5),
('DevOps', 'aws',           2.5),
('DevOps', 'azure',         2.0),
('DevOps', 'gcp',           2.0),
('DevOps', 'jenkins',       2.5),
('DevOps', 'ci cd',         2.5),
('DevOps', 'linux',         2.0),
('DevOps', 'bash',          1.8),
('DevOps', 'ansible',       2.0),
('DevOps', 'prometheus',    2.0),
('DevOps', 'grafana',       2.0),
('DevOps', 'helm',          1.8),
('DevOps', 'nginx',         1.8),
('DevOps', 'git',           1.5),
('DevOps', 'python',        1.5);

-- Full Stack Developer
INSERT INTO role_skills (role, skill, weight) VALUES
('Full Stack', 'java',        2.0),
('Full Stack', 'python',      2.0),
('Full Stack', 'javascript',  2.5),
('Full Stack', 'typescript',  2.0),
('Full Stack', 'react',       2.5),
('Full Stack', 'node.js',     2.5),
('Full Stack', 'html',        1.8),
('Full Stack', 'css',         1.8),
('Full Stack', 'mysql',       2.0),
('Full Stack', 'mongodb',     2.0),
('Full Stack', 'docker',      2.0),
('Full Stack', 'rest api',    2.5),
('Full Stack', 'aws',         1.8),
('Full Stack', 'git',         1.5);

-- Machine Learning Engineer
INSERT INTO role_skills (role, skill, weight) VALUES
('ML Engineer', 'python',         3.0),
('ML Engineer', 'tensorflow',     2.5),
('ML Engineer', 'pytorch',        2.5),
('ML Engineer', 'scikit-learn',   2.5),
('ML Engineer', 'deep learning',  2.5),
('ML Engineer', 'nlp',            2.0),
('ML Engineer', 'computer vision',2.0),
('ML Engineer', 'pandas',         2.0),
('ML Engineer', 'numpy',          2.0),
('ML Engineer', 'sql',            1.8),
('ML Engineer', 'docker',         1.8),
('ML Engineer', 'mlflow',         1.8),
('ML Engineer', 'aws',            1.8),
('ML Engineer', 'cuda',           1.8),
('ML Engineer', 'statistics',     2.0),
('ML Engineer', 'git',            1.5);
