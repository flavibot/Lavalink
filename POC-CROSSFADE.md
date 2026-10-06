# Crossfade proof of concept (FlaviBot fork)

Branch `poc/crossfade`, based on `exp/force-reconnect`. This is a **proof of concept**: it shows
that a node-triggered crossfade fits inside Lavalink's player without touching the protocol or
the Koe send path. It is not production code and is off by default.

## What it does

When `lavalink.server.crossfade.enabled` is true, each `LavalinkPlayer` is built on a
`CrossfadeAudioPlayer` instead of lavaplayer's `DefaultAudioPlayer`. That wrapper is itself an
`AudioPlayer` made of two real lavaplayer players ("decks"):

- **At rest** it forwards every call to the current deck. Frames are the same bytes, Opus
  passthrough is kept, and no codec exists.
- **Armed** (`POST .../crossfade`): the next track B loads on the other deck, paused. Its
  executor keeps buffering while paused, and the wrapper polls it every frame so that lavaplayer's
  60 s cleanup does not kill it.
- **Trigger**: on the Koe poll thread, when the current track A has `fadeMs` left (computed from
  the timecode of the frame just sent) and B has buffered at least one frame, the node starts the
  overlap. It emits `TrackEnd(A, FINISHED)` and then `TrackStart(B)`, clears A's end marker, and
  unpauses B.
- **Overlap**: for `n = remaining / 20 ms` frames it decodes both decks (two libopus decoders),
  mixes them with a linear ramp (B's gain rises sample by sample from 0 to 1, A's is `1 - gB`,
  both channels alike, clamped to 16 bits) and re-encodes (one libopus encoder).
- **End of the ramp**: the old deck is stopped silently, the codecs are closed, and the wrapper
  is a plain forward again, now to the other deck. That deck becomes the spare for the next arm.
- **Gapless fallback**: if B was not ready in time, or A ended before its announced duration,
  the wrapper swaps to B at A's real end, in the same poll, without mixing.

The client only ever sees `TrackEnd(A, FINISHED)` followed by `TrackStart(B)`, once. The handover
never produces a `REPLACED`. B's own preload `TrackStart`, every event of the fading-out deck and
the decks' own pause events are swallowed. Only the current deck's events reach Lavalink's
listeners, re-created with the wrapper as their source.

## Configuration

```yaml
lavalink:
  server:
    crossfade:
      enabled: true     # default false: plain lavaplayer players, and the endpoints below do not exist (404)
      maxFadeMs: 12000  # longest fade a client may ask for
```

The keys are commented out in `LavalinkServer/application.yml.example`.

## REST

All three routes sit under the existing `/v4` authorization filter. They exist only when the flag
is on, so a 404 on them is a capability probe.

### Arm

```sh
curl -sS -X POST "http://localhost:2333/v4/sessions/$SESSION_ID/players/$GUILD_ID/crossfade" \
  -H "Authorization: $LAVALINK_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"track": {"encoded": "<base64 track B>", "userData": {"requester": "x"}}, "fadeMs": 6000}'
```

- `track.encoded` is a Lavalink encoded track, as in `PATCH .../players/{guildId}`. `userData`
  is optional; it is copied onto B, so it shows up in `TrackStart(B)`.
