package main
import (
  "fmt"
  "github.com/nbd-wtf/go-nostr/nip19"
)
func main() {
  _, v, err := nip19.Decode("npub1m8vjhr93c5xm70cmlrjha7ta8zfk9ag4cxq0q7plvj9f05xgy8zqn4knzh")
  if err != nil { panic(err) }
  fmt.Println(v.(string))
}
