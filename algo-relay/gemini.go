package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const geminiDefaultModel = "gemini-2.0-flash"

type geminiRequest struct {
	Contents         []geminiContent        `json:"contents"`
	GenerationConfig *geminiGenerationConfig `json:"generationConfig,omitempty"`
}

type geminiContent struct {
	Parts []geminiPart `json:"parts"`
}

type geminiPart struct {
	Text       string            `json:"text,omitempty"`
	InlineData *geminiInlineData `json:"inline_data,omitempty"`
}

type geminiInlineData struct {
	MimeType string `json:"mime_type"`
	Data     string `json:"data"`
}

type geminiGenerationConfig struct {
	Temperature      float64 `json:"temperature"`
	ResponseMimeType string  `json:"responseMimeType"`
}

type geminiResponse struct {
	Candidates []struct {
		Content struct {
			Parts []struct {
				Text string `json:"text"`
			} `json:"parts"`
		} `json:"content"`
	} `json:"candidates"`
	Error *struct {
		Message string `json:"message"`
		Code    int    `json:"code"`
	} `json:"error"`
}

// MediaAnalysisResult is the structured Gemini output we persist.
type MediaAnalysisResult struct {
	Title               string   `json:"title"`
	Summary             string   `json:"summary"`
	DetailedDescription string   `json:"detailed_description"`
	Language            string   `json:"language"`
	Topics              []string `json:"topics"`
	Tags                []string `json:"tags"`
	Entities            []string `json:"entities"`
	SceneLabels         []string `json:"scene_labels"`
	Objects             []string `json:"objects"`
	Activities          []string `json:"activities"`
	Colors              []string `json:"colors"`
	Mood                string   `json:"mood"`
	Style               string   `json:"style"`
	Region              string   `json:"region"`
	IsNews              bool     `json:"is_news"`
	IsIraqRelated       bool     `json:"is_iraq_related"`
	NSFWScore           float64  `json:"nsfw_score"`
}

func geminiAPIKey() string {
	return strings.TrimSpace(os.Getenv("GEMINI_API_KEY"))
}

func geminiModel() string {
	m := strings.TrimSpace(os.Getenv("GEMINI_MODEL"))
	if m == "" {
		return geminiDefaultModel
	}
	return m
}

func geminiEnabled() bool {
	return geminiAPIKey() != "" && os.Getenv("GEMINI_ANALYSIS_ENABLED") != "0"
}

const geminiJSONSchema = `{
  "title": "short Arabic or English title",
  "summary": "2-3 sentence summary of the content",
  "detailed_description": "VERY detailed visual/textual description for similarity matching",
  "language": "ar|en|ku|mixed|unknown",
  "topics": ["topic1","topic2","topic3"],
  "tags": ["tag1","tag2","tag3","tag4","tag5","tag6","tag7","tag8"],
  "entities": ["people names","organizations","places","brands"],
  "scene_labels": ["indoor/outdoor","city","studio","street","home","stadium",...],
  "objects": ["every notable visible object"],
  "activities": ["what people/subjects are doing"],
  "colors": ["dominant colors"],
  "mood": "one word mood",
  "style": "news|vlog|interview|sports|music|comedy|ads|documentary|food|fashion|religion|politics|other",
  "region": "iraq|baghdad|basra|erbil|middle-east|global|unknown",
  "is_news": true/false,
  "is_iraq_related": true/false,
  "nsfw_score": 0.0
}`

const geminiVisualAnalysisPrompt = `You are a professional VISUAL content analyst for an Iraqi/Middle-East social app (Astro).
Your job: deep visual analysis so we can recommend SIMILAR posts to users (NOT hashtag matching — content understanding).

Analyze the attached image/video thoroughly:
- People: age range, gender presentation, clothing, expressions, notable faces/roles
- Setting: indoor/outdoor, city cues, landmarks, time of day, weather
- Text on screen / overlays / logos / watermarks (transcribe if readable)
- Actions and narrative of what is happening
- Food, products, sports, religion, politics, culture cues when present
- Audio/speech topic if inferable from caption or obvious video context
- Be concrete and specific — avoid vague words like "nice" or "content"

Return ONLY valid JSON (no markdown) with this exact schema:
` + geminiJSONSchema + `

Rules:
- Prefer Arabic topics/tags when content is Arabic.
- topics/tags must describe WHAT is shown (visual categories), not publisher hashtags.
- Mark is_iraq_related if Iraq, Baghdad, Iraqi dialect, Iraqi people, politics, culture, or sports appear.
- detailed_description must be long enough for another model to judge similarity.`

