# ADR-0004: Hold tool execution until confirmation; keep protocol stable for local model migration

## Status
Accepted

## Context
Need to enforce safety and maintain a clear seam for moving to local models.

## Decision
- When Gemini requests `call`/`sms`, relay holds execution until confirmation comes in; relay tracks pending action and requires spoken yes tied to it before allowing execution.
- Keep the same protocol between app and relay to make swapping backends (Gemini Live → local model) easier.

## Consequences
- Relay must track pending action state keyed to session/turn.
- Protocol stability enables future migration without rewriting app/relay contract.
- Defense in depth with Android enforcement.
