package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/fiatjaf/khatru"
	"github.com/fiatjaf/khatru/policies"
	"github.com/joho/godotenv"
	"github.com/nbd-wtf/go-nostr"
)

var ctx = context.Background()
var pool = nostr.NewSimplePool(ctx)
var repository *NostrRepository

// Astro: prioritize ME strfry + a small public set (avoid rate-limits from 40 relays).
var relays = []string{
	"wss://desktop-r082364.tail4b3c94.ts.net/",
	"wss://relay.primal.net",
	"wss://nos.lol",
	"wss://relay.damus.io",
	"wss://nostr.mom",
	"wss://relay.nostr.band",
}

var db *sql.DB
var art = `
 █████╗ ██╗      ██████╗  ██████╗     ██████╗ ███████╗██╗      █████╗ ██╗   ██╗
██╔══██╗██║     ██╔════╝ ██╔═══██╗    ██╔══██╗██╔════╝██║     ██╔══██╗╚██╗ ██╔╝
███████║██║     ██║  ███╗██║   ██║    ██████╔╝█████╗  ██║     ███████║ ╚████╔╝ 
██╔══██║██║     ██║   ██║██║   ██║    ██╔══██╗██╔══╝  ██║     ██╔══██║  ╚██╔╝  
██║  ██║███████╗╚██████╔╝╚██████╔╝    ██║  ██║███████╗███████╗██║  ██║   ██║   
╚═╝  ╚═╝╚══════╝ ╚═════╝  ╚═════╝     ╚═╝  ╚═╝╚══════╝╚══════╝╚═╝  ╚═╝   ╚═╝   
	`

