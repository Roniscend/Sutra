# Sutra

**Voice calls die before data does. Sutra sends the meaning instead of the
audio — about 40 bytes a sentence — so people can still talk when the network
can no longer carry a call.**

A phone call needs a steady 16–48 kbps. When a mobile network degrades, that is
the first thing to go: the bars still show, a trickle of data still moves, and
the call will not hold. A flooded village, a landslide in the hills, a fishing
boat 20 km offshore, a border post above the tree line, half a million people at
a mela with every tower saturated. It is also the satellite service India
launched for exactly these places, which carries SOS and text — not voice.

So the people who most need to be heard are the ones whose voices cannot get
through, and everyone else's answer is to type. Typing is the wrong answer for
someone who is frightened, whose hands are full, who is holding a child on a
roof, or who cannot read the script their phone offers.

Sutra's answer is to stop sending audio. The phone recognises the speech
locally, measures how it was said, sends a frame of a few dozen bytes, and the
listener's end rebuilds the speech — in the listener's language.

---

## Architecture

One Android app with two roles, chosen on the first screen, plus a Python
backend that holds the Agora credentials and drives the Conversational AI agent.

```
╔═══════════════════════════════════╗        ╔═══════════════════════════════════╗
║  FIELD PHONE          arm64       ║        ║  CONTROL ROOM      any ABI        ║
║  role: thin link                  ║        ║  role: good network               ║
╠═══════════════════════════════════╣        ╠═══════════════════════════════════╣
║                                   ║        ║                                   ║
║  FieldScreen / FieldViewModel     ║        ║  ControlPanel                     ║
║            │                      ║        ║            │                      ║
║            ▼                      ║        ║            ▼                      ║
║  FieldSpeech      16 kHz capture  ║        ║  SutraControlBridge               ║
║            │                      ║        ║            │                      ║
║      ┌─────┴─────┐                ║        ║            │                      ║
║      ▼           ▼                ║        ║            │                      ║
║  Whisper     ProsodyAnalyzer      ║        ║            │                      ║
║  Recognizer   pitch, energy,      ║        ║            │                      ║
║  (itantra)    pauses, urgency     ║        ║            │                      ║
║      │           │                ║        ║            │                      ║
║      └─────┬─────┘                ║        ║            │                      ║
║            ▼                      ║        ║            │                      ║
║  TextCodec + RangeCoder           ║        ║            │                      ║
║  SymbolTable    Chunking          ║        ║            │                      ║
║            │                      ║        ║            │                      ║
║            ▼                      ║        ║            │                      ║
║  SutraWire      frame + CRC16     ║        ║  SutraWire  decode + verify       ║
║            │                      ║        ║            ▲                      ║
║            ▼                      ║        ║            │                      ║
║  SutraLink ◄── DeliveryTracker    ║        ║  SutraLink │  ack immediately     ║
╚════════════╪══════════════════════╝        ╚════════════╪══════════════════════╝
             │                                            │
             │        ~40–110 byte Sutra frames           │
             └──────────►  Agora RTC data stream  ────────┘
                          no audio published,
                          no audio subscribed
                                    │
                                    ▼
             ╔══════════════════════════════════════════════╗
             ║  PYTHON BACKEND        FastAPI               ║
             ╠══════════════════════════════════════════════╣
             ║  routes.py      /v1/sutra/report             ║
             ║                 /v1/sutra/relay              ║
             ║                 /v1/sutra/outbound           ║
             ║  sutra.py       translate + report log       ║
             ║  agora_client.py  agent lifecycle, tools     ║
             ║  security.py    RTC/RTM token minting        ║
             ║  session_store.py  TTL session state         ║
             ╚═══════════════╪══════════════════╪═══════════╝
                             │                  │
                Groq (~170 ms)│                  │ Agora Conversational AI
                Gemini fallback                  ▼
                             │      ╔═════════════════════════════╗
                             └─────►║  THE AGENT                  ║
                                    ║  speaks reports aloud       ║
                                    ║  urgent → INTERRUPT         ║
                                    ║                             ║
                                    ║  tool: getFieldReports      ║
                                    ║    → /v1/tools/field_reports║
                                    ║  tool: relayToField         ║
                                    ║    → /v1/tools/relay        ║
                                    ╚═════════════════════════════╝
```