const geminiTextAnalysisPrompt = `You are a professional content analyst for an Iraqi/Middle-East social app (Astro).
Analyze this TEXT POST for recommendation matching (content understanding, NOT hashtag lists from the publisher).

Return ONLY valid JSON (no markdown) with this exact schema:
` + geminiJSONSchema + `

For text-only posts: fill scene_labels/objects/colors from implied context when possible; leave empty arrays if unknown.
Prefer Arabic topics/tags when text is Arabic. Mark is_iraq_related appropriately.`

const geminiEconomicVisualPrompt = `Analyze this image for Middle-East social recommendations.
Return ONLY compact JSON:
{"title":"","summary":"","detailed_description":"","language":"ar|en","topics":["≤5"],"tags":["≤5"],"entities":["≤5"],"scene_labels":[],"objects":[],"activities":[],"colors":[],"mood":"","style":"","region":"middle-east|iraq|global|unknown","is_news":false,"is_iraq_related":false,"nsfw_score":0.0}
Be specific. Prefer Arabic topics when content is Arabic.`

func analyzeMediaWithGemini(ctx context.Context, mediaURL, mimeType, caption string) (*MediaAnalysisResult, string, error) {
	isVideo := strings.HasPrefix(mimeType, "video/") || isVideoURL(strings.ToLower(mediaURL))

	// Prefer OpenAI when configured (Gemini free-tier often exhausted).
	if openaiEnabled() && openaiBudgetAllow() {
		var frame []byte
		if isVideo {
			if f, err := extractVideoFrameJPEG(ctx, mediaURL); err == nil {
				frame = f
			} else {
				log.Printf("openai: frame extract failed (%v)", err)
			}
		} else {
			data, detected, err := downloadMediaBytes(ctx, mediaURL, 400*1024)
			if err == nil && (strings.HasPrefix(mimeType, "image/") || strings.HasPrefix(detected, "image/") || looksLikeImage(data)) {
				frame = data
				if !bytes.HasPrefix(frame, []byte{0xff, 0xd8}) && !strings.Contains(detected, "jpeg") {
					// Non-JPEG image — still send; OpenAI accepts png/webp via data URL in caller as jpeg mime may fail.
					// Re-download handled as raw bytes with proper mime in openai path via jpeg-only for now.
				}
			}
		}
		if len(frame) > 200 || (!isVideo && caption != "") {
			res, raw, err := analyzeMediaWithOpenAI(ctx, mediaURL, mimeType, caption, frame)
			if err == nil {
				return res, raw, nil
			}
			log.Printf("openai analyze fail: %v — try gemini", err)
		}
	}

	key := geminiAPIKey()
	if key == "" {
		res, raw := captionFallbackAnalysis(caption + " " + mediaURL)
		return res, raw, nil
	}

	if !geminiBudgetAllow() {
		log.Printf("gemini daily budget exhausted — caption fallback")
		res, raw := captionFallbackAnalysis(caption + " " + mediaURL)
		return res, raw, nil
	}

	if isVideo {
		return analyzeVideoVisually(ctx, key, mediaURL, mimeType, caption)
	}

	data, detectedMime, err := downloadMediaBytes(ctx, mediaURL, 512*1024)
	if err != nil {
		log.Printf("gemini: download fail (%v) — caption/URL", err)
		return analyzeTextWithGemini(ctx, caption+"\nMedia URL: "+mediaURL)
	}
	if mimeType == "" {
		mimeType = detectedMime
	}
	if mimeType == "" {
		mimeType = "image/jpeg"
	}
	if !strings.HasPrefix(mimeType, "image/") {
		return analyzeTextWithGemini(ctx, caption+"\nMedia URL: "+mediaURL)
	}

	prompt := geminiEconomicVisualPrompt
	if caption != "" {
		prompt += "\n\nCaption:\n" + truncateRunes(caption, 600)
	}
	parts := []geminiPart{
		{InlineData: &geminiInlineData{MimeType: mimeType, Data: base64.StdEncoding.EncodeToString(data)}},
		{Text: prompt},
	}
	geminiBudgetConsume()
	return callGeminiJSONWithRetry(ctx, key, parts)
}

