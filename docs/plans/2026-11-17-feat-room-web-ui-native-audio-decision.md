# Room web UI + native audio: architecture decision

Status: reversed (2026-11-17) — the room UI stays native. See "Update" below.

## Update (2026-11-17): the room UI stays native

The web-UI direction below is **withdrawn**. Native is the better fit here, and
the room UI, the cinematic gift presentation and the gift rendering stay in
Compose, in the app.

What this means concretely:

- No web page for the room. No sandboxed-host work for it.
- The cinematic gift presentation is built natively, over the existing
  `AlphaVideoGiftPlayer` / `SvgaGiftPlayer` / `RoomGiftOverlay` path.
- The gallery bug is still real and still worth fixing, but as a plain
  WebView capability gap in the app (add `onShowFileChooser` to the embedded
  host) — not as a step toward a web room.
- The MoQ/WebTransport facts below remain true and useful; only the conclusion
  drawn from them is reversed.

Everything under "Decision (superseded)" is kept as the record of the
alternative that was considered and dropped.

## Decision (superseded)

Build the **room UI as web content** that runs inside the app's existing
sandboxed web host, and keep **audio on the existing MoQ-over-WebTransport
plane**, decoded and encoded in-page with WebCodecs. Native code keeps what
only native can do well: microphone capture, the foreground service, the relay
connection, and lifecycle.

**Do not adopt LiveKit, and do not add a second media plane (WebRTC/SFU).**

## Why this, and not LiveKit

The room's voice plane is already MoQ over WebTransport — not WebRTC. See
`nestsClient/src/commonMain/kotlin/com/vitorpamplona/nestsclient/transport/WebTransportSession.kt`
and `NestsConnect.kt` (the connection path is `/<moqNamespace>?jwt=<token>`,
handshaked as a moq-lite session; audio rides an `audio/data` track of Opus
packets). The app's WebRTC stack
(`amethyst/src/main/java/com/vitorpamplona/amethyst/service/call/`) serves
DMs and calls; the room deliberately does not use it.

LiveKit would mean running a WebRTC SFU **next to** the MoQ relay: two media
planes, two scaling stories, two sets of auth. The room's chat, presence and
stage already ride the Nostr relay, so the SFU would carry only voice — the
smallest part of the room — at the cost of a whole new server to operate.

The decisive fact: **the web side of the MoQ plane already works.** The
browser interop harness
(`nestsClient/tests/browser-interop/src/listen.ts`) runs headless Chromium
with `@moq/lite` + `@moq/hang` against the *same* relay subprocess the Kotlin
speaker uses (`NativeMoqRelayHarness`), subscribes to `<broadcast>/audio/data`,
and decodes each Opus packet through the WebCodecs `AudioDecoder`. It is
driven by Playwright and gates on `-DnestsBrowserInterop=true`. So "web client
that hears the room" is proven, not hypothetical.

WebTransport is first-class in Chromium and Android WebView, so the page gets
the same low-latency transport the native client uses, with no plugin and no
second protocol. Audio stays Opus end to end, in both directions.

## Where the web content runs

The app already has a sandboxed web host:

- `commons/src/commonMain/composeResources/files/napplet/shell.html` — the
  trusted shell page; it hosts the app in an iframe on the app's own origin
  and relays capability messages between the page and the native bridge
  (`__nappletBridge`).
- `amethyst/.../ui/screen/loggedIn/embed/EmbeddedSurfaceController.kt` — keeps
  each session's `SandboxedSdkView` attached (privacy-sandbox process) so a
  warm room survives being hidden.
- `EmbeddedNostrAppController.kt` / `EmbeddedWebAppController.kt` — the two
  controllers over that host.

The room UI ships as one more app in that host. The web layer owns layout and
gift rendering; the bridge carries the capabilities the page cannot get on its
own.

## The blocker to fix first

The embedded host sets up **no `WebChromeClient` at all** (grep for
`onShowFileChooser` under `ui/screen/loggedIn/` finds none in the host path).
That is why tapping "choose file" in the in-app WebView does nothing: the
callback is unimplemented. The standalone gift uploader app is fine — it
implements the callback itself.

This is the only host capability the web room needs. Because **microphone
capture stays native** (see below), the page never asks for mic permission, so
`onPermissionRequest` is not required.

## The microphone stays native

Capture, the foreground service, and the mic indicator stay in Kotlin exactly
as today: the native side encodes Opus and publishes it on the `audio/data`
track over the existing MoQ session. The web layer only **subscribes and
plays**.

This removes the whole question of whether a page can hold the mic, and it
removes the need for `AudioEncoder` in the browser. The page is a listener, and
a listener needs nothing but WebTransport and `AudioDecoder` — both of which
the interop harness already exercises.

## Phases

**Phase 0 — file chooser.** Add a `WebChromeClient` to the sandbox host with
`onShowFileChooser`, restoring the file picker in embedded pages. This also
fixes the reported gallery bug.

**Phase 1 — read-only web room.** Render stage, chat and gifts in the page.
Subscribe to `audio/data` with `@moq/lite` + `@moq/hang` and play through
WebAudio, reusing the decoder path the interop harness already exercises. The
mic is published by the native side, unchanged.

**Phase 2 — gifts in the web layer.** Render gifts with SVGAPlayer-Web and the
alpha-packed MP4. The packer now tags the video full-range BT.709, so a web
renderer and the native renderer decode the same colours — which also closes
the long-standing "preview looks right, the room looks washed out" gap by
making preview and room the *same* renderer.

**Phase 3 — native fallback.** Keep the Compose room for WebViews without
WebTransport or WebCodecs, selected by capability probe rather than a version
check.

## Risks

- **WebTransport + WebCodecs decode** must be present in the Android WebView
  build we ship; the interop harness covers Chromium, so verify the WebView
  version floor.
- **Battery**: a page listening while native captures is more moving parts than
  the current native-only listener. Measure before Phase 2.

## Verification

- Extend `nestsClient/tests/browser-interop` with a room-shaped case: a
  headless Chromium subscribes to the room's audio track and receives
  continuous PCM, asserted by Playwright.
- Add a case that `onShowFileChooser` is reached from an embedded page.
- Keep the existing relay wire tests green; they pin the protocol the web
  client must match.
