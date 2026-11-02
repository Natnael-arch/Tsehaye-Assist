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