func main() {
	err := godotenv.Overload()
	if err != nil {
		log.Fatalf("Error loading .env file")
	}
	nostr.InfoLogger = log.New(io.Discard, "", 0)
	green := "\033[32m"
	reset := "\033[0m"
	fmt.Println(green + art + reset)

	importFlag := flag.Bool("import", false, "Run the importNotes function after initializing relays")
	importMediaFlag := flag.Bool("import-media", false, "Backfill Stories/video kinds only (last 7 days)")
	reanalyzeNote := flag.String("reanalyze-note", "", "Re-run Gemini analysis for a note id and exit")
	reanalyzeURL := flag.String("reanalyze-url", "", "Run basket visual analysis for a media URL and exit")
	flag.Parse()
	conn, err := getDBConnection()

	if err != nil {
		log.Fatalf("Error getting db connection: %v", err)
	}
	defer conn.Close()
	db = conn
	repository = NewNostrRepository(db)
	weightInteractionsWithAuthor = getWeightFloat64("WEIGHT_INTERACTIONS_WITH_AUTHOR")
	weightCommentsGlobal = getWeightFloat64("WEIGHT_COMMENTS_GLOBAL")
	weightReactionsGlobal = getWeightFloat64("WEIGHT_REACTIONS_GLOBAL")
	weightZapsGlobal = getWeightFloat64("WEIGHT_ZAPS_GLOBAL")
	weightRecency = getWeightFloat64("WEIGHT_RECENCY")
	viralThreshold = getWeightFloat64("VIRAL_THRESHOLD")
	viralNoteDampening = getWeightFloat64("VIRAL_NOTE_DAMPENING")
	decayRate = getWeightFloat64("DECAY_RATE")

	log.Printf("Gemini config enabled=%v model=%s | OpenAI enabled=%v model=%s | ffmpeg=%v me=%s",
		geminiEnabled(), geminiModel(), openaiEnabled(), openaiModel(), findFFmpeg() != "", meRelayURL())

	if *reanalyzeURL != "" {
		resp := runBasketMediaAnalysis(*reanalyzeURL, "video/mp4", "")
		fmt.Printf("status=%s topics=%v tags=%v objects=%v err=%s\n",
			resp.Status, resp.Topics, resp.Tags, resp.Objects, resp.Error)
		return
	}
	if *reanalyzeNote != "" {
		if err := reanalyzeNoteByID(*reanalyzeNote); err != nil {
			log.Fatalf("reanalyze: %v", err)
		}
		return
	}

	purgeMonthsStr := os.Getenv("PURGE_MONTHS")
	if purgeMonthsStr == "" {
		log.Fatal("PURGE_MONTHS environment variable is not set")
	}

	purgeMonths, err := strconv.Atoi(purgeMonthsStr)
	if err != nil {
		log.Fatalf("Invalid PURGE_MONTHS value: %v\n", err)
	}

	if *importMediaFlag {
		log.Println("📦 importing media (Stories/video) for algo ranking")
		importMediaKinds()
		log.Println("📦 done importing media. Please restart relay")
		return
	}

	if *importFlag {
		log.Println("📦 importing notes")
		importNotes(nostr.KindArticle)
		importNotes(20)    // Picture (NIP-68) — Stories
		importNotes(21)    // Video normal
		importNotes(22)    // Video short
		importNotes(1063)  // File metadata (legacy)
		importNotes(34235) // Video horizontal
		importNotes(34236) // Video vertical
		importNotes(nostr.KindTextNote)
		importNotes(nostr.KindReaction)
		importNotes(nostr.KindZap)

		log.Println("📦 done importing notes. Please restart relay")
		return
	}

	go subscribeAll()
	go purgeData(purgeMonths)
	// Gemini analyzes ONLY new media arriving from the ME relay — never backfill history.

	go func() {
		refreshViralNotes(ctx)                // Immediate refresh when the application starts
		go refreshViralNotesPeriodically(ctx) // Start the periodic refresh
	}()

	relay := khatru.NewRelay()
	relay.Info.Description = os.Getenv("RELAY_DESCRIPTION")
	relay.Info.Name = os.Getenv("RELAY_NAME")
	relay.Info.PubKey = os.Getenv("RELAY_PUBKEY")
	relay.Info.Software = "https://github.com/barrydeen/algo-relay"
	relay.Info.Version = "0.2.0-astro"
	relay.Info.Icon = os.Getenv("RELAY_ICON")

	relay.RejectConnection = append(relay.RejectConnection,
		policies.ConnectionRateLimiter(
			10,
			time.Minute*1,
			10,
		),
	)

	relay.RejectFilter = append(relay.RejectFilter,
		policies.FilterIPRateLimiter(
			10,
			time.Second*10,
			10,
		),
	)

	relay.OnConnect = append(relay.OnConnect, func(ctx context.Context) {
		khatru.RequestAuth(ctx)
	})
	relay.RejectFilter = append(relay.RejectFilter, func(ctx context.Context, filter nostr.Filter) (bool, string) {
		// Prefer auth for personalized ranking; still allow anonymous viral/recent
		// so Astro «الرائج» is not empty when AUTH is slow/unavailable.
		if len(filter.Authors) > 0 {
			return true, "this relay is only for algorithmic feeds"
		}
		return false, ""
	})
	relay.RejectEvent = append(relay.RejectEvent, func(ctx context.Context, event *nostr.Event) (bool, string) {
		return true, "you cannot publish to this relay"
	})

	relay.QueryEvents = append(relay.QueryEvents, func(ctx context.Context, filter nostr.Filter) (chan *nostr.Event, error) {
		ch := make(chan *nostr.Event)
		copyFilter := filter
		authenticatedUser := khatru.GetAuthed(ctx)
		if authenticatedUser == "" {
			authenticatedUser = "anonymous"
			log.Println("serving anonymous viral/recent feed (no NIP-42 auth yet)")
		}

		go func() {
			defer close(ch)

			limit := copyFilter.Limit
			if limit == 0 {
				limit = interestBatchSize
			}
			if limit > interestBatchSize {
				limit = interestBatchSize
			}

			mode := astroReqMode(copyFilter)
			surface := astroSurface(copyFilter)
			if mode == "refresh" {
				invalidateUserFeedCache(authenticatedUser)
			}

			events, err := GetSurfaceFeed(ctx, authenticatedUser, limit, surface)
			if err != nil {
				log.Println("Error fetching surface feed:", err)
				return
			}
			// Do NOT post-filter by client kinds: the surface pool is already kind-scoped.
			// Filtering after serveSurfaceBatch advanced the offset left the UI with 0–1 items.
			log.Printf("astro-recommend: returning %d events surface=%s mode=%s user=%s…",
				len(events), surface, mode, truncateRunes(authenticatedUser, 8))
			for i := range events {
				ch <- &events[i]
			}
		}()

		return ch, nil
	})

	log.Println("🚀 Relay started on port 3334")
	mux := relay.Router()

	mux.HandleFunc("/", handleHomePage)
	mux.HandleFunc("/dashboard.html", handleDashboardPage)
	mux.HandleFunc("/api/top-authors", handleTopAuthorsAPI)
	mux.HandleFunc("/auth", handleAuth)
	mux.HandleFunc("/api/settings", handleUserSettings)
	mux.HandleFunc("/api/user-metrics", handleUserMetricsAPI)
	mux.HandleFunc("/api/analyze-media", handleAnalyzeMedia)

	err = http.ListenAndServe(":3334", relay)
	if err != nil {
		log.Fatal(err)
	}

	mux.Handle("/static/", http.StripPrefix("/static/", http.FileServer(http.Dir("./static"))))
	mux.HandleFunc("/", handleHomePage)

	log.Printf("listening at http://0.0.0.0:3334")
	http.ListenAndServe("0.0.0.0:3334", relay)
}

