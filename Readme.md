# Tsehay Assist

**A voice-first Android app that lets visually impaired users in Ethiopia call and text their contacts completely hands-free, in Amharic or English.**

Built by [Natnael Beshane](https://github.com/Natnael-arch) · Addis Ababa, Ethiopia

---

## The problem

Touchscreens weren't designed with visually impaired users in mind. Small icons, unlabeled buttons, and text-heavy interfaces make something as basic as calling a family member a real daily obstacle, especially for older or blind users in Ethiopia, where most existing accessibility tools are built for English and don't handle Amharic well, whether that's spoken input, contact names, or dual-script (Amharic/Latin) name matching.

## What Tsehay Assist does

You speak. It listens, understands, and acts.

- **"ደዊትን ደውልልኝ" ("Call Dawit")** → Tsehay Assist finds the right contact,whether they're saved as `ዳዊት`, `Dawit`, or any close variant, reads the name back to you, and waits for you to physically confirm before it actually places the call.
- **Send a text** the same way, say who and what, Tsehay Assist reads it back for confirmation, then sends it.
- **Fully bilingual** - speak and receive responses in Amharic or English, whichever the user is comfortable with.

The whole interaction is voice in, voice out. No menus to hunt through, no small text to read.

## Who it's for

Visually impaired and low-vision users in Ethiopia who own an Android phone and want to stay connected with the people in their life without depending on someone else to place a call or send a message for them.

## How it works

Tsehay Assist is two parts working together:

1. **The Android app** ([this repo](.)) - captures voice, plays back responses, and holds the safety-critical logic: matching a spoken name against the phone's real contact list (handling Amharic/Latin script differences and fuzzy pronunciation), and a **mandatory touch-confirmation gate** before any call or message is actually sent. The AI can suggest an action, but only a deliberate physical touch from the user executes it, nothing happens by voice alone.
2. **A relay server** ([Tsehaye_Assist_Relay_Server](https://github.com/Natnael-arch/Tsehaye_Assist_Relay_Server))- a lightweight Node.js WebSocket proxy that securely bridges the app to Google's Gemini Live API (real-time voice AI), keeping the API key off the device and handling the live audio streaming protocol.

```
User's voice
    ↓
Android app (recording, UI, confirmation gate)
    ↓ WebSocket
Relay server (secure proxy, holds system prompt + tool definitions)
    ↓ WebSocket
Gemini Live (understands speech, decides what action to take)
    ↓ tool call
Android app (matches contact, waits for physical confirmation)
    ↓
Call placed / text sent — only after user confirms
```

## Why the confirmation gate matters

This is the core design decision behind Tsehay Assist: **voice AI proposes, the user disposes.** A visually impaired user relying entirely on an AI's interpretation of their speech needs a safety net, accidentally calling the wrong person, or worse, sending an unintended message, is a real risk with voice-only control. Every irreversible action (calling, texting) requires the user to physically touch the screen to confirm, after hearing the AI read back exactly what it's about to do. Voice gets you 90% of the way there; a deliberate touch closes the loop safely.

## Tech stack

- **Android**: Kotlin, native Android APIs (`ContactsContract`, `TelephonyManager`, Accessibility Services)
- **Voice AI**: Google Gemini Live API (real-time bidirectional audio + function calling)
- **Relay server**: Node.js, Express, `ws` (WebSocket), deployed on Railway
- **Contact matching**: custom Levenshtein-based fuzzy matcher with Amharic-script cleanup, tuned for dual-script (Amharic/Latin) name resolution
- **Testing**: JUnit-based unit test suite for the contact resolution logic, runs on the JVM in seconds, no emulator or build required

## Current status

Tsehay Assist is an actively developed working prototype. Core functionality,voice-triggered calling and texting, dual-script contact matching, the touch confirmation gate, and reconnect-resilient relay infrastructure, is built and testable end-to-end. Ongoing work includes:

- Audio narration of call state (ringing, connected, call ended)
- Broader real-world testing and tuning against Ethiopian names and accents
- Observability and crash reporting ahead of a wider beta
- Per-tester API access controls

## Where this is going

Calling and texting are the starting point, not the destination. Tsehay Assist exists to prove that voice-first, Amharic-aware AI can give visually impaired users independent control over a smartphone and once that foundation works, the same architecture opens the door to far more: navigating apps by voice, reading out messages, notifications, and documents, describing surroundings through the camera, helping with everyday tasks like online payments or reading labels, and more. The long-term goal is for Tsehay Assist to grow into a full voice-driven layer over the phone itself, not just a contacts tool,built specifically around Ethiopian languages and the real needs of visually impaired users here, rather than adapting a foreign product after the fact.

## Project structure

```
Tsehaye-Assist/
├── app/src/main/java/com/example/voicelauncher/
│   ├── MainActivity.kt          # UI, orchestration, touch confirmation handling
│   ├── IntentDispatcher.kt      # Decides what each AI tool call actually does
│   ├── ContactResolver.kt       # Dual-script name resolution logic
│   ├── ContactMatcher.kt        # Fuzzy name-matching scoring
│   ├── ContactProvider.kt       # Reads the phone's real contact list
│   ├── GeminiRelayClient.kt     # WebSocket client to the relay server
│   ├── VoiceRecorder.kt         # Mic capture
│   ├── PcmPlayer.kt             # Plays AI voice responses
│   └── AccessibilityAudioService.kt
└── app/src/test/java/com/example/voicelauncher/
    └── ContactResolverTest.kt   # Fast JVM unit tests for name matching
```

## Getting started

1. Clone this repo and open it in Android Studio.
2. Set up and run the [relay server](https://github.com/Natnael-arch/Tsehaye_Assist_Relay_Server) (see that repo's setup instructions).
3. Point the app at your running relay server's WebSocket URL.
4. Build and run on a device — grant contacts, phone, and microphone permissions when prompted.
5. Hold the mic button, speak a request, and Tsehay Assist takes it from there.

## Related repo

- [Tsehaye_Assist_Relay_Server](https://github.com/Natnael-arch/Tsehaye_Assist_Relay_Server) - the WebSocket relay that connects this app to Gemini Live.

---

*Tsehay Assist is being built with real Ethiopian users in mind, this README and the project itself are evolving alongside it.*