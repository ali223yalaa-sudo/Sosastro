package main

import (
	"bytes"
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func findFFmpeg() string {
	if p := strings.TrimSpace(os.Getenv("FFMPEG_PATH")); p != "" {
		return p
	}
	if p, err := exec.LookPath("ffmpeg"); err == nil {
		return p
	}
	// Common Windows winget / chocolatey locations
	candidates := []string{
		`C:\ffmpeg\bin\ffmpeg.exe`,
		`C:\ProgramData\chocolatey\bin\ffmpeg.exe`,
	}
	home, _ := os.UserHomeDir()
	if home != "" {
		candidates = append(candidates,
			filepath.Join(home, `AppData\Local\Microsoft\WinGet\Links\ffmpeg.exe`),
			filepath.Join(home, `scoop\shims\ffmpeg.exe`),
		)
	}
	for _, c := range candidates {
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			return c
		}
	}
	return ""
}

// extractVideoFrameJPEG pulls one JPEG frame near 0.5s using ffmpeg (when available).
func extractVideoFrameJPEG(ctx context.Context, mediaURL string) ([]byte, error) {
	ff := findFFmpeg()
	if ff == "" {
		return nil, fmt.Errorf("ffmpeg not found")
	}
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, ff,
		"-hide_banner", "-loglevel", "error",
		"-y",
		"-ss", "0.5",
		"-i", mediaURL,
		"-frames:v", "1",
		"-q:v", "5",
		"-f", "image2pipe",
		"-vcodec", "mjpeg",
		"pipe:1",
	)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return nil, fmt.Errorf("ffmpeg: %s", truncateRunes(msg, 200))
	}
	out := stdout.Bytes()
	if len(out) < 200 {
		return nil, fmt.Errorf("ffmpeg produced empty frame")
	}
	// Cap frame size for Gemini inline budget
	const maxFrame = 512 * 1024
	if len(out) > maxFrame {
		log.Printf("gemini: frame truncated %d→%d", len(out), maxFrame)
		out = out[:maxFrame]
	}
	return out, nil
}