**The agent is the control room, not a feature.** It is the only voice there,
and its two tools are the entire relay. `getFieldReports` returns every message
the field actually sent, so the agent quotes the record instead of inventing a
casualty count. `relayToField` puts the officer's spoken instruction back on the
thin link, translated and compressed. Take the agent out and there is no control
room.

### Why the backend exists

`AGORA_APP_CERTIFICATE` never reaches the phone. The backend mints short-lived
RTC and RTM tokens, starts and stops the agent, and hosts the two tool endpoints
Agora calls back into — which is why it needs a public HTTPS URL during
development.

### Request path, field to control room

| Step | Where | What happens |
|---|---|---|
| 1 | `FieldSpeech` | records 16 kHz PCM and keeps the samples |
| 2 | `WhisperRecognizer` | on-device recognition, no network |
| 3 | `ProsodyAnalyzer` | pitch, energy, pauses, urgency from the same samples |
| 4 | `TextCodec` + `RangeCoder` | script-relative 7-bit offsets, range coded |
| 5 | `SutraWire` | frame it, CRC16-CCITT |
| 6 | `SutraLink` | send over the Agora data stream, track the ack |
| 7 | `SutraControlBridge` | decode, verify, acknowledge |
| 8 | `POST /v1/sutra/report` | translate via Groq, append to the report log |
| 9 | agent | speak it aloud; interrupt if the frame said urgent |

---

## The frame

```
┌───────┬────────────┬───────┬─────┬──────┬────────┬─────────┬───────┐
│ magic │ ver | type │ flags │ seq │ lang │ length │ payload │ crc16 │
│  1 B  │  3b |  5b  │  1 B  │ 2 B │ 1 B  │  1 B   │  ≤255 B │  2 B  │
└───────┴────────────┴───────┴─────┴──────┴────────┴─────────┴───────┘
payload = word count + prosody (2 B + 6 bits/word) + range-coded text
```

**Text.** The nine Brahmic scripts sit in parallel 0x80-wide Unicode blocks with
the same internal layout. The packet names the script once, every character
becomes a 7-bit offset, and one static frequency table serves all nine scripts
through a carry-less range coder. Anything outside the alphabet — a Latin name
in Hindi, an emoji, a second script — escapes, so the codec is lossless for any
string.

**Prosody.** Six bits per word: pitch and loudness relative to the speaker's own
median, plus timing (lengthened, short pause, long pause). Relative rather than
absolute, because the receiver renders with a different voice — what must
survive is "this word was raised", not "this word was 220 Hz". A 2-byte header
carries median pitch, speaking rate, overall loudness and an urgency estimate.

**Urgency** decides whether the control room's agent interrupts, and whether a
field phone announces an instruction on the alarm stream or queues it.

---

## Measurements

All produced by the test suite, not by hand.

| | |
|---|---|
| 3.6 s of spoken Hindi, on device | **40 bytes — 89 bits per second** |
| Real 9.12 s FLEURS Hindi clip | **108 bytes — ~95 bits per second** |
| The same speech as a voice call | ~24 000 bits per second |
| Data that has to move | **200–270× less** |
| Held-out Indic text vs UTF-8 | **3.53× smaller** (3.38–3.79× across nine scripts) |
| Prosody analysis, 9 s of audio | 34–40 ms (desktop JVM) |
| Hindi → English translation | Groq, **121–204 ms**; Gemini fallback ~1.2 s |
| On-device recognition | 17.1 % WER Hindi, 0.37× realtime on Snapdragon 8 Gen 3 |
| Delivery at 25 % frame loss | 6 of 6 messages still arrived |

