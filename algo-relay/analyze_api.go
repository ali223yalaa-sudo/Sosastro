package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/lib/pq"
)

type analyzeMediaRequest struct {
	URL     string `json:"url"`
	Mime    string `json:"mime"`
	Caption string `json:"caption"`
}

type analyzeMediaResponse struct {
	Status  string   `json:"status"` // pending | done | done_degraded | failed
	Topics  []string `json:"topics,omitempty"`
	Tags    []string `json:"tags,omitempty"`
	Objects []string `json:"objects,omitempty"`
	Error   string   `json:"error,omitempty"`
	NoteID  string   `json:"note_id,omitempty"`
}

func mediaURLStubID(mediaURL string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(mediaURL)))
	return hex.EncodeToString(sum[:])
}

func isGenericAnalysis(a *MediaAnalysisResult) bool {
	if a == nil {
		return true
	}
	generic := map[string]bool{
		"general": true, "unknown": true, "unknown content": true,
		"unclassified": true, "video": true, "فيديو": true, "post": true, "text": true,
	}
	meaningful := 0
	check := func(items []string) {
		for _, t := range items {
			t = strings.ToLower(strings.TrimSpace(t))
			if t != "" && !generic[t] {
				meaningful++
			}
		}
	}
	check(a.Topics)
	check(a.Tags)
	check(a.Objects)
	check(a.Activities)
	check(a.Entities)
	return meaningful == 0
}

func analysisStatusForResult(a *MediaAnalysisResult, usedFallback bool) string {
	if isGenericAnalysis(a) {
		return "failed"
	}
	if usedFallback {
		return "done_degraded"
	}
	return "done"
}

// handleAnalyzeMedia:
//
//	POST /api/analyze-media  {"url","mime","caption"} → runs Gemini (or fallback) and returns status
//	GET  /api/analyze-media?url=… → latest status for that media URL
func handleAnalyzeMedia(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	if r.Method == http.MethodOptions {
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.WriteHeader(http.StatusNoContent)
		return
	}

	switch r.Method {
	case http.MethodGet:
		mediaURL := strings.TrimSpace(r.URL.Query().Get("url"))
		if mediaURL == "" {
			http.Error(w, `{"error":"missing url"}`, http.StatusBadRequest)
			return
		}
		resp := lookupAnalysisByURL(mediaURL)
		_ = json.NewEncoder(w).Encode(resp)
	case http.MethodPost:
		var req analyzeMediaRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
			return
		}
		req.URL = strings.TrimSpace(req.URL)
		if req.URL == "" {
			http.Error(w, `{"error":"missing url"}`, http.StatusBadRequest)
			return
		}
		resp := runBasketMediaAnalysis(req.URL, req.Mime, req.Caption)
		_ = json.NewEncoder(w).Encode(resp)
	default:
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
	}
}

func lookupAnalysisByURL(mediaURL string) analyzeMediaResponse {
	var status, errMsg, noteID string
	var topics, tags, objects pq.StringArray
	err := repository.db.QueryRow(`
		SELECT note_id, status, coalesce(error,''), topics, tags, objects
		FROM media_analysis
		WHERE media_url = $1
		ORDER BY updated_at DESC
		LIMIT 1
	`, mediaURL).Scan(&noteID, &status, &errMsg, &topics, &tags, &objects)
	if err != nil {
		return analyzeMediaResponse{Status: "pending"}
	}
	return analyzeMediaResponse{
		Status:  status,
		Topics:  []string(topics),
		Tags:    []string(tags),
		Objects: []string(objects),
		Error:   errMsg,
		NoteID:  noteID,
	}
}

func runBasketMediaAnalysis(mediaURL, mime, caption string) analyzeMediaResponse {
	stubID := mediaURLStubID(mediaURL)
	if err := repository.ensureNoteStub(stubID); err != nil {
		log.Printf("analyze-media stub: %v", err)
		return analyzeMediaResponse{Status: "failed", Error: "stub failed"}
	}

	mediaType := "image"
	if strings.HasPrefix(strings.ToLower(mime), "video/") || isVideoURL(strings.ToLower(mediaURL)) {
		mediaType = "video"
	} else if strings.HasPrefix(strings.ToLower(mime), "audio/") {
		mediaType = "audio"
	}

	_ = repository.upsertMediaAnalysisPending(stubID, mediaURL, mediaType)

	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	usedFallback := false
	var result *MediaAnalysisResult
	var raw string
	var err error

	if !geminiEnabled() {
		result, raw = captionFallbackAnalysis(caption + " " + mediaURL)
		usedFallback = true
	} else {
		result, raw, err = analyzeMediaWithGemini(ctx, mediaURL, mime, caption)
		if err != nil {
			log.Printf("analyze-media gemini fail: %v — fallback", err)
			result, raw = captionFallbackAnalysis(caption + " " + mediaURL)
			usedFallback = true
		}
	}

	status := analysisStatusForResult(result, usedFallback)
	errMsg := ""
	if status == "failed" {
		errMsg = "no meaningful topics from analysis"
	}

	if err := repository.saveMediaAnalysisWithStatus(stubID, mediaURL, mediaType, geminiModel(), result, raw, status, errMsg); err != nil {
		log.Printf("analyze-media save: %v", err)
		return analyzeMediaResponse{Status: "failed", Error: err.Error(), NoteID: stubID}
	}

	topics, tags, objects := []string{}, []string{}, []string{}
	if result != nil {
		topics, tags, objects = result.Topics, result.Tags, result.Objects
	}
	return analyzeMediaResponse{
		Status:  status,
		Topics:  topics,
		Tags:    tags,
		Objects: objects,
		Error:   errMsg,
		NoteID:  stubID,
	}
}
