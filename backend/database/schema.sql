-- ============================================================================
-- AIntiRADAR Uzbekistan - Geospatial Database Schema
-- PostgreSQL 15+ with PostGIS 3.3+
-- High-Performance Radar FOV & Crowdsourced Hazard Geotagging
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";

-- ----------------------------------------------------------------------------
-- 1. ENUMS
-- ----------------------------------------------------------------------------
CREATE TYPE radar_type_enum AS ENUM (
    'STATIC_RADAR',               -- Cordon, Multaradar, etc.
    'AUTOCON_SPEED',              -- AutoCon tezlik nazorati
    'AUTOCON_RED_LIGHT',          -- AutoCon qizil chiroq & to'xtash chizig'i
    'AUTOCON_BUS_LANE',           -- Jamoat transporti yo'lagi (A chiziq)
    'SECTOR_AVERAGE_START',       -- O'rtacha tezlik hisoblash boshlang'ich kamerasi
    'SECTOR_AVERAGE_END',         -- O'rtacha tezlik hisoblash yakuniy kamerasi
    'TRIPOD_RADAR',               -- 3 oyoqli mobil tripod radar
    'MOBILE_PATROL'               -- Harakatdagi YPX Cobalt/Lacetti radarlari
);

CREATE TYPE shooting_mode_enum AS ENUM (
    'INCOMING',                   -- Avtomobil peshonasidan suratga oluvchi
    'OUTGOING',                   -- Orqa tomondan (avtomobil ketidan) suratga oluvchi
    'BIDIRECTIONAL'               -- Ikkala yo'nalishni ham nazorat qiluvchi
);

CREATE TYPE hazard_type_enum AS ENUM (
    'TRIPOD_RADAR',               -- 3 oyoqli radar
    'YPX_POST_MOBILE',            -- Ko'chma YPX posti / reyd
    'YPX_STATIONARY_POST',        -- Statsionar YPX maskani (Chinor, Sirdayo...)
    'ACCIDENT',                   -- Yo'l-transport hodisasi (YTH)
    'ROAD_DEFECT',                -- Chuqurlik, xavfli yo'l qoplamasi
    'BAD_WEATHER_HAZARD',         -- Qora muzlama (gololed), kuchli tuman
    'SPEED_BUMP'                  -- Sun'iy yo'l notekisligi (lying policeman)
);

CREATE TYPE report_status_enum AS ENUM (
    'ACTIVE',
    'EXPIRED',
    'DISMISSED'
);

