package main

import "testing"

func TestIsMERelayURL(t *testing.T) {
	t.Setenv("ME_RELAY_URL", "wss://desktop-r082364.tail4b3c94.ts.net/")
	if !isMERelayURL("wss://desktop-r082364.tail4b3c94.ts.net/") {
		t.Fatal("expected ME relay match with trailing slash")
	}
	if !isMERelayURL("wss://desktop-r082364.tail4b3c94.ts.net") {
		t.Fatal("expected ME relay match without trailing slash")
	}
	if isMERelayURL("wss://relay.primal.net") {
		t.Fatal("public relay must not match ME")
	}
}

func TestIsMiddleEastRelevant(t *testing.T) {
	iraq := &MediaAnalysisResult{IsIraqRelated: true, Region: "global"}
	if !isMiddleEastRelevant(iraq) {
		t.Fatal("iraq flag should pass")
	}
	me := &MediaAnalysisResult{Region: "middle-east", Topics: []string{"news"}}
	if !isMiddleEastRelevant(me) {
		t.Fatal("middle-east region should pass")
	}
	global := &MediaAnalysisResult{Region: "global", Topics: []string{"guns", "meme"}, Tags: []string{"usa"}}
	if isMiddleEastRelevant(global) {
		t.Fatal("global meme must be rejected by ME filter")
	}
}
