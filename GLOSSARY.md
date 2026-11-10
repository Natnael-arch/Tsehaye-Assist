# Glossary

## Core Concepts

- **Tsehay Assist**: Voice-first Android app that lets visually impaired people make calls and send texts without needing a screen or another person’s help. Amharic first, with English first-class parity.
- **Hold-to-speak / Large touch zone**: User holds a large touch zone anywhere on screen to speak a command (e.g., "Call Dave", "Text Dave saying I'm on my way").
- **Contact disambiguation**: When multiple contacts share a name, the app reads out candidates so the user can choose explicitly from the spoken list (MVP requires explicit selection).
- **Spoken confirmation gate**: Before any action (dial/send), the app asks for a spoken yes/no. Nothing is dialed or sent without confirmation. Non-negotiable for safety.
- **Haptic feedback**: Distinct vibration patterns signal confirm, reject, and awaiting input; user never has to look at screen.
- **Call pause/resume**: App pauses itself during incoming calls and resumes afterward.

## Architecture

- **Android app (Kotlin)**: Captures mic audio, plays Gemini’s replies, handles contacts/telephony APIs, enforces confirmation defensively.
- **Node.js relay server**: Sits between app and Gemini Live API. Handles session setup, passes contact names to Gemini, relays tool calls (contact search, call, SMS, confirm/cancel). Enforces confirmation as source of truth.
- **Gemini Live API**: Real-time speech understanding; decides which tool to call based on context and contact names.
- **Tool calls**: Actions Gemini can request (contact_search, call, sms, confirm, cancel). Execution gated by confirmation.
- **Local model path**: Long-term plan to move voice/data processing to local models; current path passes through own servers and external model.

## Pilot tenancy

- **Tester**: A person explicitly admitted to the pilot, whose phone the host has bound to that name. The unit of identity, of usage budget, of being paused, and of consent.
- **Pairing**: The binding of one phone to one named Tester — requested by the phone, granted by the host. A phone is not a Tester until it is paired.
- **Pending**: A phone that has asked to be paired but has not been granted. It is not a Tester and has no one to blame for a poor experience; the host decides what it may do meanwhile.
- **Busy**: A Tester is admitted, but the service declines to serve them right now — and must say so out loud. Not an error, not a fault on their phone.
- **Pause**: The host's reversible act of refusing a Tester's requests, used to save a pilot that one heavy user would otherwise exhaust.
- **Revoke**: The host's act of unbinding a phone from a Tester, after which the phone is Pending again.
- **Silent failure**: The pilot's unit of harm. A moment where the phone knew something was wrong — or something had happened — and told the user nothing. A loud failure is a bug; a silent one ends the pilot.
- **Handover**: The host-performed act of preparing a Tester's phone — pairing, approval, permissions, clips, first working call — after which the Tester never needs sighted help.
- **Host**: The single person who approves, pauses, revokes and diagnoses. During the pilot, this is also the only support channel, and their sleep is a constraint on the design.

