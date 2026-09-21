# Sutra — how it works, what we built, and what we fixed

This is the "read this first" document. `SUTRA.md` is the pitch and the
measurements; this one explains what is actually happening inside the app,
why each piece exists, and what went wrong on the way.

---

## 1. The problem in one paragraph

When a mobile network degrades, **voice calls fail before data does**. A call
needs a steady 16–48 kbps; when that is not there, the call drops even though
a trickle of data still gets through. This is the situation in floods and
landslides, on fishing boats offshore, in border villages, and on overloaded
towers at melas. India's satellite-to-phone service carries SOS and text
today, not voice. So the people who most need to talk are the ones who cannot.

**Sutra's answer: stop sending audio. Send the meaning, and rebuild the speech
on the other side.** Speech is recognised on the phone, compressed to roughly
40–110 bytes, and reconstructed by the listener. Measured on a real device:
**3.6 s of Hindi cost 40 bytes — 89 bits per second — where a call would have
cost about 24 000.**

---

## 2. What the app is

One app with two roles, chosen on the first screen. Both join the same Agora
channel by typing the same channel name.

### Field phone — the weak-link side
- Press and hold, speak. **Nothing is streamed.** The microphone track is not
  published, remote audio is not subscribed, audio is disabled outright.
- The phone runs whisper.cpp locally (iTantra's Hindi model), measures how the
  words were said, compresses everything, and sends one small frame.
- Incoming instructions are spoken back through the phone's own text-to-speech,
  with the sender's pitch, pace and pauses applied. Urgent ones play on the
  alarm stream.
- The screen shows what each utterance cost and whether it was acknowledged.

### Control room — the good-network side
- Runs the **Agora Conversational AI agent**, which is the only voice here.
- The agent **speaks each field report** in the officer's language; an urgent
  report interrupts whatever it was saying.
- The agent **answers questions** about the situation using its
  `getFieldReports` tool, quoting the field's own words.
- The agent **relays the officer's instructions** back down the thin link
  through its `relayToField` tool.

---

## 3. What happens, step by step

### Field → control room

1. **Capture.** `FieldSpeech` records at 16 kHz and keeps the samples.
   iTantra's own recogniser could not be reused as-is: it constrains the
   decoder to a 42-message phrasebook (right for a distress relay, wrong for
   open speech) and throws the audio away once it has text, while Sutra needs
   the same samples to measure delivery.

2. **Recognise, on the device.** whisper.cpp with the bundled Hindi model.
   Silence, repetition loops and low-confidence decodes are **rejected, not
   sent** — iTantra's measurements showed the model emitting confident nonsense
   on silence, and a relay that invents a distress message is worse than one
   that asks the user to repeat.

3. **Measure prosody.** `ProsodyAnalyzer` runs YIN pitch detection and RMS
   energy over the same audio and produces **six bits per word**: pitch and
   loudness relative to the speaker's own median, plus timing (lengthened,
   short pause, long pause). Plus a 2-byte header: median pitch, speaking rate,
   overall loudness, and an urgency estimate.

   Relative, not absolute, because the receiver renders with a different voice.
   What must survive is "this word was raised", not "this word was 220 Hz".

4. **Compress the text.** `TextCodec` exploits the fact that the nine Brahmic
   scripts sit in **parallel 0x80-wide Unicode blocks with the same internal
   layout** — the observation iTantra's phonetic key is built on. The packet
   names the script once, each character becomes a 7-bit offset, and a
   carry-less range coder squeezes those against one static frequency table
   that serves all nine scripts. Anything outside the alphabet (a Latin name
   inside Hindi, an emoji, a second script) escapes, so it is lossless for any
   string. Result: **3.53× smaller than UTF-8** on held-out text.