func looksLikeImage(b []byte) bool {
	if len(b) < 4 {
		return false
	}
	// JPEG / PNG / GIF / WEBP
	if b[0] == 0xff && b[1] == 0xd8 {
		return true
	}
	if b[0] == 0x89 && b[1] == 0x50 {
		return true
	}
	if string(b[:4]) == "GIF8" {
		return true
	}
	if len(b) > 12 && string(b[8:12]) == "WEBP" {
		return true
	}
	return false
}

// analyzeVideoVisually extracts a JPEG frame (ffmpeg) and sends THAT ONE image to Gemini.
// Full/partial MP4 upload is disabled by default (GEMINI_VIDEO_BYTES=1 to enable) to save quota.
func analyzeVideoVisually(ctx context.Context, key, mediaURL, mimeType, caption string) (*MediaAnalysisResult, string, error) {
	prompt := geminiEconomicVisualPrompt
	if caption != "" {
		prompt += "\n\nCaption:\n" + truncateRunes(caption, 400)
	}

	if frame, err := extractVideoFrameJPEG(ctx, mediaURL); err == nil && len(frame) > 200 {
		log.Printf("gemini visual: video frame %d bytes host=%s", len(frame), safeURLHost(mediaURL))
		parts := []geminiPart{
			{InlineData: &geminiInlineData{MimeType: "image/jpeg", Data: base64.StdEncoding.EncodeToString(frame)}},
			{Text: prompt},
		}
		geminiBudgetConsume()
		return callGeminiJSONWithRetry(ctx, key, parts)
	} else if err != nil {
		log.Printf("gemini visual: frame extract failed (%v)", err)
	}

	// Expensive path — off unless explicitly enabled.
	if os.Getenv("GEMINI_VIDEO_BYTES") == "1" {
		maxVideo := 2 * 1024 * 1024
		data, detected, err := downloadMediaBytes(ctx, mediaURL, maxVideo)
		if err == nil && len(data) >= 1000 {
			if mimeType == "" {
				mimeType = detected
			}
			if mimeType == "" || !strings.HasPrefix(mimeType, "video/") {
				mimeType = "video/mp4"
			}
			log.Printf("gemini visual: short video %d bytes mime=%s host=%s", len(data), mimeType, safeURLHost(mediaURL))
			parts := []geminiPart{
				{InlineData: &geminiInlineData{MimeType: mimeType, Data: base64.StdEncoding.EncodeToString(data)}},
				{Text: prompt},
			}
			geminiBudgetConsume()
			return callGeminiJSONWithRetry(ctx, key, parts)
		}
	}

	log.Printf("gemini visual: no frame — caption fallback (0 API) host=%s", safeURLHost(mediaURL))
	res, raw := captionFallbackAnalysis(caption + " " + mediaURL)
	return res, raw, nil
}

// analyzeTextWithGemini analyzes text-only notes published to the ME relay.
func analyzeTextWithGemini(ctx context.Context, content string) (*MediaAnalysisResult, string, error) {
	if openaiEnabled() && openaiBudgetAllow() {
		res, raw, err := analyzeMediaWithOpenAI(ctx, "", "text/plain", content, nil)
		if err == nil {
			return res, raw, nil
		}
		log.Printf("openai text fail: %v — try gemini", err)
	}
	key := geminiAPIKey()
	if key == "" {
		res, raw := captionFallbackAnalysis(content)
		return res, raw, nil
	}
	if !geminiBudgetAllow() {
		res, raw := captionFallbackAnalysis(content)
		return res, raw, nil
	}
	body := strings.TrimSpace(content)
	if body == "" {
		body = "(empty post)"
	}
	prompt := geminiTextAnalysisPrompt + "\n\nPost text:\n" + truncateRunes(body, 2000)
	geminiBudgetConsume()
	return callGeminiJSONWithRetry(ctx, key, []geminiPart{{Text: prompt}})
}

