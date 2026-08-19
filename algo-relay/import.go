package main

import (
	"context"
	"log"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

const layout = "2006-01-02"

func importNotes(kind int) {
	startDate := time.Now().Add(-7 * 24 * time.Hour)
	startTime, _ := time.Parse(layout, startDate.Format(layout))
	endTime := startTime.Add(24 * time.Hour)

	for {
		dayCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)

		startTimestamp := nostr.Timestamp(startTime.Unix())
		endTimestamp := nostr.Timestamp(endTime.Unix())

		filters := []nostr.Filter{{
			Kinds: []int{kind},
			Since: &startTimestamp,
			Until: &endTimestamp,
			Limit: 500,
		}}

		count := 0
		for ev := range pool.SubManyEose(dayCtx, []string{meRelayURL()}, filters) {
			if err := repository.SaveNostrEvent(ev.Event); err == nil {
				count++
			}
		}
		cancel()

		log.Printf("importing notes of kind=%d from %s to %s saved≈%d", kind, startTime.Format(layout), endTime.Format(layout), count)

		startTime = startTime.Add(24 * time.Hour)
		endTime = endTime.Add(24 * time.Hour)

		if startTime.After(time.Now()) {
			log.Println("✅ imported notes of kind:", kind)
			break
		}
	}
}

/** Faster Stories/video backfill: one window, same SaveNostrEvent path as live ingest. */
func importMediaKinds() {
	kinds := []int{20, 21, 22, 1063, 34235, 34236, 30923}
	since := nostr.Now() - 7*24*60*60
	for _, kind := range kinds {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		filters := []nostr.Filter{{
			Kinds: []int{kind},
			Since: &since,
			Limit: 300,
		}}
		count := 0
		for ev := range pool.SubManyEose(ctx, []string{meRelayURL()}, filters) {
			if err := repository.SaveNostrEvent(ev.Event); err == nil {
				count++
			}
		}
		cancel()
		log.Printf("✅ media import kind=%d saved≈%d", kind, count)
	}
}
