package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lib/pq"
	"github.com/nbd-wtf/go-nostr"
)

var (
	mediaAnalysisSem     chan struct{}
	mediaAnalysisOnce    sync.Once
	mediaAnalysisWorkers = 2
	mediaAnalyzing       sync.Map // note_id -> struct{} in-flight de-dupe
)

// Default ME strfry (Astro Middle-East filter). Override with ME_RELAY_URL.
const defaultMERelayURL = "wss://desktop-r082364.tail4b3c94.ts.net/"

func meRelayURL() string {
	if v := strings.TrimSpace(os.Getenv("ME_RELAY_URL")); v != "" {
		return v
	}
	return defaultMERelayURL
}

func isMERelayURL(raw string) bool {
	a := normalizeRelayURL(raw)
	b := normalizeRelayURL(meRelayURL())
	return a != "" && a == b
}

func normalizeRelayURL(u string) string {
	u = strings.TrimSpace(strings.ToLower(u))
	u = strings.TrimSuffix(u, "/")
	return u
}

func isMiddleEastRelevant(a *MediaAnalysisResult) bool {
	if a == nil {
		return false
	}
	if a.IsIraqRelated {
		return true
	}
	region := strings.ToLower(strings.TrimSpace(a.Region))
	switch region {
	case "iraq", "baghdad", "basra", "erbil", "mosul", "najaf", "karbala",
		"kurdistan", "middle-east", "middle_east", "middle east", "levant",
		"syria", "lebanon", "jordan", "palestine", "gulf", "saudi", "uae",
		"kuwait", "yemen", "oman", "qatar", "bahrain", "iran", "turkey", "egypt":
		return true
	}
	for _, t := range append(append([]string{}, a.Topics...), a.Tags...) {
		low := strings.ToLower(t)
		if strings.Contains(low, "iraq") || strings.Contains(low, "baghdad") ||
			strings.Contains(low, "العراق") || strings.Contains(low, "بغداد") ||
			strings.Contains(low, "شرق") || strings.Contains(low, "middle-east") ||
			strings.Contains(low, "middle east") {
			return true
		}
	}
	return false
}

func initMediaAnalysisWorkers() {
	mediaAnalysisOnce.Do(func() {
		n := mediaAnalysisWorkers
		if v := strings.TrimSpace(os.Getenv("GEMINI_WORKERS")); v != "" {
			if parsed, err := strconv.Atoi(v); err == nil && parsed > 0 && parsed <= 8 {
				n = parsed
			}
		}
		mediaAnalysisSem = make(chan struct{}, n)
		log.Printf("Gemini media analysis workers=%d enabled=%v", n, geminiEnabled())
	})
}

func isMediaKind(kind int) bool {
	switch kind {
	case 20, 21, 22, 1063, 34235, 34236, 30923:
		return true
	default:
		return false
	}
}

func extractMediaFromEvent(ev *nostr.Event) (mediaURL, mime, mediaType string) {
	// Prefer explicit url tag (NIP-94 FileHeader)
	for _, t := range ev.Tags {
		if len(t) >= 2 && t[0] == "url" && looksLikeHTTP(t[1]) {
			mediaURL = t[1]
			break
		}
	}
	// imeta url / m
	if mediaURL == "" {
		for _, t := range ev.Tags {
			if len(t) < 2 || t[0] != "imeta" {
				continue
			}
			var u, m string
			for _, part := range t[1:] {
				if strings.HasPrefix(part, "url ") {
					u = strings.TrimSpace(strings.TrimPrefix(part, "url "))
				}
				if strings.HasPrefix(part, "m ") {
					m = strings.TrimSpace(strings.TrimPrefix(part, "m "))
				}
			}
			if looksLikeHTTP(u) {
				mediaURL = u
				mime = m
				break
			}
		}
	}
	// mime tag
	if mime == "" {
		for _, t := range ev.Tags {
			if len(t) >= 2 && t[0] == "m" {
				mime = t[1]
			}
		}
	}
	// fallback: first media-looking URL in content
	if mediaURL == "" {
		for _, tok := range strings.Fields(ev.Content) {
			tok = strings.Trim(tok, "<>\"'")
			if looksLikeMediaURL(tok) {
				mediaURL = tok
				break
			}
		}
	}
	if mediaURL == "" {
		return "", "", ""
	}
	if mime == "" {
		mime = guessMimeFromURL(mediaURL)
	}
	if strings.HasPrefix(mime, "video/") || isVideoURL(mediaURL) {
		mediaType = "video"
	} else {
		mediaType = "image"
	}
	return mediaURL, mime, mediaType
}