-- ----------------------------------------------------------------------------
-- 2. RADARS TABLE (Statik va Doimiy Kameralar)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS radars (
    id                      BIGSERIAL PRIMARY KEY,
    external_code           VARCHAR(64) UNIQUE,                 -- YPX yoki rasmiy reyestr kodi
    name                    VARCHAR(255) NOT NULL,              -- Masalan: "Toshkent KHAY - Bunyodkor chorrahasi"
    radar_type              radar_type_enum NOT NULL DEFAULT 'STATIC_RADAR',
    speed_limit             SMALLINT NOT NULL CHECK (speed_limit BETWEEN 20 AND 150),
    azimuth                 DOUBLE PRECISION NOT NULL CHECK (azimuth >= 0.0 AND azimuth < 360.0), -- Gradus (0°=Shimol, 90°=Sharq)
    fov_angle               DOUBLE PRECISION NOT NULL DEFAULT 40.0 CHECK (fov_angle > 0 AND fov_angle <= 180.0), -- Ko'rish burchagi
    range_meters            DOUBLE PRECISION NOT NULL DEFAULT 120.0 CHECK (range_meters > 0 AND range_meters <= 1000.0), -- Ko'rish masofasi
    shooting_mode           shooting_mode_enum NOT NULL DEFAULT 'INCOMING',
    location                GEOMETRY(Point, 4326) NOT NULL,     -- WGS84 koordinatalari (Lon, Lat)
    city_region             VARCHAR(100) NOT NULL,              -- Masalan: "Toshkent shahri", "Samarqand viloyati"
    is_active               BOOLEAN NOT NULL DEFAULT true,
    last_verified_at        TIMESTAMPTZ DEFAULT NOW(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- PostGIS Spasial indeks va filtr indekslari
CREATE INDEX idx_radars_location_gist ON radars USING GIST (location);
CREATE INDEX idx_radars_active_speed ON radars (is_active, speed_limit);
CREATE INDEX idx_radars_azimuth ON radars (azimuth);

-- ----------------------------------------------------------------------------
-- 3. AVERAGE SPEED SECTORS TABLE (O'rtacha tezlik nazorat segmentlari)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS average_speed_sectors (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                    VARCHAR(255) NOT NULL,              -- Masalan: "M39 Toshkent - Chinoz 12km sektori"
    start_radar_id          BIGINT NOT NULL REFERENCES radars(id) ON DELETE RESTRICT,
    end_radar_id            BIGINT NOT NULL REFERENCES radars(id) ON DELETE RESTRICT,
    speed_limit             SMALLINT NOT NULL CHECK (speed_limit BETWEEN 30 AND 140),
    total_distance_meters   DOUBLE PRECISION NOT NULL CHECK (total_distance_meters > 0),
    minimum_allowed_seconds INTEGER GENERATED ALWAYS AS (
        ROUND((total_distance_meters / (speed_limit * 1000.0 / 3600.0)))
    ) STORED,
    route_geom              GEOMETRY(LineString, 4326),
    is_active               BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sectors_route_gist ON average_speed_sectors USING GIST (route_geom);

-- ----------------------------------------------------------------------------
-- 4. CROWDSOURCED HAZARD REPORTS TABLE (Ijtimoiy Geotagging & TTL)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crowdsourced_reports (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    hazard_type             hazard_type_enum NOT NULL,
    location                GEOMETRY(Point, 4326) NOT NULL,
    heading                 DOUBLE PRECISION CHECK (heading IS NULL OR (heading >= 0 AND heading < 360)),
    description             TEXT,
    reporter_user_id        UUID NOT NULL,
    reputation_weight       DOUBLE PRECISION NOT NULL DEFAULT 1.0 CHECK (reputation_weight >= 0.1 AND reputation_weight <= 10.0),
    upvotes                 INTEGER NOT NULL DEFAULT 1,
    downvotes               INTEGER NOT NULL DEFAULT 0,
    confidence_score        DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    status                  report_status_enum NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reports_location_gist ON crowdsourced_reports USING GIST (location);
CREATE INDEX idx_reports_active_ttl ON crowdsourced_reports (status, expires_at);

-- ----------------------------------------------------------------------------
-- 5. USER REPORT VOTES TABLE (Dublikat ovozlarning oldini olish)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS report_votes (
    report_id               UUID NOT NULL REFERENCES crowdsourced_reports(id) ON DELETE CASCADE,
    user_id                 UUID NOT NULL,
    is_positive             BOOLEAN NOT NULL,                   -- true = "Ha, bor", false = "Yo'q, olib ketilgan"
    voted_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (report_id, user_id)
);

-- ----------------------------------------------------------------------------
-- 6. STORED PROCEDURES & SPATIAL FUNCTIONS
-- ----------------------------------------------------------------------------

-- A) Foydalanuvchi atrofidagi radarlarni tezkor qaytaruvchi PostGIS funksiya
CREATE OR REPLACE FUNCTION fn_get_nearby_radars(
    p_user_lon DOUBLE PRECISION,
    p_user_lat DOUBLE PRECISION,
    p_radius_meters DOUBLE PRECISION DEFAULT 2000.0
)
RETURNS TABLE (
    radar_id BIGINT,
    external_code VARCHAR,
    name VARCHAR,
    radar_type radar_type_enum,
    speed_limit SMALLINT,
    azimuth DOUBLE PRECISION,
    fov_angle DOUBLE PRECISION,
    range_meters DOUBLE PRECISION,
    shooting_mode shooting_mode_enum,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    distance_meters DOUBLE PRECISION
)
LANGUAGE sql
STABLE
AS $$
    SELECT 
        r.id AS radar_id,
        r.external_code,
        r.name,
        r.radar_type,
        r.speed_limit,
        r.azimuth,
        r.fov_angle,
        r.range_meters,
        r.shooting_mode,
        ST_Y(r.location) AS latitude,
        ST_X(r.location) AS longitude,
        ST_Distance(
            r.location::geography,
            ST_SetSRID(ST_MakePoint(p_user_lon, p_user_lat), 4326)::geography
        ) AS distance_meters
    FROM radars r
    WHERE r.is_active = true
      AND ST_DWithin(
            r.location::geography,
            ST_SetSRID(ST_MakePoint(p_user_lon, p_user_lat), 4326)::geography,
            p_radius_meters
          )
    ORDER BY distance_meters ASC;
$$;

-- B) Crowdsourced xavfga ovoz berish va TTL/Confidence dinamik hisoblash
CREATE OR REPLACE FUNCTION fn_vote_hazard_report(
    p_report_id UUID,
    p_user_id UUID,
    p_is_positive BOOLEAN
)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_report RECORD;
    v_new_confidence DOUBLE PRECISION;
    v_new_status report_status_enum;
    v_new_expires_at TIMESTAMPTZ;
BEGIN
    -- 1. Avvalgi ovozini tekshirish
    IF EXISTS (SELECT 1 FROM report_votes WHERE report_id = p_report_id AND user_id = p_user_id) THEN
        RETURN jsonb_build_object('success', false, 'message', 'Siz allaqachon ovoz bergansiz');
    END IF;

    -- 2. Ovozni yozish
    INSERT INTO report_votes (report_id, user_id, is_positive)
    VALUES (p_report_id, p_user_id, p_is_positive);

    -- 3. Reportni yangilash
    IF p_is_positive THEN
        UPDATE crowdsourced_reports
        SET upvotes = upvotes + 1,
            expires_at = expires_at + INTERVAL '30 minutes' -- Har bir tasdiq TTLni uzaytiradi (maksimum 4 soatgacha)
        WHERE id = p_report_id
        RETURNING * INTO v_report;
    ELSE
        UPDATE crowdsourced_reports
        SET downvotes = downvotes + 1,
            expires_at = expires_at - INTERVAL '20 minutes'
        WHERE id = p_report_id
        RETURNING * INTO v_report;
    END IF;

    -- 4. Wilson Score / Bayes ishonchlilik koeffitsienti
    -- Agar salbiy ovozlar ijobiydan ancha ko'payib ketsa, status DISMISSED bo'ladi
    v_new_confidence := (v_report.upvotes::DOUBLE PRECISION + 1.0) / 
                        (v_report.upvotes + v_report.downvotes + 2.0);

    IF v_report.downvotes >= 3 AND (v_report.downvotes > v_report.upvotes * 2) THEN
        v_new_status := 'DISMISSED';
    ELSIF v_report.expires_at <= NOW() THEN
        v_new_status := 'EXPIRED';
    ELSE
        v_new_status := 'ACTIVE';
    END IF;

    UPDATE crowdsourced_reports
    SET confidence_score = v_new_confidence,
        status = v_new_status
    WHERE id = p_report_id;

    RETURN jsonb_build_object(
        'success', true,
        'report_id', p_report_id,
        'status', v_new_status,
        'confidence', v_new_confidence,
        'upvotes', v_report.upvotes,
        'downvotes', v_report.downvotes
    );
END;
$$;

-- C) Expire bo'lgan xabarlarni avtomatik tozalash (Background cron uchun)
CREATE OR REPLACE PROCEDURE sp_cleanup_expired_reports()
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE crowdsourced_reports
    SET status = 'EXPIRED'
    WHERE status = 'ACTIVE' AND expires_at < NOW();
END;
$$;
