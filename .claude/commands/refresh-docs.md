---
description: Refresh docs/ and .github/copilot-instructions.md to reflect current code (via the doc-sync skill)
argument-hint: "[optional scope, e.g. 'testing' or a path]"
---

Use the **`doc-sync`** skill to refresh this repository's documentation so it reflects the current code.

Intent to preserve (from the skill): the docs let an AI on another project (similar stack — Spring Boot
4 · Temporal · MongoDB · Testcontainers · virtual threads) learn how each block was implemented and how
to reuse it. Keep the navigable `root → section → page` structure, the breadcrumbs, the
`path/File.java → Class.method()` references, and the per-page template.

Steps:
1. Determine what changed since the docs were last updated — run `git diff --stat` (and inspect recent
   commits / the working tree). If the user passed a scope in `$ARGUMENTS`, focus there but still fix any
   obviously stale references you encounter.
2. Map changed source to the affected pages using the tables in `docs/README.md` and each section
   `README.md`, then update those pages **in place** per the template. Add new pages/sections for any new
   major block, and update **both** the section index and the hub nav tables.
3. Keep `.github/copilot-instructions.md` true (its §-references, repo map, commands, pitfalls).
4. Verify before finishing: every cited file/class/method exists (grep them), every internal link
   resolves, every code fence is balanced, breadcrumbs are on line 1, and no stale ports/tags/versions/
   env-var names/`make` targets remain.
5. Report which pages were added or updated and why. Do **not** modify application code — documentation
   only.
