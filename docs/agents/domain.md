# Domain docs

Layout: **single-context** (one context for the whole repo).

- Root `GLOSSARY.md`: definitions of domain terms, used as the source of truth for terminology.
- `docs/adr/`: Architecture Decision Records. Number sequentially (e.g., `0001-<slug>.md`) or by date; include context, decision, consequences.

## Consumer rules

When skills need domain context (e.g., `domain-modeling`, `grill-with-docs`, `to-spec`):
1. Read `GLOSSARY.md` at repo root if it exists; otherwise treat as empty.
2. Read relevant ADRs from `docs/adr/` (scan by title/keywords).
3. Prefer existing terminology from the glossary; add new terms there when they become canonical.
4. When making decisions that affect architecture, consider creating/updating an ADR in `docs/adr/`.
