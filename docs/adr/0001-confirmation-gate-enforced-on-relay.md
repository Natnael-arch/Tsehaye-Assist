# ADR-0001: Confirmation gate is enforced on relay (source of truth)

## Status
Accepted

## Context
For visually impaired users, accidental calls/texts are high-risk. The app requires spoken yes/no before dialing/sending. Commands flow through Gemini Live via the Node relay.

## Decision
- The Node.js relay server must enforce confirmation as the **source of truth**: it will not forward/allow `call` or `sms` tool execution unless a valid spoken `yes` tied to the pending action has been received.
- The Android app must also enforce confirmation defensively (defense in depth).
- Both Amharic and English core flows must respect this gate.

## Consequences
- Prevents accidental actions even if Gemini misinterprets or attempts to call tools prematurely.
- Adds state tracking on relay for pending actions and confirmation state.
- Slightly increases complexity but critical for safety.