func callGeminiJSONWithRetry(ctx context.Context, key string, parts []geminiPart) (*MediaAnalysisResult, string, error) {
	// Only retry transient network/5xx — never burn quota on 429/RESOURCE_EXHAUSTED.
	delays := []time.Duration{0, 2 * time.Second, 5 * time.Second}
	var lastErr error
	for i, d := range delays {
		if d > 0 {
			select {
			case <-ctx.Done():
				return nil, "", ctx.Err()
			case <-time.After(d):
			}
		}
		res, raw, err := callGeminiJSON(ctx, key, parts)
		if err == nil {
			return res, raw, nil
		}
		lastErr = err
		msg := strings.ToLower(err.Error())
		if strings.Contains(msg, "quota") ||
			strings.Contains(msg, "resource_exhausted") ||
			strings.Contains(msg, "rate limit") ||
			strings.Contains(msg, "429") {
			log.Printf("gemini: quota/rate-limit — stop retries to save quota")
			return nil, "", err
		}
		if i < len(delays)-1 {
			log.Printf("gemini retry %d/%d: %v", i+1, len(delays), err)
		}
	}
	return nil, "", lastErr
}

// captionFallbackAnalysis always produces a usable analysis row when Gemini is unavailable.
func captionFallbackAnalysis(caption string) (*MediaAnalysisResult, string) {
	text := strings.TrimSpace(caption)
	if text == "" {
		text = "unknown content"
	}
	topics := []string{}
	lower := strings.ToLower(text)
	for _, kw := range []string{
		"طبيعة", "طبيعه", "شجر", "اشجار", "أشجار", "كرة", "قدم", "مسلسل", "وادي", "علمدار", "مراد",
		"فتاة", "فتيات", "بنات", "بنت", "جمال", "مكياج", "موضة", "ازياء", "أزياء", "شعر", "selfie",
		"girl", "girls", "woman", "women", "beauty", "fashion", "model", "makeup",
		"nature", "tree", "football", "food", "طعام", "سيارة", "سيارات", "car", "cars",
		"رقص", "dance", "gym", "رياضة", "fitness",
	} {
		if strings.Contains(lower, strings.ToLower(kw)) {
			topics = append(topics, kw)
		}
	}
	if len(topics) == 0 {
		// Prefer opaque token over matching the whole corpus as "general".
		topics = []string{"unclassified"}
	}
	res := &MediaAnalysisResult{
		Title:               truncateRunes(text, 80),
		Summary:             truncateRunes(text, 300),
		DetailedDescription: text,
		Language:            "ar",
		Topics:              topics,
		Tags:                topics,
		Region:              "middle-east",
	}
	normalizeAnalysis(res)
	raw, _ := json.Marshal(res)
	return res, string(raw)
}

var (
	geminiBudgetDay   string
	geminiBudgetCount int
	geminiBudgetMu    sync.Mutex
	geminiDailyLimit  = 40 // free-tier friendly; override with GEMINI_DAILY_LIMIT
)

func geminiBudgetAllow() bool {
	geminiBudgetMu.Lock()
	defer geminiBudgetMu.Unlock()
	day := time.Now().UTC().Format("2006-01-02")
	if geminiBudgetDay != day {
		geminiBudgetDay = day
		geminiBudgetCount = 0
	}
	if v := strings.TrimSpace(os.Getenv("GEMINI_DAILY_LIMIT")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			geminiDailyLimit = n
		}
	}
	return geminiBudgetCount < geminiDailyLimit
}

func geminiBudgetConsume() {
	geminiBudgetMu.Lock()
	defer geminiBudgetMu.Unlock()
	day := time.Now().UTC().Format("2006-01-02")
	if geminiBudgetDay != day {
		geminiBudgetDay = day
		geminiBudgetCount = 0
	}
	geminiBudgetCount++
}

