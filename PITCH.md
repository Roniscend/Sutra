# Sutra — the pitch

## One line

**Voice calls die before data does. Sutra sends the meaning instead of the
audio — about 40 bytes a sentence — so people can still talk when the network
can no longer carry a call.**

---

## 1. The problem nobody has solved

A phone call needs a steady 16–48 kbps. When a network degrades, that is the
first thing to go: the bars are still showing, a trickle of data still moves,
and the call will not hold.

That is not an edge case in India. It is a flooded village, a landslide in the
hills, a fishing boat 20 km offshore, a border post above the tree line, a
crowd of half a million at a mela with every tower saturated. It is also the
satellite service India launched for exactly these places — **which carries
SOS and text, not voice**.

So the people who most need to be heard are the ones whose voices cannot get
through. Everyone else's answer is to type. Typing is the wrong answer for
someone who is frightened, whose hands are full, who is holding a child on a
roof, or who cannot read the script their phone offers.

## 2. What we did about it

Stop sending audio. **Send what was said, and how it was said, and rebuild the
speech on the other side.**

The phone recognises the speech locally, measures the delivery — pitch,
loudness, pauses, urgency — compresses both, and sends a frame. The listener's
device or an Agora Conversational AI agent turns it back into speech, in the
listener's language.

**Measured on real devices, not estimated:**

| | |
|---|---|
| 3.6 s of spoken Hindi | **40 bytes — 89 bits per second** |
| The same speech as a voice call | ~24 000 bits per second |
| **Data that has to move** | **200–270× less** |
| Indic text vs UTF-8 | 3.53× smaller, across nine scripts, on held-out data |
| Recognition on the phone | 0.5× real time, 0.96 confidence |
| Hindi → English, numbers intact | 179 ms |
| Delivery at 25% frame loss | 6 of 6 messages still arrived |

## 3. Why this is not "a chatbot with a microphone"

The Conversational AI agent **is the control room**. It is the only voice
there, and it does three jobs no chatbot does:

- **It speaks the field's words.** A report arrives as 40 bytes; the agent says
  it aloud in the officer's language. Urgent traffic interrupts whatever it was
  saying — because urgency travelled in the frame.
- **It answers from the field's record, not its memory.** Ask "how many people
  are trapped?" and it calls a tool that returns every message the field
  actually sent, and quotes them. It cannot invent a casualty count.
- **It puts the officer's instruction on the wire.** Speak an order and the
  agent relays it: translated, compressed, sent down a link that could not
  carry your voice.

Take the agent out and there is no control room. That is what "real-time voice
is core to the experience" should mean.

## 4. Why this team deserves the win

**We built on the quickstart as asked, and then went somewhere with it.** RTC,
RTM, tokens and the agent lifecycle are the quickstart's. Everything that makes
Sutra Sutra is ours: a range coder over the parallel block structure of the
nine Brahmic scripts, prosody quantised to six bits a word, a 9-byte frame with
acknowledgement and retry, an audio-free channel, and an agent whose two tools
turn it into a relay desk.

**It stands on a real prior project.** iTantra, our SIH problem statement 26173
work for ISRO, contributes the on-device Hindi recognition measured at 17% WER
and 0.37× real time, and its cross-script phonetic key is the observation this
codec is built on. Sutra compiles those sources in place rather than copying
them — and iTantra keeps its own guarantee of being offline by construction.

**We tested on hardware and published what broke.** Five bugs came out of the
device runs, every one fixed with a test:

1. the agent silenced because a second phone's join erased the running agent;
2. translations thrown away when speaking failed;
3. the agent silenced again by a rate limit we caused ourselves;
4. the link never recovering after the network came back — the failure that
   matters most for a phone behind a ridge;
5. speech muted by Android's audio hardening because we held no audio focus.

Two of those had the same dangerous shape: everything looked healthy and nobody
could hear anything.

**We threw away our own best-looking result.** An emulator sweep produced a
tidy table showing the app working at EDGE and failing at GPRS. It was
measuring nothing — that emulator has no cellular data path at all. The table
is gone; the harness that produced it is still in `tools/weak_link_test.sh`,
carrying a header that says in full why its emulator numbers are worthless and
what it is actually for — the run on a real 2G radio we have not done yet.
**A demo that overclaims is worth less than a smaller one that holds**, and
judges can check every number here against the tests that produced it: 75
Android tests, 20 backend tests, iTantra's 63 untouched.

## 5. Why it should scale

**The economics invert.** A voice minute costs bandwidth per listener. Sutra
costs bytes per sentence, and the speech is rebuilt at the edge. One officer
can broadcast to ten thousand phones, each hearing it in its own language, for
a few kilobytes of server traffic. Adding a listener costs almost nothing —
that is a property calls have never had.

**It rides infrastructure that already exists.** No new hardware, no new
spectrum, no app for the other side to install first. An Agora channel today;
tomorrow the same 40-byte frames fit a satellite text channel or an SMS, because
frames already travel through an interface with Bluetooth and loopback
implementations behind it.

**The market is not one disaster.** The same thin-link conversation is worth
having for maritime fishing fleets, mining and tunnelling crews, border and
forest patrols, trekking and expedition safety, rural health workers reaching a
district doctor, and rail and pipeline maintenance in places built precisely
where coverage is not. Every one of them today chooses between a call that
drops and a text nobody sends.

**Language is the multiplier.** The protocol carries ten Indian languages, and
one frequency table serves nine scripts because of how Unicode lays them out.
The cost of the eleventh is a table, not an architecture.

## 6. Why we think it is the strongest thing here

Most voice AI makes an existing conversation nicer. **Sutra creates a
conversation that could not happen at all.** If the network cannot carry your
voice, no assistant, tutor, companion or copilot on the leaderboard helps you.
This one does, and it does it by treating Conversational AI as **a
communications rail rather than a feature** — the agent is the far end of a
link, not a widget on a screen.

It is also the rare hackathon entry you can verify. Every number above came out
of a test or a log on a real phone, the failures are written down next to the
successes, and the one claim we cannot yet prove is labelled as unproven.

## 7. What is honestly still ahead

- **A run on a genuine 2G radio.** The claim "works where a call cannot" is a
  design argument plus loss and reconnect tests — not yet a bandwidth
  measurement. A 2G-only SIM closes it in an afternoon.
- **A lighter transport.** Today the frames ride an Agora RTC session, which
  has a floor of a few kbps. SMS and satellite text are where 40-byte frames
  truly belong.
- **The multilingual model.** Hindi has the bundled recogniser; the other nine
  need the training run iTantra's plan already budgets.
- **The speaker's own voice**, which needs a cloning vendor we have not wired
  up.

We would rather show you that list than pretend it does not exist.

---

## The 60-second version

> When the network gets bad, calls fail first — and that is exactly when
> somebody needs to say "two children are on the roof".
>
> Sutra stops sending audio. The phone recognises the speech, measures the
> urgency in the voice, and sends about **40 bytes**. On the other end, an
> Agora Conversational AI agent speaks it aloud to the control room in their
> language, answers questions using only what the field actually reported, and
> sends the officer's instructions back down the same thread.
>
> Measured on a real phone: **3.6 seconds of Hindi, 40 bytes, 89 bits per
> second, against 24 000 for a call.** It survived 25% packet loss, six of six
> messages delivered.
>
> It is built on the Agora quickstart and on our ISRO problem-statement work,
> tested on hardware, and it found five real bugs on the way — including one
> where the link never recovered after an outage, which is the failure that
> would have mattered in a valley.
>
> We are not making conversations nicer. We are making conversations possible
> where they currently are not.