5. **Frame it.** `SutraWire`: 9 bytes of header, CRC16 (iTantra's), payload
   capped at 255 bytes. Longer speech is split at word boundaries by
   `Chunking`, so a lost frame costs a clause, not half a word.

6. **Send.** Over Agora's **data stream** — not audio. `DeliveryTracker`
   watches for the acknowledgement and retries, because "the SDK accepted the
   bytes" is not "the other phone rendered the message".

7. **Control room decodes it**, acknowledges immediately, asks the backend to
   translate (Groq, ~180 ms, Gemini as fallback), and hands the words to the
   agent to **speak aloud**.

### Control room → field

1. The officer speaks. The agent decides this is meant for the field and calls
   its **`relayToField`** tool.
2. The backend translates it into the field language and queues it. The backend
   cannot reach the field itself — it is not in the RTC channel — so it waits.
3. The control-room app, which **does** hold the data stream, polls, encodes
   the instruction as a Sutra frame and sends it.
4. The field phone decodes it and speaks it locally, on the alarm stream if
   urgent.

---

## 4. Where the code lives

```
sutra/
├── app/src/main/java/live/sutra/
│   ├── codec/        TextCodec, RangeCoder, Prosody, ProsodyAnalyzer,
│   │                 SutraWire, Chunking  ← the whole "meaning not audio" idea
│   ├── link/         SutraLink (audio-free Agora channel), DeliveryTracker
│   ├── field/        FieldSpeech, ProsodicSpeaker, FieldViewModel
│   ├── control/      SutraControlBridge  ← frames ⇄ agent ⇄ backend
│   ├── data/         SutraApi (control room's backend client)
│   └── ui/           RoleScreen, FieldScreen, ControlPanel
├── app/src/main/java/com/androidengineers/...   the Agora quickstart (MIT),
│                     patched in four places rather than forked
├── server/app/sutra.py        translation, per-channel report log, relay queue
├── server/app/routes.py       /v1/sutra/* and the agent's two tools
└── server/app/agora_client.py the relay-desk prompt and tool definitions

android/            iTantra (SIH PS 26173) — untouched, still offline,
                    its domain/speech/transport sources compiled from here
```

Nothing from iTantra is copied. Its sources and its native whisper.cpp build
are compiled from their original location, so one fix serves both apps and
iTantra keeps its "no INTERNET permission" guarantee.

---

## 5. What we measured

On a physical OnePlus (field) and a Pixel 9 emulator (control room):

| | |
|---|---|
| 3.6 s of spoken Hindi | **40 bytes on the wire — 89 bps** |
| A voice call, for comparison | ~24 000 bps, continuously |
| Whole Agora channel, measured by Agora | 7 kbps tx / 7 kbps rx, including all protocol overhead |
| On-device recognition | 4.2 s of audio decoded in 2.35 s (0.5× real time), confidence 0.96 |
| Prosody analysis | ~150 ms |
| Translation | Groq 179 ms, and the count survived: "पांच" → "Five" |
| Instruction back to the field | 61 bytes, announced on the alarm stream |
| Held-out Indic text vs UTF-8 | 3.53× smaller, 3.38–3.79× across nine scripts |

Tests: **69 Android, 20 backend, iTantra's 63 unchanged.**

The compression model is trained on `phrasebook.json` and measured on the
separate `eval_utterances.json`, so the ratio is not reported on its own
training data.

---

## 6. What we fixed

### Three bugs the device runs exposed

**1. The agent went silent while everything looked healthy.**
Both roles join one channel, so both ask the backend for a token. The second
request overwrote the session record and **erased the running agent's ID**.
Every later request to speak was rejected with "agent does not match the
session". The link worked, frames arrived, the backend translated them — and
the officer heard nothing. Fixed: the agent belongs to the channel, not to
whoever asked for a token last. Regression test in `test_sutra.py`.

**2. Translations were thrown away on failure.**
Translating and speaking shared one error handler in `SutraControlBridge`, so
when the speak call failed, the translation died with it and the officer saw
untranslated Hindi even though the backend had translated it. They are separate
steps now.

**3. A 429 silenced the agent again.**
The control room polled for queued instructions every 900 ms — about 67
requests a minute against a 60/min limit — so the agent's speak request was
rejected as rate-limited. Same dangerous shape as bug 1: healthy-looking link,
silent room. Poll is now 2.5 s and the demo budget is raised.

**4. The link never came back after the network did.**
Found while testing weak networks: with connectivity gone for a minute, frames
kept being handed to a dead session, nothing was acknowledged, and the screen
still claimed the link was up. The link now reports its connection state,
rejoins with a fresh token when Agora gives up, and resends anything
unacknowledged. See section 8.

**5. The phone received instructions and stayed silent.**
Not the voices: the test phone has all ten installed
(`engine=com.google.android.tts voices=hi:AVAILABLE, bn:AVAILABLE, …`). The
cause was **Android 15+ "audio hardening"**, which mutes playback from an app
that is not holding audio focus:

```
AudioHardening background playback would be muted for
    com.google.android.tts (10157), level: full
```

The message was synthesised, the audio track ran its full length, and nothing
came out — with only a logcat line to say why. The speaker now takes audio
focus for the duration of each message (transient, ducking other audio for
routine traffic and taking it outright for urgent traffic) and releases it
afterwards. Confirmed audible on the phone.

The app also now surveys which voices exist, says so on screen when one is
missing, and offers Android's install flow, because a silent field phone looks
like a broken link rather than a missing download.

### One bug the tests exposed

**Raised words could not be detected in short sentences.** Each word's pitch
was compared against the median of *all* voiced frames, which is pulled toward
whichever word has more of them; in a two-word utterance the reference
collapsed onto one word's own pitch, so that word could never read as raised.
Now the reference is the median of the per-word pitches, taken in log space.

### Practical fixes

- **The APK was 202 MB and would not install.** One build per processor type,
  and Agora's **voice-only SDK** since Sutra never uses video: **94 MB**.
- **Content sat under the status bar** on all three screens: safe-area padding.
- **The emulator kept killing the app** at 2 GB RAM; the AVD now has 4 GB.
- **A debug-only "send test frame" button** pushes a canned Hindi sentence
  through the real codec and data stream, so the link can be demonstrated
  before anyone speaks.

---

## 7. What is still not true

Stated plainly, because a demo that overclaims is worse than a smaller one
that holds.

- **It is not the speaker's own voice.** Agora-managed text-to-speech offers
  preset voices. The frame carries the speaker's median pitch, so delivery and
  urgency survive, but real cloning needs a vendor account we do not have.
- **Word timing is approximate.** iTantra's JNI does not expose whisper's word
  timestamps, so words are laid out proportionally across the speech. Good
  enough for "which part was loud"; it is not forced alignment.
- **Urgency is a heuristic**, not a trained emotion model, and is used only to
  decide whether to interrupt and how to render.
- **Speech output depends on the device's own voices.** That is a deliberate
  trade, and here is the arithmetic behind it. The APK is 94 MB today
  (uncompressed contents: whisper model 30.7 MB, Agora SDK 40.5 MB, code
  60.9 MB, other native 3.2 MB).

  | Option | Added size | Quality | Languages |
  |---|---|---|---|
  | **Platform TTS (current)** | **0 MB** | Natural | All 10 on the test phone |
  | eSpeak-NG bundled | ~4–6 MB | Robotic but intelligible | All 10 |
  | Piper/VITS, one voice | ~25–30 MB | Natural | 1 |
  | Piper/VITS, ten voices | ~200 MB+ | Natural | 10 — not viable |
  | Multilingual VITS fine-tune | ~20–25 MB | Natural | 10, but needs the 4–6 day training run in iTantra's README §3.1 |

  Bundling is therefore **not** the fix for silence — audio focus was. eSpeak-NG
  is the cheap insurance if a demo device turns out to have no voices, at about
  5 MB; Piper only becomes sensible alongside the multilingual model iTantra
  already plans to train.

- **Speaking into the phone is limited by what the device can hear.** All ten
  languages can be *received, decoded and spoken*; dictation depends on the
  handset, and the app now routes and labels each one honestly:

  | Marker | Engine | Languages on the test phone | Prosody |
  |---|---|---|---|
  | ● | bundled whisper.cpp | Hindi | yes |
  | ◐ | the device's own offline recogniser | English only, on this handset | no |
  | ○ | none — receive and speak only | the other eight | n/a |

  Prosody is missing on the ◐ path for a concrete reason: Android's recogniser
  owns the microphone while it listens and returns text, never samples, so
  there is no audio left to measure. Those frames carry neutral levels rather
  than invented ones. Closing this gap is the multilingual model in iTantra's
  README §3.1, not more code here.
- **Turn-by-turn, like a walkie-talkie** — one to two seconds per utterance,
  not a continuous call.
- The tunnel hostname changes on every restart and is compiled into the app, so
  both config files and the APK must be refreshed together (`./run-sutra.sh
  --build` does this).

---

## 8. Weak links: what we tested, what we found, what we cannot claim

The project's headline is "it works where a call cannot". That is a claim about
bad networks, so it needs testing on bad networks rather than argument.

### The scale, stated precisely

| | |
|---|---|
| Sutra payload, per utterance | **40–60 bytes**, measured at **67–129 bps** of speech |
| A voice codec carrying the same speech | ~24 000 bps, continuously |
| **Ratio of data that must move** | **≈ 200–270× less** |
| Agora RTC session, idle, measured on device | **4–9 kbps tx/rx** |

Both rows matter, and quoting only the first would be dishonest. **Sutra
reduces the data per sentence by more than two orders of magnitude, but today
it rides on an Agora RTC session, and that session has a floor of a few kbps
on its own.** So the end-to-end requirement right now is "a link that can hold
an RTC session", not 89 bps.

### What we tested and what happened

**Frame loss (valid, passed).** The app can drop a percentage of outgoing
frames before they reach the SDK. At **10% and 25% loss, 6 of 6 utterances
still arrived**, recovered by acknowledgement and retry. Retries back off and
keep trying for three minutes, because a 40-byte frame costs nothing to repeat
and abandoning a distress message because a ridge was in the way is the wrong
trade. `tools/weak_link_test.sh` drives this.

**Bandwidth throttling (invalid, discarded).** The Android emulator can be set
to GSM/GPRS/EDGE/UMTS tiers, and a first run produced a neat table showing
EDGE working and GPRS failing. **That table was wrong and has been thrown
away.** The emulator's throttle only shapes the *cellular* path, and this
emulator has no data route over cellular at all — with Wi-Fi off, `ping 8.8.8.8`
loses 100% of packets. The "successful" tiers were Wi-Fi traffic ignoring the
throttle, and the "failed" tiers were the session dying when Wi-Fi went away.
A tier table would have been a fabricated result.

**Losing the network entirely (found a real bug, fixed).** While chasing the
above, the field phone ended up with no connectivity for a minute. When the
network came back, **the link never recovered**: frames kept being handed to a
dead session, the SDK accepted them, nothing was ever acknowledged, and the
screen still said the link was up. For a phone that loses coverage behind every
ridge, that is the most important failure there is. Now the link reports
`CONNECTING / RECONNECTING / CONNECTED / LOST`, rejoins with a fresh token when
Agora gives up, and resends anything unacknowledged. Verified on device:
transitions appear in the log across an interruption and queued frames end
**delivered ✓**.

### What we still cannot claim

- **We have not run Sutra on a real 2G or weak radio link.** No throttled
  measurement in this repo is trustworthy, for the reason above.
- Therefore **"works in hilly areas" is a design argument, not a measurement**:
  the data per sentence is tiny and delivery survives loss and reconnects, but
  the transport underneath still wants an RTC session.

### Where testing stopped

Run on a physical OnePlus and a Pixel 9 emulator, in this order, with every
result above reproduced from logs rather than observation:

- the full loop with real speech, both directions;
- frame loss at 10% and 25%, 6 of 6 delivered each time;
- an emulator bandwidth sweep, **discarded as invalid** (see above);
- network interruption and recovery;
- speech output, which turned out to be an audio-focus problem, not a voice one;
- per-language routing, confirming this handset hears Hindi via the bundled
  model and English via the platform, and says so on screen.

Five bugs came out of it, all fixed and all with tests: the agent silenced by
an erased session, translations discarded on a failed speak, the agent silenced
again by a 429, the link never recovering after an outage, and speech muted by
audio hardening. Device testing is closed at that point.

### How to make it a measurement

1. **A 2G-only SIM on a real phone** — the honest test, and the cheapest.
2. **Host-level shaping** (clumsy on Windows, `tc netem` on Linux) against the
   emulator's traffic, which throttles regardless of interface.
3. **A lighter transport.** Frames already go through iTantra's `Transport`
   interface, which has Bluetooth and loopback implementations, so the same
   40 bytes can ride SMS or a satellite text channel where no RTC session can
   live. This is the honest path to "works where nothing else does" — and it is
   not built yet.

---

## 9. Running it

```bash
cd sutra
./run-sutra.sh --build      # backend + tunnel + config + APK
```

Then install on two devices, type the **same channel name** on both, and pick
**field phone** on the weak-link device and **control room** on the other.

Requirements: `server/.env.local` needs `AGORA_APP_ID`,
`AGORA_APP_CERTIFICATE` and `GROQ_API_KEY`. The agent's tools are called by
Agora's cloud, so the backend must be publicly reachable — that is what the
tunnel is for.