The compression model is trained on `phrasebook.json` only and measured on the
separate `eval_utterances.json`, so the ratio is not reported on its own
training data. The 9.12 s clip is general-domain speech, not disaster phrases.

---

## Layout

```
app/src/main/java/live/sutra/
  codec/    RangeCoder, SymbolTable, TextCodec, Prosody, ProsodyAnalyzer,
            Chunking, SutraWire — compression and framing
  link/     SutraLink, DeliveryTracker — the data stream and ack accounting
  field/    FieldSpeech, FieldViewModel, ProsodicSpeaker, SpeechRouting
  control/  SutraControlBridge — decode, ack, drive the backend
  data/     SutraApi — Retrofit client
  ui/       RoleScreen, FieldScreen, ControlPanel

app/src/main/java/live/itantra/
  domain/   Normalize, Phrasebook, Relay, Wire — phonetic key, frame codec
  speech/   WhisperRecognizer, HybridRecognizer, AudioCapture, Speech
  transport/ FrameLink, FrameReader, Transport, BluetoothSppTransport

app/src/main/cpp/          whisper.cpp JNI bridge, grammar-constrained decoding
app/src/main/assets/       phrasebook, 10 GBNF grammars, the speech model

server/app/
  routes.py        HTTP surface
  sutra.py         the relay: translation, report log
  agora_client.py  agent lifecycle and tool declarations
  security.py      RTC/RTM token minting
  session_store.py TTL-bounded session state
```

Frames already travel through a transport interface with Bluetooth and loopback
implementations behind it, so the same 40-byte frames fit an SMS or a satellite
text channel without touching the codec.

---

## Running it

You need Android Studio with JDK 17+, the Android NDK, Python 3.10+, Bash, and
an Agora account with access to Conversational AI.

```bash
git clone https://github.com/Roniscend/Sutra.git sutra && cd sutra
./tools/fetch-deps.sh          # clones whisper.cpp into vendor/
```

The speech model is committed, so there is nothing else to download. Then bring
up the backend:

```bash
python -m venv server/.venv
./server/.venv/Scripts/python.exe -m pip install -r server/requirements-dev.txt
cp server/.env.example server/.env.local
#   AGORA_APP_ID, AGORA_APP_CERTIFICATE, GROQ_API_KEY
./server/.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

The agent's tools are called by Agora, so the backend has to be publicly
reachable:

```bash
cloudflared tunnel --url http://127.0.0.1:8000
```

Point both sides at the tunnel hostname — `PUBLIC_BASE_URL` in
`server/.env.local` and `QUICKSTART_SERVER_URL` in `local.properties` — restart
the server, then build:

```bash
./gradlew :app:assembleDebug
```

`./run-sutra.sh` does all of that in one go, and `--build` rebuilds the APK too.
A quick tunnel gets a fresh hostname every restart, and the URL is compiled into
`BuildConfig`, so the APK has to be rebuilt whenever it changes.

On the phones: open the app, keep the channel name identical on both devices,
then choose **field phone** on the weak-link device and **control room** on the
other. The arm64-v8a APK carries the speech model; the x86_64 one does not,
because the control room never runs the recogniser.

`AGORA_APP_CERTIFICATE` stays in `server/.env.local` and is never compiled into
Android. A development tunnel is public while it runs, so stop it when you are
done and add real authentication before adapting any of this for production.

---

## Tests

```
75 Android unit tests
20 backend tests
```

Worth knowing what they pin: every single-bit flip in a frame is caught, every
truncation is rejected, an iTantra frame is never decoded as a Sutra one, a
frame whose word count disagrees with its text is treated as corruption, long
speech splits at word boundaries and rebuilds exactly, and an unacknowledged
frame becomes *lost* rather than pending forever.

```bash
./gradlew :app:testDebugUnitTest
./server/.venv/Scripts/python.exe -m pytest server/tests
```
