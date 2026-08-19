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

func openaiAPIKey() string {
	return strings.TrimSpace(os.Getenv("OPENAI_API_KEY"))
}

func openaiModel() string {
	m := strings.TrimSpace(os.Getenv("OPENAI_MODEL"))
	if m == "" {
		return "gpt-5.4-mini"
	}
	return m
}

func openaiEnabled() bool {
	return openaiAPIKey() != "" && os.Getenv("OPENAI_ANALYSIS_ENABLED") != "0"
}

var (
	openaiBudgetDay   string
	openaiBudgetCount int
	openaiBudgetMu    sync.Mutex
	openaiDailyLimit  = 30
)

func openaiBudgetAllow() bool {
	openaiBudgetMu.Lock()
	defer openaiBudgetMu.Unlock()
	day := time.Now().UTC().Format("2006-01-02")
	if openaiBudgetDay != day {
		openaiBudgetDay = day
		openaiBudgetCount = 0
	}
	if v := strings.TrimSpace(os.Getenv("OPENAI_DAILY_LIMIT")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			openaiDailyLimit = n
		}
	}
	return openaiBudgetCount < openaiDailyLimit
}

func openaiBudgetConsume() {
	openaiBudgetMu.Lock()
	defer openaiBudgetMu.Unlock()
	day := time.Now().UTC().Format("2006-01-02")
	if openaiBudgetDay != day {
		openaiBudgetDay = day
		openaiBudgetCount = 0
	}
	openaiBudgetCount++
}

type openaiResponsesRequest struct {
	Model string        `json:"model"`
	Input []openaiInput `json:"input"`
	Store bool          `json:"store"`
}

type openaiInput struct {
	Role    string               `json:"role"`
	Content []openaiInputContent `json:"content"`
}

type openaiInputContent struct {
	Type     string `json:"type"`
	Text     string `json:"text,omitempty"`
	ImageURL string `json:"image_url,omitempty"`
}

type openaiResponsesResponse struct {
	Status string `json:"status"`
	Error  *struct {
		Message string `json:"message"`
		Code    string `json:"code"`
	} `json:"error"`
	Output []struct {
		Type    string `json:"type"`
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	} `json:"output"`
	OutputText string `json:"output_text"`
}

// analyzeMediaWithOpenAI sends ONE jpeg (or text-only) to OpenAI Responses API.
func analyzeMediaWithOpenAI(ctx context.Context, mediaURL, mimeType, caption string, imageJPEG []byte) (*MediaAnalysisResult, string, error) {
	key := openaiAPIKey()
	if key == "" {
		return nil, "", fmt.Errorf("openai key missing")
	}
	if !openaiBudgetAllow() {
		return nil, "", fmt.Errorf("openai daily budget exhausted")
	}

	prompt := geminiEconomicVisualPrompt
	if caption != "" {
		prompt += "\n\nCaption:\n" + truncateRunes(caption, 400)
	}
	prompt += "\nReturn ONLY compact JSON, no markdown."

	content := []openaiInputContent{{Type: "input_text", Text: prompt}}
	if len(imageJPEG) > 200 {
		dataURL := "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(imageJPEG)
		content = append(content, openaiInputContent{Type: "input_image", ImageURL: dataURL})
		log.Printf("openai visual: frame %d bytes host=%s model=%s", len(imageJPEG), safeURLHost(mediaURL), openaiModel())
	} else {
		body := strings.TrimSpace(caption + "\n" + mediaURL)
		if body == "" {
			body = "(empty)"
		}
		content = []openaiInputContent{{Type: "input_text", Text: geminiTextAnalysisPrompt + "\n\nPost:\n" + truncateRunes(body, 1500)}}
		log.Printf("openai text-only model=%s", openaiModel())
	}

	reqBody := openaiResponsesRequest{
		Model: openaiModel(),
		Store: false, // never store — saves cost/privacy
		Input: []openaiInput{{Role: "user", Content: content}},
	}
	rawReq, _ := json.Marshal(reqBody)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.openai.com/v1/responses", bytes.NewReader(rawReq))
	if err != nil {
		return nil, "", err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+key)

	openaiBudgetConsume()
	client := &http.Client{Timeout: 90 * time.Second}
	resp, err := client.Do(httpReq)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if resp.StatusCode >= 400 {
		return nil, "", fmt.Errorf("openai HTTP %d: %s", resp.StatusCode, truncateRunes(string(body), 300))
	}

	var parsed openaiResponsesResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil, "", fmt.Errorf("openai decode: %w", err)
	}
	if parsed.Error != nil {
		return nil, "", fmt.Errorf("openai: %s", parsed.Error.Message)
	}
	text := strings.TrimSpace(parsed.OutputText)
	if text == "" {
		for _, out := range parsed.Output {
			for _, c := range out.Content {
				if c.Text != "" {
					text = c.Text
					break
				}
			}
			if text != "" {
				break
			}
		}
	}
	text = stripCodeFence(text)
	if text == "" {
		return nil, "", fmt.Errorf("openai empty response")
	}

	var result MediaAnalysisResult
	if err := json.Unmarshal([]byte(text), &result); err != nil {
		// Sometimes model wraps JSON — try extract object
		if i, j := strings.Index(text, "{"), strings.LastIndex(text, "}"); i >= 0 && j > i {
			_ = json.Unmarshal([]byte(text[i:j+1]), &result)
		}
		if result.Title == "" && len(result.Topics) == 0 {
			return nil, "", fmt.Errorf("openai json parse: %v", err)
		}
	}
	normalizeAnalysis(&result)
	rawOut, _ := json.Marshal(&result)
	return &result, string(rawOut), nil
}
