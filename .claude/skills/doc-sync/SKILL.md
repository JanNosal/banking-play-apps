---
name: doc-sync
description: Refresh this repo's documentation (the docs/ tree and .github/copilot-instructions.md) so it reflects the current code. Use when code changed and the docs may be stale, when asked to update/regenerate/sync documentation, or after adding a feature. Preserves the navigable root→section→page structure, breadcrumbs, source references, and the "explain how it's implemented + how to reuse it" intent so another AI (e.g. Copilot) can learn from the repo.
---

# doc-sync — keep the documentation true to the code

## Purpose & intent (do not lose this)

The docs exist so that **an AI assistant in another project, on a similar stack (Spring Boot 4 ·
Temporal · MongoDB · Testcontainers · virtual threads), can read this repo and learn how to implement a
similar feature.** Every page must: explain **how it's implemented here** (with real
`path/File.java → Class.method()` references), say **why**, and give **how to reuse it elsewhere**. Keep
pages **discoverable** (clear titles, "Read this when") and **navigable** (breadcrumbs to upper levels).

## Documentation layout (the contract to preserve)

```
docs/
  README.md                       # root hub (level 0): nav tables to every section/page
  01-architecture/README.md       # section index (level 1)
  01-architecture/*.md            # pages (level 2): data-model, services-and-apis,
                                  #   temporal-workflows, persistence-and-mongodb,
                                  #   concurrency-and-virtual-threads, data-seeding,
                                  #   configuration, observability
  02-testing/README.md            # section index
  02-testing/*.md                 # workflow-logic-tests, in-jvm-e2e-tests,
                                  #   dockerised-e2e-tests, reliability-and-temporal-testing
  03-build-and-run/README.md      # section index
  03-build-and-run/*.md           # local-development, docker-and-compose, troubleshooting
.github/copilot-instructions.md   # imperative "how to AUTHOR e2e tests" guide (repo-grounded)
```

Numbered section folders order the sections. Add new sections as `NN-<kebab-name>/` with a `README.md`.

## Per-page template (every level-2 page MUST follow it)

1. **Line 1 — breadcrumb** to upper levels:
   `[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › [Section](README.md) › **Page Title**`
   (root hub uses `[🏠 Repo](../README.md) › **📖 Documentation**`; section index uses
   `[🏠 Repo](../../README.md) › [📖 Docs](../README.md) › **Section**`).
2. `# Title`
3. `> **Read this when** …` — one line so an AI can match a question to this page.
4. `## In one line` — the essence.
5. `## How it's implemented here` — cite `path/File.java → Class.method()`; short, real excerpts only.
6. `## Why / key decisions` — the rationale and trade-offs.
7. `## Reuse in your own project (similar stack)` — numbered, generalized steps.
8. `## See also` — links to related pages / source / `.github/copilot-instructions.md`.

## Procedure (follow in order)

1. **Detect what changed.** Prefer `git diff --stat` against the last doc update (or review recent
   commits / the working tree). Map changed source areas to the doc pages that cover them using the
   tables in `docs/README.md` and each section `README.md`.
2. **Update affected pages in place.** Re-read the changed source files and correct every reference,
   excerpt, signature, path, port, env var, and number. Do **not** rewrite untouched pages.
3. **Add pages for new topics.** If a new major block appeared (a new app, a new workflow, a new
   integration, a new test layer), create a new level-2 page from the template and a new section if it
   doesn't fit an existing one.
4. **Update navigation.** Add the new/renamed page to its section `README.md` table **and** the
   `docs/README.md` hub table. Keep both in sync.
5. **Keep `.github/copilot-instructions.md` true.** If test structure, pitfalls, file names, or commands
   changed, update its §-references and the repo map (§A). It is the imperative companion to docs/.
6. **Verify integrity (do not skip):**
   - Every cited `path/File.java`, class, and method **exists** — grep for each identifier you cite.
   - Every relative markdown link resolves (target file exists).
   - Every code fence is balanced (even count of ```` ``` ````), breadcrumbs present on line 1.
   - No stale facts: ports, image tags, versions, env-var names, `make` targets, default values.
7. **Report** a short summary of which pages were added/updated and why. If you changed source-affecting
   facts, say which.

## Guardrails

- **Never invent identifiers.** If unsure a method/field exists, open the file and check; cite only what
  you verified.
- **Don't duplicate large code** into docs — link to the source and show only the key lines.
- **Preserve the breadcrumb + template** on every page so the tree stays predictable.
- Make **surgical edits**; keep diffs reviewable. Don't reflow whole files for a one-line change.
- This skill **only edits documentation** (`docs/`, `.github/copilot-instructions.md`, and doc pointers
  in `README.md`). It does not change application code.

## Done check

- [ ] Changed code is reflected on the right page(s), with verified references.
- [ ] New major blocks have a page + nav entries in both indexes.
- [ ] All internal links resolve; all fences balanced; breadcrumbs intact.
- [ ] `.github/copilot-instructions.md` still matches reality.
- [ ] Summary of changes reported.
