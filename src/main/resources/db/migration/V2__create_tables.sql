-- 1. Tabela de Usuários (com suporte a Gênero, Dias de Treino e Frequência)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    google_id VARCHAR(255) UNIQUE,
    strava_athlete_id BIGINT UNIQUE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    gender VARCHAR(10) NOT NULL,
    birth_date DATE,
    hr_max INT NOT NULL,
    hr_resting INT NOT NULL,
    weekly_frequency INT NOT NULL DEFAULT 3,
    training_days VARCHAR(100) NOT NULL DEFAULT 'TUESDAY,THURSDAY,SATURDAY',
    target_objective VARCHAR(100) DEFAULT 'METABOLIC_EFFICIENCY',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Tabela de Eventos / Provas Alvo do Atleta
CREATE TABLE IF NOT EXISTS user_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_name VARCHAR(255) NOT NULL,
    event_date DATE NOT NULL,
    target_distance_km DOUBLE NOT NULL,
    is_main_event BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_events_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. Tabela de Histórico de Análises Geradas pela IA
CREATE TABLE IF NOT EXISTS activity_analyses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    strava_activity_id BIGINT UNIQUE NOT NULL,
    analysis_text LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_activity_analyses_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);