func callGeminiJSON(ctx context.Context, key string, parts []geminiPart) (*MediaAnalysisResult, string, error) {
	model := geminiModel()
	endpoint := fmt.Sprintf(
		"https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
		model, key,
	)

	reqBody, _ := json.Marshal(geminiRequest{
		Contents: []geminiContent{{Parts: parts}},
		GenerationConfig: &geminiGenerationConfig{
			Temperature:      0.15,
			ResponseMimeType: "application/json",
		},
	})

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(reqBody))
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 120 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(resp.Body)

	var parsed geminiResponse
	if err := json.Unmarshal(respBody, &parsed); err != nil {
		return nil, "", fmt.Errorf("gemini json decode: %w body=%s", err, truncateRunes(string(respBody), 300))
	}
	if parsed.Error != nil {
		return nil, "", fmt.Errorf("gemini api: %s", parsed.Error.Message)
	}
	if len(parsed.Candidates) == 0 || len(parsed.Candidates[0].Content.Parts) == 0 {
		return nil, "", fmt.Errorf("gemini empty response")
	}

	text := strings.TrimSpace(parsed.Candidates[0].Content.Parts[0].Text)
	text = stripCodeFence(text)

	var result MediaAnalysisResult
	if err := json.Unmarshal([]byte(text), &result); err != nil {
		return nil, text, fmt.Errorf("parse analysis json: %w", err)
	}
	normalizeAnalysis(&result)
	return &result, text, nil
}

func downloadMediaBytes(ctx context.Context, url string, maxBytes int) ([]byte, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("User-Agent", "AstroAlgoRelay/1.0 GeminiAnalyzer")
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return nil, "", fmt.Errorf("download HTTP %d", resp.StatusCode)
	}
	mime := resp.Header.Get("Content-Type")
	if i := strings.Index(mime, ";"); i >= 0 {
		mime = strings.TrimSpace(mime[:i])
	}
	limited := io.LimitReader(resp.Body, int64(maxBytes)+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return nil, "", err
	}
	if len(data) > maxBytes {
		data = data[:maxBytes]
		log.Printf("gemini: truncated download to %d bytes for %s", maxBytes, truncateRunes(url, 80))
	}
	return data, mime, nil
}

func stripCodeFence(s string) string {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "```") {
		s = strings.TrimPrefix(s, "```json")
		s = strings.TrimPrefix(s, "```JSON")
		s = strings.TrimPrefix(s, "```")
		if i := strings.LastIndex(s, "```"); i >= 0 {
			s = s[:i]
		}
	}
	return strings.TrimSpace(s)
}

func normalizeAnalysis(a *MediaAnalysisResult) {
	a.Topics = cleanStringSlice(a.Topics, 20)
	a.Tags = cleanStringSlice(a.Tags, 30)
	a.Entities = cleanStringSlice(a.Entities, 20)
	a.SceneLabels = cleanStringSlice(a.SceneLabels, 20)
	a.Objects = cleanStringSlice(a.Objects, 30)
	a.Activities = cleanStringSlice(a.Activities, 20)
	a.Colors = cleanStringSlice(a.Colors, 10)
	if a.NSFWScore < 0 {
		a.NSFWScore = 0
	}
	if a.NSFWScore > 1 {
		a.NSFWScore = 1
	}
}

func cleanStringSlice(in []string, max int) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(strings.ToLower(s))
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
		if len(out) >= max {
			break
		}
	}
	return out
}

func truncateRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

func buildSearchText(a *MediaAnalysisResult) string {
	parts := []string{
		a.Title, a.Summary, a.DetailedDescription, a.Mood, a.Style, a.Region, a.Language,
	}
	parts = append(parts, a.Topics...)
	parts = append(parts, a.Tags...)
	parts = append(parts, a.Entities...)
	parts = append(parts, a.SceneLabels...)
	parts = append(parts, a.Objects...)
	parts = append(parts, a.Activities...)
	return strings.Join(parts, " ")
}
