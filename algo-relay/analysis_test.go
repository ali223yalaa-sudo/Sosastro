package main

import (
	"strings"
	"testing"
)

func TestIsGenericAnalysis(t *testing.T) {
	if !isGenericAnalysis(nil) {
		t.Fatal("nil should be generic")
	}
	if !isGenericAnalysis(&MediaAnalysisResult{Topics: []string{"general"}, Tags: []string{"unclassified"}}) {
		t.Fatal("general/unclassified should be generic")
	}
	if isGenericAnalysis(&MediaAnalysisResult{Topics: []string{"beauty", "fashion"}, Objects: []string{"woman"}}) {
		t.Fatal("beauty/fashion should NOT be generic")
	}
}

func TestAnalysisStatusForResult(t *testing.T) {
	good := &MediaAnalysisResult{Topics: []string{"فتاة", "جمال"}}
	if got := analysisStatusForResult(good, false); got != "done" {
		t.Fatalf("want done got %s", got)
	}
	if got := analysisStatusForResult(good, true); got != "done_degraded" {
		t.Fatalf("want done_degraded got %s", got)
	}
	bad := &MediaAnalysisResult{Topics: []string{"general"}}
	if got := analysisStatusForResult(bad, true); got != "failed" {
		t.Fatalf("want failed got %s", got)
	}
}

func TestCaptionFallbackGirlKeywords(t *testing.T) {
	res, _ := captionFallbackAnalysis("فتاة جميلة selfie beauty fashion")
	if isGenericAnalysis(res) {
		t.Fatalf("expected meaningful topics, got %#v", res.Topics)
	}
	joined := strings.Join(res.Topics, " ")
	if !strings.Contains(joined, "فتاة") && !strings.Contains(joined, "beauty") {
		t.Fatalf("expected girl/beauty keyword in %v", res.Topics)
	}
}

func TestCaptionFallbackEmptyIsUnclassified(t *testing.T) {
	res, _ := captionFallbackAnalysis("https://example.com/abcdef.mp4")
	if len(res.Topics) == 0 {
		t.Fatal("expected unclassified topic")
	}
	if res.Topics[0] != "unclassified" && res.Topics[0] != "general" {
		// after fix default is unclassified
		if !isGenericAnalysis(res) {
			t.Fatalf("URL-only should be generic, got %v", res.Topics)
		}
	}
}

func TestMediaURLStubIDStable(t *testing.T) {
	a := mediaURLStubID("https://example.com/a.mp4")
	b := mediaURLStubID("https://example.com/a.mp4")
	c := mediaURLStubID("https://example.com/b.mp4")
	if len(a) != 64 {
		t.Fatalf("stub id len=%d", len(a))
	}
	if a != b {
		t.Fatal("stub id not stable")
	}
	if a == c {
		t.Fatal("different urls must differ")
	}
}

func TestIsVideoURL(t *testing.T) {
	if !isVideoURL(strings.ToLower("https://x.blossom.band/abc.mp4")) {
		t.Fatal("mp4 should be video")
	}
	if isVideoURL(strings.ToLower("https://x.blossom.band/abc.jpg")) {
		t.Fatal("jpg should not be video")
	}
}