func subscribeAll() {
	now := nostr.Now()
	filters := nostr.Filters{{
		Kinds: []int{
			nostr.KindTextNote,
			nostr.KindReaction,
			nostr.KindZap,
			nostr.KindFollowList,
			nostr.KindArticle,
			20,    // Picture (NIP-68) — Stories
			21,    // Video normal
			22,    // Video short
			1063,  // File metadata (legacy stories)
			34235, // Video horizontal
			34236, // Video vertical
			30923, // Ephemeral stories
		},
		Since: &now, // live only — never replay historical firehose
	}}

	// Separate ME subscription so public relays cannot "win" uniqueness and skip Gemini.
	go subscribeMEForGemini(filters)

	// Strict ME scope: feed storage and ranking source must come only from the ME relay.
	for ev := range pool.SubMany(ctx, []string{meRelayURL()}, filters) {
		err := repository.SaveNostrEvent(ev.Event)
		if err != nil {
			continue
		}
		// Analyze here too: the dedicated Gemini subscription can stall while ingest keeps running.
		scheduleMediaAnalysis(ev.Event)
	}
}

// subscribeMEForGemini analyzes every NEW note/media on the Middle-East strfry (live only).
// Ranking for the app Middle-East filter is served from this algo-relay (interest, not time).
func subscribeMEForGemini(filters nostr.Filters) {
	me := []string{meRelayURL()}
	log.Printf("Gemini ME live analyze-all subscribe: %s (no history backfill)", me[0])
	for ev := range pool.SubMany(ctx, me, filters) {
		_ = repository.SaveNostrEvent(ev.Event)
		scheduleMediaAnalysis(ev.Event)
	}
}

func loadEnv() {
	err := godotenv.Load()
	if err != nil {
		log.Fatalf("Error loading .env file: %v", err)
	}
}

func purgeData(months int) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			repository := NewNostrRepository(db)

			log.Println("Starting data purge...")

			if err := repository.PurgeCommentsOlderThan(months); err != nil {
				log.Printf("Error purging comments: %v\n", err)
			}
			if err := repository.PurgeNotesOlderThan(months); err != nil {
				log.Printf("Error purging posts: %v\n", err)
			}
			if err := repository.PurgeReactionsOlderThan(months); err != nil {
				log.Printf("Error purging reactions: %v\n", err)
			}
			if err := repository.PurgeZapsOlderThan(months); err != nil {
				log.Printf("Error purging zaps: %v\n", err)
			}

			log.Println("Data purge completed.")
		}
	}
}
