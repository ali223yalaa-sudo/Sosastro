package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

var (
	weightInteractionsWithAuthor float64
	weightCommentsGlobal         float64
	weightReactionsGlobal        float64
	weightZapsGlobal             float64
	weightRecency                float64
	viralThreshold               float64
	viralNoteDampening           float64
	decayRate                    float64
)

// SurfacePool is one ranked queue per (user, surface). Never per-kind.
type SurfacePool struct {
	Ranked    []FeedNote
	Offset    int
	Timestamp time.Time
}

var userFeedCache sync.Map // key: userID_surface → SurfacePool

const feedCacheDuration = 2 * time.Minute
const interestBatchSize = 500
const similarPoolLimit = 500
const similarScoreBoost = 80.0
const ownRecentBoost = 140.0
const variantFeedSize = 100

const (
	SurfacePosts  = "posts"
	SurfaceForYou = "foryou"
)

var postsKinds = []int{1, 6, 16, 30023}
var forYouKinds = []int{20, 21, 22, 1063, 34235, 34236, 30923, 1}

var pendingRequests = make(map[string]chan struct{})
var pendingRequestsMutex sync.Mutex

func getCacheKey(userID, surface string) string {
	return userID + "_" + surface
}

func surfaceKinds(surface string) []int {
	if surface == SurfaceForYou {
		return forYouKinds
	}
	return postsKinds
}

func invalidateUserFeedCache(userID string) {
	if userID == "" || userID == "anonymous" {
		return
	}
	for _, s := range []string{SurfacePosts, SurfaceForYou} {
		userFeedCache.Delete(getCacheKey(userID, s))
	}
	prefix := userID + "_"
	userFeedCache.Range(func(key, _ interface{}) bool {
		if k, ok := key.(string); ok && strings.HasPrefix(k, prefix) {
			userFeedCache.Delete(key)
		}
		return true
	})
	log.Printf("astro-recommend: invalidated cache for user %s…", truncateRunes(userID, 8))
}

func astroReqMode(filter nostr.Filter) string {
	if filter.Tags == nil {
		return ""
	}
	for _, v := range filter.Tags["astro"] {
		if v == "refresh" || v == "more" {
			return v
		}
	}
	return ""
}

func astroSurface(filter nostr.Filter) string {
	if filter.Tags == nil {
		return SurfacePosts
	}
	// Prefer dedicated tag, else second value in #astro
	if vals := filter.Tags["surface"]; len(vals) > 0 {
		return normalizeSurface(vals[0])
	}
	for _, v := range filter.Tags["astro"] {
		if v == SurfacePosts || v == SurfaceForYou {
			return v
		}
	}
	// Infer from requested kinds
	for _, k := range filter.Kinds {
		switch k {
		case 20, 21, 22, 1063, 34235, 34236, 30923:
			return SurfaceForYou
		}
	}
	return SurfacePosts
}

func normalizeSurface(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	if s == SurfaceForYou || s == "stories" || s == "lak" || s == "for_you" {
		return SurfaceForYou
	}
	return SurfacePosts
}

func kindAllowed(kind int, kinds []int) bool {
	for _, k := range kinds {
		if k == kind {
			return true
		}
	}
	return false
}

// GetSurfaceFeed returns the next interest batch for (user, surface).
func GetSurfaceFeed(ctx context.Context, userID string, limit int, surface string) ([]nostr.Event, error) {
	surface = normalizeSurface(surface)
	now := time.Now()
	if limit <= 0 || limit > interestBatchSize {
		limit = interestBatchSize
	}
	kinds := surfaceKinds(surface)
	cacheKey := getCacheKey(userID, surface)

	if cached, ok := getCachedPool(userID, surface); ok && now.Sub(cached.Timestamp) < feedCacheDuration {
		fresh, _ := repository.userHasEngagementSince(ctx, userID, cached.Timestamp)
		if fresh {
			invalidateUserFeedCache(userID)
		} else {
			log.Printf("astro-recommend: cache hit user=%s… surface=%s", truncateRunes(userID, 8), surface)
			return serveSurfaceBatch(userID, surface, cached, limit), nil
		}
	}

	pendingRequestsMutex.Lock()
	if pending, exists := pendingRequests[cacheKey]; exists {
		pendingRequestsMutex.Unlock()
		<-pending
		if cached, ok := getCachedPool(userID, surface); ok {
			return serveSurfaceBatch(userID, surface, cached, limit), nil
		}
		return nil, fmt.Errorf("surface feed generation failed after wait")
	}
	pending := make(chan struct{})
	pendingRequests[cacheKey] = pending
	pendingRequestsMutex.Unlock()
	defer func() {
		pendingRequestsMutex.Lock()
		close(pending)
		delete(pendingRequests, cacheKey)
		pendingRequestsMutex.Unlock()
	}()

	log.Printf("astro-recommend: building pool user=%s… surface=%s", truncateRunes(userID, 8), surface)

	var similarFeed []FeedNote
	// Similar-to-engagement ONLY — no viral/author/own filler in the recommendation pool.
	seenSimilar := map[string]bool{}
	for _, kind := range kinds {
		part, e := repository.GetSimilarMediaNotes(ctx, userID, similarPoolLimit/len(kinds)+10, kind)
		if e != nil {
			log.Printf("similar kind=%d: %v", kind, e)
			continue
		}
		for _, n := range part {
			if !seenSimilar[n.Event.ID] {
				seenSimilar[n.Event.ID] = true
				similarFeed = append(similarFeed, n)
			}
		}
	}

	ranked := buildSurfaceRankedFeed(similarFeed, kinds)
	pool := SurfacePool{Ranked: ranked, Offset: 0, Timestamp: now}
	userFeedCache.Store(cacheKey, pool)
	log.Printf("astro-recommend: cached surface=%s ranked=%d similar=%d", surface, len(ranked), len(similarFeed))
	return serveSurfaceBatch(userID, surface, pool, limit), nil
}

