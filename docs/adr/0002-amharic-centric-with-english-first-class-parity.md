# ADR-0002: Amharic-centric with English first-class parity

## Status
Accepted

## Context
Mainstream assistants (Google Assistant, Siri) don't support Amharic well. Target users speak Amharic first. The product needs to be usable in Amharic-centric contexts.

## Decision
- Design and optimize for Amharic first (prompting, TTS/STT expectations, disambiguation language).
- English must have first-class parity for core flows (calls, texts, confirmation).
- UI/haptics remain screen-free; language choice/handling must not rely on visual elements.

## Consequences
- Prompts/contact presentation and confirmation phrasing must work well in both languages.
- Testing must cover Amharic flows explicitly.
- May require careful prompt engineering for Gemini Live in Amharic.