func looksLikeHTTP(s string) bool {
	return strings.HasPrefix(s, "http://") || strings.HasPrefix(s, "https://")
}

func looksLikeMediaURL(s string) bool {
	if !looksLikeHTTP(s) {
		return false
	}
	low := strings.ToLower(s)
	if strings.Contains(low, "news.google.") {
		return false
	}
	return isImageURL(low) || isVideoURL(low) || strings.Contains(low, "/download/videos/")
}

func isImageURL(low string) bool {
	for _, ext := range []string{".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".jfif"} {
		if strings.Contains(low, ext) {
			return true
		}
	}
	return false
}

func isVideoURL(low string) bool {
	for _, ext := range []string{".mp4", ".webm", ".mov", ".m4v", ".mpeg"} {
		if strings.Contains(low, ext) {
			return true
		}
	}
	return strings.Contains(low, "/download/videos/")
}

func guessMimeFromURL(u string) string {
	low := strings.ToLower(u)
	switch {
	case strings.Contains(low, ".png"):
		return "image/png"
	case strings.Contains(low, ".webp"):
		return "image/webp"
	case strings.Contains(low, ".gif"):
		return "image/gif"
	case strings.Contains(low, ".webm"):
		return "video/webm"
	case strings.Contains(low, ".mp4"), strings.Contains(low, "/download/videos/"):
		return "video/mp4"
	default:
		if isVideoURL(low) {
			return "video/mp4"
		}
		return "image/jpeg"
	}
}

// scheduleMediaAnalysis queues Gemini analysis for EVERY NEW note/media on the ME relay.
// Live ingest only (caller uses Since=now). No historical backfill.
// Everything that enters ME is analyzed and stored — we do NOT skip by hashtags or region guess
// (the ME strfry is already the Middle-East gate).
func scheduleMediaAnalysis(ev *nostr.Event) {
	if !geminiEnabled() {
		return
	}
	// Analyze posts + media kinds only (not reactions/zaps/follows).
	if !isMediaKind(ev.Kind) && ev.Kind != nostr.KindTextNote && ev.Kind != nostr.KindArticle {
		return
	}
	initMediaAnalysisWorkers()

	mediaURL, mime, mediaType := extractMediaFromEvent(ev)
	if mediaURL == "" {
		mediaURL = "text://note/" + ev.ID
		mediaType = "text"
		mime = "text/plain"
	} else if strings.Contains(strings.ToLower(mediaURL), "youtube.com") ||
		strings.Contains(strings.ToLower(mediaURL), "youtu.be") ||
		strings.Contains(strings.ToLower(mediaURL), "news.google.") {
		// Still analyze via caption/URL text — never let ME content pass unanalyzed.
		mediaType = "text"
		mime = "text/plain"
	}

	go func(event nostr.Event, url, mimeType, mType string) {
		if _, loaded := mediaAnalyzing.LoadOrStore(event.ID, struct{}{}); loaded {
			return
		}
		defer mediaAnalyzing.Delete(event.ID)

		mediaAnalysisSem <- struct{}{}
		defer func() { <-mediaAnalysisSem }()

		ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
		defer cancel()

		if done, _ := repository.mediaAnalysisAlreadyDone(event.ID); done {
			return
		}

		// Reuse basket / prior analysis for the same media URL when available.
		if copied, _ := repository.copyMediaAnalysisByURL(event.ID, url); copied {
			log.Printf("media_analysis reused by url note=%s", event.ID[:8])
			return
		}

		if err := repository.upsertMediaAnalysisPending(event.ID, url, mType); err != nil {
			log.Printf("media_analysis pending: %v", err)
			return
		}

		usedFallback := false
		var result *MediaAnalysisResult
		var raw string
		var err error
		if mType == "text" || strings.HasPrefix(url, "text://") {
			result, raw, err = analyzeTextWithGemini(ctx, event.Content)
		} else {
			result, raw, err = analyzeMediaWithGemini(ctx, url, mimeType, event.Content)
		}
		if err != nil {
			log.Printf("gemini analyze fail note=%s: %v — caption fallback", event.ID[:8], err)
			result, raw = captionFallbackAnalysis(event.Content + " " + url)
			usedFallback = true
		}

		status := analysisStatusForResult(result, usedFallback)
		errMsg := ""
		if status == "failed" {
			errMsg = "no meaningful topics from analysis"
		}
		if saveErr := repository.saveMediaAnalysisWithStatus(event.ID, url, mType, geminiModel(), result, raw, status, errMsg); saveErr != nil {
			log.Printf("media_analysis save: %v", saveErr)
			return
		}
		log.Printf("gemini %s note=%s type=%s topics=%v iraq=%v region=%s",
			status, event.ID[:8], mType, result.Topics, result.IsIraqRelated, result.Region)
	}(*ev, mediaURL, mime, mediaType)
}