func getCachedPool(userID, surface string) (SurfacePool, bool) {
	if v, ok := userFeedCache.Load(getCacheKey(userID, surface)); ok {
		return v.(SurfacePool), true
	}
	return SurfacePool{}, false
}

func serveSurfaceBatch(userID, surface string, pool SurfacePool, limit int) []nostr.Event {
	cacheKey := getCacheKey(userID, surface)
	if limit <= 0 || limit > interestBatchSize {
		limit = interestBatchSize
	}
	if len(pool.Ranked) == 0 {
		// No true recommendations — return empty so the client shows ME live chronology
		// WITHOUT marking every new post as a recommendation.
		log.Printf("astro-recommend: empty similar pool surface=%s — returning empty (client falls back to ME live)", surface)
		return nil
	}
	if pool.Offset >= len(pool.Ranked) {
		log.Printf("astro-recommend: similar pool exhausted surface=%s — empty (no recent-as-rec)", surface)
		return nil
	}
	start := pool.Offset
	end := start + limit
	if end > len(pool.Ranked) {
		end = len(pool.Ranked)
	}
	batch := pool.Ranked[start:end]
	pool.Offset = end
	userFeedCache.Store(cacheKey, pool)

	out := make([]nostr.Event, 0, len(batch))
	for _, n := range batch {
		out = append(out, n.Event)
	}
	log.Printf("astro-recommend: serve surface=%s offset=%d→%d size=%d user=%s…",
		surface, start, end, len(out), truncateRunes(userID, 8))
	return out
}

func serveRecentFallbackSurface(surface string, limit int) []nostr.Event {
	kinds := surfaceKinds(surface)
	out := make([]nostr.Event, 0, limit)
	seen := map[string]bool{}
	for _, kind := range kinds {
		if len(out) >= limit {
			break
		}
		recent, err := repository.GetRecentNotes(context.Background(), limit*2, kind)
		if err != nil {
			continue
		}
		for _, n := range recent {
			if seen[n.Event.ID] {
				continue
			}
			seen[n.Event.ID] = true
			out = append(out, n.Event)
			if len(out) >= limit {
				break
			}
		}
	}
	return out
}

func buildSurfaceRankedFeed(similar []FeedNote, kinds []int) []FeedNote {
	byID := map[string]FeedNote{}
	add := func(n FeedNote, boost float64) {
		if !kindAllowed(n.Event.Kind, kinds) {
			return
		}
		n.Score += boost
		if prev, ok := byID[n.Event.ID]; ok {
			if n.Score > prev.Score {
				byID[n.Event.ID] = n
			}
			return
		}
		byID[n.Event.ID] = n
	}

	// STRICT: recommendations = similar-to-engagement ONLY.
	if len(similar) == 0 {
		log.Printf("astro-recommend: no similar matches — empty ranked (ME live shown without rec badge)")
		return nil
	}
	for _, n := range similar {
		add(n, 0)
	}

	out := make([]FeedNote, 0, len(byID))
	for _, n := range byID {
		out = append(out, n)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Score == out[j].Score {
			return out[i].Event.ID < out[j].Event.ID
		}
		return out[i].Score > out[j].Score
	})
	log.Printf("astro-recommend: similar-only pool size=%d", len(out))
	return out
}

// Legacy name kept for viral refresh helpers that still call GetUserFeed by kind.
func GetUserFeed(ctx context.Context, userID string, limit, kind int) ([]nostr.Event, error) {
	surface := SurfacePosts
	for _, k := range forYouKinds {
		if k == kind && kind != 1 {
			surface = SurfaceForYou
			break
		}
	}
	return GetSurfaceFeed(ctx, userID, limit, surface)
}

func getCachedUserFeeds(userID string, kind int) (CachedFeeds, bool) {
	return CachedFeeds{}, false
}

// CachedFeeds kept for compile compat with older helpers.
type CachedFeeds struct {
	Feeds           map[int][][]FeedNote
	Ranked          map[int][]FeedNote
	Offset          map[int]int
	Timestamp       time.Time
	LastServedIndex int
}

func splitIntoBatches(ranked []FeedNote, batchSize int) [][]FeedNote {
	if batchSize <= 0 {
		batchSize = interestBatchSize
	}
	var out [][]FeedNote
	for i := 0; i < len(ranked); i += batchSize {
		end := i + batchSize
		if end > len(ranked) {
			end = len(ranked)
		}
		out = append(out, ranked[i:end])
	}
	return out
}

func getWeightFloat64(envKey string) float64 {
	weight := strings.TrimSpace(os.Getenv(envKey))
	log.Printf("Fetching environment variable for %s: %s", envKey, weight)
	if weight == "" {
		return 1
	}
	w, err := strconv.ParseFloat(weight, 64)
	if err != nil {
		log.Printf("Error parsing float for %s: %v, defaulting to 1", envKey, err)
		return 1
	}
	return w
}

func calculateRecencyFactorWithDecay(createdAt time.Time, decay float64) float64 {
	if decay <= 0 {
		decay = 1.2
	}
	hours := time.Since(createdAt).Hours()
	if hours < 0 {
		hours = 0
	}
	return math.Exp(-decay * hours / 24.0)
}
