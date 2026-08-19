-- Gemini media analysis for Stories/video recommendation (Astro algo-relay)
CREATE TABLE IF NOT EXISTS media_analysis (
    note_id TEXT PRIMARY KEY REFERENCES notes(id) ON DELETE CASCADE,
    media_url TEXT NOT NULL,
    media_type TEXT NOT NULL,
    model TEXT NOT NULL DEFAULT '',
    title TEXT,
    summary TEXT,
    detailed_description TEXT,
    language TEXT,
    topics TEXT[] NOT NULL DEFAULT '{}',
    tags TEXT[] NOT NULL DEFAULT '{}',
    entities TEXT[] NOT NULL DEFAULT '{}',
    scene_labels TEXT[] NOT NULL DEFAULT '{}',
    objects TEXT[] NOT NULL DEFAULT '{}',
    activities TEXT[] NOT NULL DEFAULT '{}',
    colors TEXT[] NOT NULL DEFAULT '{}',
    mood TEXT,
    style TEXT,
    region TEXT,
    is_news BOOLEAN DEFAULT FALSE,
    is_iraq_related BOOLEAN DEFAULT FALSE,
    nsfw_score REAL DEFAULT 0,
    search_text TEXT NOT NULL DEFAULT '',
    raw_response JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'pending',
    error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS media_analysis_status_idx ON media_analysis (status);
CREATE INDEX IF NOT EXISTS media_analysis_iraq_idx ON media_analysis (is_iraq_related) WHERE is_iraq_related = TRUE;
CREATE INDEX IF NOT EXISTS media_analysis_topics_gin ON media_analysis USING GIN (topics);
CREATE INDEX IF NOT EXISTS media_analysis_tags_gin ON media_analysis USING GIN (tags);
CREATE INDEX IF NOT EXISTS media_analysis_updated_idx ON media_analysis (updated_at DESC);
