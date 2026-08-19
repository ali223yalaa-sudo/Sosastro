package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

func reanalyzeNoteByID(noteID string) error {
	var rawJSON, mediaURL, mediaType string
	err := repository.db.QueryRow(`
		SELECT n.raw_json,
			coalesce(ma.media_url, ''),
			coalesce(ma.media_type, '')
		FROM notes n
		LEFT JOIN media_analysis ma ON ma.note_id = n.id
		WHERE n.id = $1
	`, noteID).Scan(&rawJSON, &mediaURL, &mediaType)
	if err != nil {
		return fmt.Errorf("note lookup: %w", err)
	}
	var ev nostr.Event
	if rawJSON != "" && rawJSON != "{}" {
		if err := json.Unmarshal([]byte(rawJSON), &ev); err != nil {
			return fmt.Errorf("raw_json: %w", err)
		}
	}
	if mediaURL == "" && ev.ID != "" {
		u, _, mt := extractMediaFromEvent(&ev)
		mediaURL, mediaType = u, mt
	}
	if mediaURL == "" {
		mediaURL = "text://note/" + noteID
		mediaType = "text"
	}
	if mediaType == "" {
		mediaType = "video"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()

	usedFallback := false
	var result *MediaAnalysisResult
	var raw string
	var aerr error
	if mediaType == "text" || strings.HasPrefix(mediaURL, "text://") {
		result, raw, aerr = analyzeTextWithGemini(ctx, ev.Content)
	} else {
		result, raw, aerr = analyzeMediaWithGemini(ctx, mediaURL, guessMime(mediaURL, mediaType), ev.Content)
	}
	if aerr != nil {
		log.Printf("reanalyze gemini err: %v — fallback", aerr)
		result, raw = captionFallbackAnalysis(ev.Content + " " + mediaURL)
		usedFallback = true
	}
	status := analysisStatusForResult(result, usedFallback)
	errMsg := ""
	if status == "failed" {
		errMsg = "no meaningful topics from analysis"
	}
	if err := repository.saveMediaAnalysisWithStatus(noteID, mediaURL, mediaType, geminiModel(), result, raw, status, errMsg); err != nil {
		return err
	}
	fmt.Printf("reanalyzed note=%s status=%s topics=%v objects=%v\n", noteID[:8], status, result.Topics, result.Objects)
	return nil
}

func guessMime(url, mediaType string) string {
	low := strings.ToLower(url)
	switch {
	case strings.Contains(low, ".mp4"), mediaType == "video":
		return "video/mp4"
	case strings.Contains(low, ".jpg"), strings.Contains(low, ".jpeg"):
		return "image/jpeg"
	case strings.Contains(low, ".png"):
		return "image/png"
	case strings.Contains(low, ".webp"):
		return "image/webp"
	default:
		if mediaType == "image" {
			return "image/jpeg"
		}
		return "video/mp4"
	}
}