- 200 returns the state (see GET).
- 400: `fadeMs` outside `200..maxFadeMs`, or an encoded track that does not decode.
- 404: unknown session or player.
- 409, any of these:
  - nothing is playing;
  - the current track is a stream;
  - `remaining <= fadeMs` (too late);
  - a crossfade is already armed or running;
  - the output format is not Opus (Lavalink's default is Opus).

### Inspect

```sh
curl -sS "http://localhost:2333/v4/sessions/$SESSION_ID/players/$GUILD_ID/crossfade" \
  -H "Authorization: $LAVALINK_PASSWORD"
```

Response fields:

- `phase`: `IDLE`, `ARMING`, `ARMED` or `OVERLAP`.
- `fadeMs`.
- `current`: `{identifier, title, position, length}` of the track the client sees.
- `next`: `{identifier, title, ready, failed}`, while armed.
- `tail`: `{identifier, title, position}` of the track fading out, during the overlap. This is
  the only place A's position appears once the overlap has started.
- `rampFrame`, `rampFrames`, `gainB`.
- `counters`:
  - `armed`, `overlaps`, `completed`;
  - `endSwaps`: swaps at A's end without a mix;
  - `tailEndedEarly`: A ran out of audio during the ramp;
  - `cutShort`: an overlap ended by a play, a stop or B's end;
  - `disarmed`, `seeksRefused`;
  - `codecOpens`, `mixedFrames`, `prerollFrames`.
- `mixMicros`: `{last, avg, max}`, the cost of decode, mix and encode per mixed frame, measured
  on the Koe poll thread.

### Disarm

```sh
curl -sS -X DELETE "http://localhost:2333/v4/sessions/$SESSION_ID/players/$GUILD_ID/crossfade" \
  -H "Authorization: $LAVALINK_PASSWORD"
```

- 204, also when nothing was armed.
- 409 once the overlap has started.

### Interaction with the existing player API

- `PATCH` with a track while armed or overlapping is an ordinary replace. The crossfade is
  cancelled, the other deck is stopped silently, and the client gets the usual
  `TrackEnd(current, REPLACED)` and `TrackStart(new)`.
- `PATCH` with `noReplace=true` after the handover is a no-op, because B is already current.
- A stop or a destroy cancels everything.
- `PATCH` with a `position` (a seek):
  - while armed, it only moves the trigger; a seek into the last `fadeMs` gives a shorter ramp;
  - during the overlap, it is refused with 409.

  The guard lives in `LavalinkPlayer.seekTo`, so it also covers plugins.
- Pause applies to the current and the fading-out deck. The ramp is frozen while paused.
- Volume, filters and the frame buffer duration are applied to both decks.
- `playerUpdate.state.position` is B's from the overlap's start. The now-playing position
  switches `fadeMs` before A's audio ends.

## Measured mix cost

These numbers come from `OpusFrameCodecTest`, run twice in `azul/zulu-openjdk-debian:17` on
linux-aarch64 (Docker on an arm64 Mac): once in a standalone harness and once under
`./gradlew :Lavalink-Server:test`. The test uses real libopus at 48 kHz stereo. Each 20 ms frame
costs two decodes, the mix and one encode:

| run | case | avg per mixed frame | max |
| --- | --- | --- | --- |
| harness | sine fading out into silence, 20 frames | 208 µs | 310 µs |
| harness | silence fading into a sine, 20 frames | 174 µs | 300 µs |
| gradle | sine fading out into silence, 20 frames | 127 µs | 155 µs |
| gradle | silence fading into a sine, 20 frames | 139 µs | 210 µs |

That is about 0.6 to 1.5 % of the 20 ms frame budget on the Koe poll thread, for one player and
only during its overlap. It was not measured under load, with many players overlapping at once,
or on x86-64. On a live node, the GET's `mixMicros` reports the same figures.

The same test checks the audio. With A as a sine and B as silence, the RMS of each mixed frame
falls along `rmsA * (1 - (k + 0.5) / n)` within 15 %; with them swapped it rises.

## Code

- `LavalinkServer/src/main/java/lavalink/server/player/crossfade/`:
  - `CrossfadeAudioPlayer.kt`: the two-deck `AudioPlayer` (roles, phases, `provide`, event
    forwarding, locks).
  - `FrameMixer.kt`: the `FrameCodec` interface, `OpusFrameCodec` (2 decoders and 1 encoder, with
    complexity `opusEncodingQuality` and libopus' default bitrate) and the linear mixer.
  - `CrossfadeRestHandler.kt`: the three routes and their DTOs, behind
    `@ConditionalOnProperty("lavalink.server.crossfade.enabled")`.
- `LavalinkServer/src/main/java/lavalink/server/config/CrossfadeConfig.kt` and the nullable
  `crossfade` field in `ServerConfig`.
- `LavalinkPlayer.kt`: builds the wrapper when the flag is on, and routes `seekTo` through the
  seek guard.

### Locks

- `stateLock` (roles, phase, generation) and `codecLock` (decode, mix, encode, close) are leaves.
  No deck or track call is made while either is held.
- The poll thread decides on a snapshot, makes its deck calls without a wrapper lock, and commits
  a transition only if the generation did not move. Every REST-side change bumps it.
- `listenerLock` serialises the wrapper's own event dispatch. A forwarded deck event takes it
  inside the deck's `trackSwitchLock`, as Lavalink's listeners already run today.
- Why it matters: an end marker passed by a seek calls `stopTrack()` while lavaplayer holds the
  executor's `actionSynchronizer`. A wrapper lock held across a deck call could deadlock with that.

## Tests

`LavalinkServer/src/test/java/lavalink/server/player/crossfade/`. Plain JUnit 5, no Spring context.

- The helpers are `FakeDeck`, `FakeTrack` and `PcmFrameCodec`:
  - `FakeDeck` plays scripted 20 ms frames and dispatches events under its own lock, as lavaplayer
    does;
  - `PcmFrameCodec` treats a frame as raw PCM, so the arithmetic can be checked sample by sample.
- `FrameMixerTest` (4 tests): ramp monotonic with `gA + gB = 1`, expected values at `k = 0`, `n/2`
  and `n - 1`, clamping, same gain on both channels.
- `CrossfadeAudioPlayerTest` (18 tests):
  - byte-identical passthrough at rest;
  - 40 frames of A, exactly 10 mixed frames, then B unchanged;
  - events `[Start(A), End(A, FINISHED), Start(B)]` with no REPLACED;
  - the tail is stopped once, its marker cleared and the codec closed;
  - disarm, stop and play while armed;
  - a reused deck is stopped before play;
  - seeks refused during the overlap, and moving the trigger while armed;
  - B failing or cleaned up while armed;
  - a tail ending early (10-frame catch-up);
  - B not ready, or A shorter than its duration (gapless swap);
  - B dying during the overlap;
  - play during the overlap (ordinary REPLACED);
  - a re-entrant stop from inside `provide` (the end-marker path);
  - pause freezing the ramp while both decks keep being polled;
  - arm refusals;
  - a 2 s concurrency smoke test, which is not a proof.
- `OpusFrameCodecTest` (2 tests): real libopus through the wrapper. It is skipped, not failed,
  when the natives do not load.

The audio path was **not** tested end to end on a live node with Discord. Nobody has listened to
it yet.

## What an unmodified engine would see (not proven by this POC)

- The voice-manager handles `End(A, FINISHED)` by recording A as finished, advancing, and sending
  `PATCH(next)` with `noReplace=true`. The node ignores that PATCH because B is already current,
  and returns B.
- This stays consistent only if the queue head is the B that was posted, and only if B's
  re-encoded blob compares equal in play-patch-confirm. That is not verified.
- `TrackStart(B)` now arrives before the engine's PATCH, unlike today, and `start.ts` assumes
  `queue.current` is what started.
- The TrackEnd identity guard (C1) is still a prerequisite before any client uses this.

## Known limits and out of scope

- **Client and engine**
  - No voice-manager change: end-of-track clock, peek of the next track, pre-resolve, the C1
    guard, stats.
  - No Shoukaku or protocol change, no `userData` transition instruction, no custom WebSocket
    event, no `/v4/info` capability, no Prometheus metrics.
  - No crossfade on skip or stop.
- **Audio**
  - The curve is linear, not equal-power.
  - No alignment to the true end of the audio and no silence trim.
  - No overlay or ducking, and no fades on pause, seek or stop.
  - No `fadeMs = 0` gapless mode. The end-of-A swap already does it, so it is a near-free
    follow-up.
- **Refused or unsupported**
  - A seek during the overlap (409). A refused seek fails the whole PATCH after earlier fields
    (pause, volume, userData) were already applied.
  - Timescale or rate filters: positions are output timecodes, so the trigger is skewed.
  - `endTime` on the outgoing track: the trigger ignores it and it is cleared at the swap. If it
    fires while armed, everything stops, as today.
  - Streams as the outgoing track (409).
  - A third deck, or arming during an overlap (409).
- **Load and quality**
  - The mix runs on the Koe poll thread, with no pump thread; its cost is only measured.
  - The Opus codecs are created lazily on the poll thread. With the 60 ms pre-roll this happens
    on the first pre-roll frame, which `mixMicros` does not time, so the native allocation cost
    is not visible there.
  - The bitrate and quality of the re-encode were not tuned (libopus default bitrate).
  - The Opus seam at the overlap's start and end is accepted. The 60 ms pre-roll warms decoder A
    and the encoder only.
  - The mix cost was measured on two short runs on one machine (see above).
- **Session and connection**: session resume, moving a player to another node, and a Koe
  reconnect during an overlap are untested.
- **Arming far ahead**: arm at most about 30 s before the end. B's source idles while paused
  once its 5 s buffer is full, and a source idle timeout over a long pause is untested.
- **Deployment**: nothing touches production, the lavalink-setup image or kubernetes.
