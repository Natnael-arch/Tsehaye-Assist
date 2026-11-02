# ADR-0003: Android is source of truth for contacts; relay provides context

## Status
Accepted

## Context
Contacts live on the user's device. Resolution/disambiguation must work offline in terms of device data; also need to respect privacy.

## Decision
- Android app is the source of truth for contacts and performs contact lookup/resolution.
- Relay passes contact names to Gemini for context/disambiguation prompting, but does not own the contact database.
- Android only executes call/SMS after confirmation.

## Consequences
- Reduces server exposure to full contact lists if desired; Android controls access.
- Disambiguation logic can leverage Android's contact data.
- Clear separation of concerns.