func (r *NostrRepository) upsertMediaAnalysisPending(noteID, mediaURL, mediaType string) error {
	_, err := r.db.Exec(`
		INSERT INTO media_analysis (note_id, media_url, media_type, status, updated_at)
		VALUES ($1, $2, $3, 'pending', NOW())
		ON CONFLICT (note_id) DO UPDATE SET
			media_url = EXCLUDED.media_url,
			media_type = EXCLUDED.media_type,
			status = CASE WHEN media_analysis.status = 'done' THEN media_analysis.status ELSE 'pending' END,
			updated_at = NOW()
		WHERE media_analysis.status IS DISTINCT FROM 'done'
	`, noteID, mediaURL, mediaType)
	return err
}

func (r *NostrRepository) mediaAnalysisAlreadyDone(noteID string) (bool, error) {
	var status string
	err := r.db.QueryRow(`SELECT status FROM media_analysis WHERE note_id = $1`, noteID).Scan(&status)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return status == "done" || status == "done_degraded", nil
}

func (r *NostrRepository) copyMediaAnalysisByURL(noteID, mediaURL string) (bool, error) {
	if mediaURL == "" || strings.HasPrefix(mediaURL, "text://") {
		return false, nil
	}
	res, err := r.db.Exec(`
		INSERT INTO media_analysis (
			note_id, media_url, media_type, model,
			title, summary, detailed_description, language,
			topics, tags, entities, scene_labels, objects, activities, colors,
			mood, style, region, is_news, is_iraq_related, nsfw_score,
			search_text, raw_response, status, error, updated_at, created_at
		)
		SELECT $1, s.media_url, s.media_type, s.model,
			s.title, s.summary, s.detailed_description, s.language,
			s.topics, s.tags, s.entities, s.scene_labels, s.objects, s.activities, s.colors,
			s.mood, s.style, s.region, s.is_news, s.is_iraq_related, s.nsfw_score,
			s.search_text, s.raw_response, s.status, s.error, NOW(), NOW()
		FROM (
			SELECT *
			FROM media_analysis
			WHERE media_url = $2 AND status IN ('done', 'done_degraded')
			ORDER BY updated_at DESC
			LIMIT 1
		) s
		ON CONFLICT (note_id) DO NOTHING
	`, noteID, mediaURL)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

func (r *NostrRepository) saveMediaAnalysis(noteID, mediaURL, mediaType, model string, a *MediaAnalysisResult, rawJSON string) error {
	return r.saveMediaAnalysisWithStatus(noteID, mediaURL, mediaType, model, a, rawJSON, "done", "")
}

func (r *NostrRepository) saveMediaAnalysisWithStatus(
	noteID, mediaURL, mediaType, model string,
	a *MediaAnalysisResult,
	rawJSON string,
	status string,
	errMsg string,
) error {
	raw := json.RawMessage([]byte(rawJSON))
	if !json.Valid(raw) {
		b, _ := json.Marshal(a)
		raw = b
	}
	if status == "" {
		status = "done"
	}
	var errPtr interface{}
	if errMsg != "" {
		errPtr = errMsg
	}
	search := buildSearchText(a)
	_, err := r.db.Exec(`
		INSERT INTO media_analysis (
			note_id, media_url, media_type, model,
			title, summary, detailed_description, language,
			topics, tags, entities, scene_labels, objects, activities, colors,
			mood, style, region, is_news, is_iraq_related, nsfw_score,
			search_text, raw_response, status, error, updated_at
		) VALUES (
			$1,$2,$3,$4,
			$5,$6,$7,$8,
			$9,$10,$11,$12,$13,$14,$15,
			$16,$17,$18,$19,$20,$21,
			$22,$23,$24,$25,NOW()
		)
		ON CONFLICT (note_id) DO UPDATE SET
			media_url = EXCLUDED.media_url,
			media_type = EXCLUDED.media_type,
			model = EXCLUDED.model,
			title = EXCLUDED.title,
			summary = EXCLUDED.summary,
			detailed_description = EXCLUDED.detailed_description,
			language = EXCLUDED.language,
			topics = EXCLUDED.topics,
			tags = EXCLUDED.tags,
			entities = EXCLUDED.entities,
			scene_labels = EXCLUDED.scene_labels,
			objects = EXCLUDED.objects,
			activities = EXCLUDED.activities,
			colors = EXCLUDED.colors,
			mood = EXCLUDED.mood,
			style = EXCLUDED.style,
			region = EXCLUDED.region,
			is_news = EXCLUDED.is_news,
			is_iraq_related = EXCLUDED.is_iraq_related,
			nsfw_score = EXCLUDED.nsfw_score,
			search_text = EXCLUDED.search_text,
			raw_response = EXCLUDED.raw_response,
			status = EXCLUDED.status,
			error = EXCLUDED.error,
			updated_at = NOW();
	`,
		noteID, mediaURL, mediaType, model,
		a.Title, a.Summary, a.DetailedDescription, a.Language,
		pq.Array(a.Topics), pq.Array(a.Tags), pq.Array(a.Entities),
		pq.Array(a.SceneLabels), pq.Array(a.Objects), pq.Array(a.Activities), pq.Array(a.Colors),
		a.Mood, a.Style, a.Region, a.IsNews, a.IsIraqRelated, a.NSFWScore,
		search, raw, status, errPtr,
	)
	return err
}

// GetSimilarMediaNotes finds notes similar to what the user recently engaged with
// (reactions / zaps / comments) AND their own recent posts — using Gemini analysis
// fields. Ultra-generic tokens like "general" are ignored so caption-fallback rows
// do not match the entire corpus.
func (r *NostrRepository) GetSimilarMediaNotes(ctx context.Context, userID string, limit, kind int) ([]FeedNote, error) {
	if limit <= 0 {
		limit = 40
	}

	rows, err := r.db.QueryContext(ctx, `
		WITH engaged AS (
			SELECT DISTINCT note_id FROM (
				SELECT note_id FROM reactions WHERE reactor_id = $1
					AND created_at > NOW() - INTERVAL '30 days'
				UNION ALL
				SELECT note_id FROM zaps WHERE zapper_id = $1
					AND created_at > NOW() - INTERVAL '30 days'
				UNION ALL
				SELECT note_id FROM comments WHERE commenter_id = $1
					AND created_at > NOW() - INTERVAL '30 days'
				UNION ALL
				SELECT id AS note_id FROM notes WHERE author_id = $1
					AND created_at > NOW() - INTERVAL '30 days'
					AND kind IN (1, 20, 21, 22, 1063, 34235, 34236, 30923, 30023)
			) x
			WHERE note_id IS NOT NULL AND note_id <> ''
		),
		topic_prefs AS (
			SELECT DISTINCT lower(t) AS topic
			FROM media_analysis ma
			CROSS JOIN LATERAL unnest(ma.topics) AS t
			WHERE ma.status IN ('done', 'done_degraded')
			  AND ma.note_id IN (SELECT note_id FROM engaged)
			  AND lower(t) NOT IN ('general', 'unknown', 'unknown content', 'fيديو', 'فيديو', 'video', 'post', 'text')
		),
		tag_prefs AS (
			SELECT DISTINCT lower(t) AS tag
			FROM media_analysis ma
			CROSS JOIN LATERAL unnest(ma.tags) AS t
			WHERE ma.status IN ('done', 'done_degraded')
			  AND ma.note_id IN (SELECT note_id FROM engaged)
			  AND lower(t) NOT IN ('general', 'unknown', 'unknown content', 'فيديو', 'video', 'post', 'text')
		),
		object_prefs AS (
			SELECT DISTINCT lower(t) AS obj
			FROM media_analysis ma
			CROSS JOIN LATERAL unnest(ma.objects) AS t
			WHERE ma.status IN ('done', 'done_degraded')
			  AND ma.note_id IN (SELECT note_id FROM engaged)
			  AND lower(t) NOT IN ('general', 'unknown')
		),
		activity_prefs AS (
			SELECT DISTINCT lower(t) AS act
			FROM media_analysis ma
			CROSS JOIN LATERAL unnest(ma.activities) AS t
			WHERE ma.status IN ('done', 'done_degraded')
			  AND ma.note_id IN (SELECT note_id FROM engaged)
			  AND lower(t) NOT IN ('general', 'unknown')
		),
		entity_prefs AS (
			SELECT DISTINCT lower(t) AS ent
			FROM media_analysis ma
			CROSS JOIN LATERAL unnest(ma.entities) AS t
			WHERE ma.status IN ('done', 'done_degraded')
			  AND ma.note_id IN (SELECT note_id FROM engaged)
			  AND lower(t) NOT IN ('general', 'unknown')
		),
		scene_prefs AS (
			SELECT DISTINCT lower(t) AS scene
			FROM media_analysis ma
			CROSS JOIN LATERAL unnest(ma.scene_labels) AS t
			WHERE ma.status IN ('done', 'done_degraded')
			  AND ma.note_id IN (SELECT note_id FROM engaged)
			  AND lower(t) NOT IN ('general', 'unknown')
		)
		SELECT n.id, n.raw_json,
			(
				(SELECT count(*)::float FROM unnest(ma.topics) t WHERE lower(t) IN (SELECT topic FROM topic_prefs)) * 4.0 +
				(SELECT count(*)::float FROM unnest(ma.tags) t WHERE lower(t) IN (SELECT tag FROM tag_prefs)) * 3.0 +
				(SELECT count(*)::float FROM unnest(ma.objects) t WHERE lower(t) IN (SELECT obj FROM object_prefs)) * 3.5 +
				(SELECT count(*)::float FROM unnest(ma.activities) t WHERE lower(t) IN (SELECT act FROM activity_prefs)) * 3.0 +
				(SELECT count(*)::float FROM unnest(ma.entities) t WHERE lower(t) IN (SELECT ent FROM entity_prefs)) * 2.5 +
				(SELECT count(*)::float FROM unnest(ma.scene_labels) t WHERE lower(t) IN (SELECT scene FROM scene_prefs)) * 1.5 +
				CASE WHEN ma.is_iraq_related THEN 1.0 ELSE 0 END
			) AS score
		FROM media_analysis ma
		JOIN notes n ON n.id = ma.note_id
		WHERE ma.status IN ('done', 'done_degraded')
		  AND n.kind = $2
		  AND n.raw_json IS NOT NULL AND n.raw_json::text <> '{}'
		  AND ma.note_id NOT IN (SELECT note_id FROM engaged)
		  AND (
				EXISTS (SELECT 1 FROM topic_prefs) OR EXISTS (SELECT 1 FROM tag_prefs)
				OR EXISTS (SELECT 1 FROM object_prefs) OR EXISTS (SELECT 1 FROM activity_prefs)
				OR EXISTS (SELECT 1 FROM entity_prefs) OR EXISTS (SELECT 1 FROM scene_prefs)
		  )
		  AND (
				EXISTS (SELECT 1 FROM unnest(ma.topics) t WHERE lower(t) IN (SELECT topic FROM topic_prefs))
				OR EXISTS (SELECT 1 FROM unnest(ma.tags) t WHERE lower(t) IN (SELECT tag FROM tag_prefs))
				OR EXISTS (SELECT 1 FROM unnest(ma.objects) t WHERE lower(t) IN (SELECT obj FROM object_prefs))
				OR EXISTS (SELECT 1 FROM unnest(ma.activities) t WHERE lower(t) IN (SELECT act FROM activity_prefs))
				OR EXISTS (SELECT 1 FROM unnest(ma.entities) t WHERE lower(t) IN (SELECT ent FROM entity_prefs))
				OR EXISTS (SELECT 1 FROM unnest(ma.scene_labels) t WHERE lower(t) IN (SELECT scene FROM scene_prefs))
		  )
		ORDER BY score DESC, ma.updated_at DESC
		LIMIT $3;
	`, userID, kind, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []FeedNote
	for rows.Next() {
		var id, raw string
		var score float64
		if err := rows.Scan(&id, &raw, &score); err != nil {
			return nil, err
		}
		if score <= 0 {
			continue
		}
		var ev nostr.Event
		if err := json.Unmarshal([]byte(raw), &ev); err != nil {
			continue
		}
		if ev.ID == "" {
			continue
		}
		out = append(out, FeedNote{Event: ev, Score: score + similarScoreBoost})
	}
	return out, nil
}

func (r *NostrRepository) userHasEngagementSince(ctx context.Context, userID string, since time.Time) (bool, error) {
	var exists bool
	err := r.db.QueryRowContext(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM reactions WHERE reactor_id = $1 AND created_at > $2
			UNION ALL
			SELECT 1 FROM zaps WHERE zapper_id = $1 AND created_at > $2
			UNION ALL
			SELECT 1 FROM comments WHERE commenter_id = $1 AND created_at > $2
			LIMIT 1
		)
	`, userID, since).Scan(&exists)
	return exists, err
}

// safeURLHost for logs only
func safeURLHost(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "?"
	}
	return u.Host
}
