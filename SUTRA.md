# Sutra — voice conversations over a link too thin for a call

Sutra sends **meaning, not audio**. A field phone recognises speech on the
device, measures how it was said, and puts roughly a hundred bytes on the wire.
An Agora Conversational AI agent in the control room turns that back into
speech in the officer's language, and carries the officer's instructions back
down the same thin link.

Measured on a real 9.12 s Hindi clip: **108 bytes, about 95 bits per second**,
against roughly 24 000 bits per second for a voice call.

This project is built on two things:

- the **Agora Conversational AI Android quickstart** (MIT), which
  owns the RTC/RTM lifecycle, token generation on a Python backend and the
  agent lifecycle;
- **iTantra** (`../android`), our offline multilingual speech relay for SIH
  problem statement 26173 (ISRO), which owns the on-device Hindi speech
  recognition, the cross-script phonetic key and the wire discipline. Its
  sources are compiled from their original location, not copied, and the
  iTantra app itself is untouched — it keeps its "no INTERNET permission"
  guarantee.

---

## Why

When a network degrades, voice calls fail first: they need a steady 16–48 kbps.
Data often still trickles. Floods, landslides, fishing boats offshore, border
villages and overloaded towers at melas all produce the same situation — the
phone shows a bar of signal and a call will not hold.

India's satellite-to-phone service today carries SOS and text messages, not
voice. A relay that needs only a few hundred bytes per sentence fits inside
that kind of link.

## How it works

```
FIELD PHONE (thin link)                      CONTROL ROOM (good network)
──────────────────────                       ────────────────────────────
push-to-talk
  └ whisper.cpp on-device  (iTantra)
  └ prosody: pitch, energy, pauses
  └ TextCodec + range coder
       ↓ ~100 B Sutra frame
   Agora data stream  ────────────────────▶  decode, ACK immediately
   (no audio published                        POST /v1/sutra/report
    or subscribed)                            └ Groq translates (~170 ms)
                                              └ agent SPEAKS it aloud
                                                 urgent → INTERRUPT
                                                 routine → APPEND
                                              officer asks a question
                                              └ agent calls getFieldReports
                                                 and answers from the log
                                              officer gives an instruction
                                              └ agent calls relayToField
   play with the sender's   ◀───────────────  backend translates to Hindi,
   pitch, rate and pauses                     control app encodes + sends
```

The agent is not decoration. It is the only voice in the control room, and the
two tools it holds are the entire relay: `getFieldReports` reads what the field
actually said, `relayToField` puts the officer's words on the thin link.

## What is in the frame

```
┌───────┬────────────┬───────┬─────┬──────┬────────┬─────────┬───────┐
│ magic │ ver | type │ flags │ seq │ lang │ length │ payload │ crc16 │
│  1 B  │  3b |  5b  │  1 B  │ 2 B │ 1 B  │  1 B   │  ≤255 B │  2 B  │
└───────┴────────────┴───────┴─────┴──────┴────────┴─────────┴───────┘
payload = word count + prosody (2 B + 6 bits/word) + range-coded text
```

**Text.** The nine Brahmic scripts sit in parallel 0x80-wide Unicode blocks
with the same internal layout — the observation iTantra's phonetic key is built
on. The packet names the script once, every character becomes a 7-bit offset,
and one static frequency table serves all nine scripts through a carry-less
range coder. Anything outside the alphabet (a Latin name in Hindi, an emoji, a
second script) escapes, so the codec is lossless for any string.

**Prosody.** Six bits per word: pitch and loudness relative to the speaker's
own median, plus timing (lengthened, short pause, long pause). Relative, not
absolute, because the receiver renders with a different voice — what must
survive is "this word was raised", not "this word was 220 Hz". A 2-byte header
carries median pitch, speaking rate, overall loudness and an urgency estimate.

**Urgency** decides whether the control room's agent interrupts, and whether a
field phone announces an instruction on the alarm stream or queues it.

## Measurements

All produced by the test suite, not by hand.

| | |
|---|---|
| Real 9.12 s FLEURS Hindi clip | **108-byte frame, ~95 bps** (~250× less than a 24 kbps call) |
| Frame contents | 78 B text + 20 B prosody (24 words) + 9 B framing |
| Held-out Indic text vs UTF-8 | **3.53× smaller** (3.38–3.79× across nine scripts) |
| Prosody analysis, 9 s of audio | 34–40 ms (desktop JVM; on-device pending) |
| Hindi→English translation | Groq `qwen3.8-27b`, **121–204 ms**; Gemini fallback ~1.2 s |
| On-device recognition (iTantra) | 17.1 % WER Hindi, 0.37× realtime on Snapdragon 8 Gen 3 |

The compression model is trained on `phrasebook.json` only and measured on the
separate `eval_utterances.json`, so the ratio is not reported on its own
training data. The 9.12 s clip is general-domain speech, not disaster phrases.

## Tests

```
68 Android unit tests   (quickstart 26 + Sutra 42)
19 backend tests        (quickstart 10 + Sutra  9)
63 iTantra tests        unchanged, still passing
```

Worth knowing what they pin: every single-bit flip in a frame is caught, every
truncation is rejected, an iTantra frame is never decoded as a Sutra one, a
frame whose word count disagrees with its text is corruption, long speech
splits at word boundaries and rebuilds exactly, and an unacknowledged frame
becomes *lost* rather than pending forever.

## Running it

```bash
# 1. backend
cd sutra/server
python -m venv .venv && ./.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
# .env.local needs AGORA_APP_ID, AGORA_APP_CERTIFICATE, GROQ_API_KEY
./.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 2. public HTTPS (the agent's tools are called by Agora, so this must be public)
cloudflared tunnel --url http://127.0.0.1:8000

# 3. point both at the tunnel URL
#    sutra/server/.env.local : PUBLIC_BASE_URL=https://<host>
#    sutra/local.properties  : QUICKSTART_SERVER_URL=https://<host>
#    restart the server after changing PUBLIC_BASE_URL

# 4. app
cd sutra && ./gradlew :app:assembleDebug
```

A quick tunnel gets a new hostname every restart; both files and the APK have
to be updated when it changes, because the URL is compiled into `BuildConfig`.

On the phone: open the app, keep the channel name identical on both devices,
then choose **field phone** on the weak-link device and **control room** on the
other.

## Status

Working and verified on this machine:

- the codec, framing, chunking, delivery tracking and prosody analysis, under test;
- the backend relay in both directions, including the agent's two tools;
- a real Agora agent starting and stopping on the live project, with the tools attached;
- the app building, including the native whisper.cpp build for arm64.

Not yet verified, because it needs two phones:

- audio in and out on device (recognition, the data stream, spoken playback);
- the measured channel bitrate shown in the field meter;
- whether the agent's tool calls behave as intended in a live conversation.

Known limits, stated plainly:

- **Not the speaker's own voice.** Agora-managed TTS offers preset voices; the
  frame carries a voice fingerprint's worth of information (median pitch) but
  cloning needs a vendor account we do not have yet.
- **Word timing is approximate.** iTantra's JNI does not expose whisper's word
  timestamps, so words are laid out proportionally across the speech.
- **Urgency is a heuristic**, not a trained emotion model, and is used only as
  a rendering and priority hint.
- **Hindi is the only bundled on-device language.** The other nine need the
  multilingual model iTantra's README §3.1 describes; Android's own recognizer
  covers them where the OEM installed it.
- Turn-by-turn, like a walkie-talkie — one to two seconds per utterance, not a
  continuous call.
