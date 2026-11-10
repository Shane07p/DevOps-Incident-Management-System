# Lessons

Mistakes we have made, and the rule each one taught. Read this before opening a pull request.

How to add one: append an entry at the end, dated, at most three lines, naming the pull requests it
came from. Never rewrite or delete an entry; if a rule changes, add a newer entry that says so. When
the same lesson comes back a second time, it becomes a rule in the matching `AGENTS.md`.

## 2026-11-07 · Editor encoding breaks characters
Seen in: #216, #217. Files saved in another encoding turned `→` into `â†’` and `·` into `Â·`, and added a byte-order mark.
Rule: save as UTF-8 without BOM; before pushing, look at `git diff` for odd characters such as `Â`, `â€`, or `ΓÇ`.

## 2026-11-07 · Reformatting whole files
Seen in: #205, #216. Rewrapping unchanged lines hid the real change and caused conflicts with every other PR on the page.
Rule: change only the lines your feature needs; leave the formatting of the rest alone.

## 2026-11-07 · Guessing an API that does not exist yet
Seen in: #210 against #209, #211, #212, #216. Frontends built on guessed responses broke or disagreed with each other.
Rule: before building on an unbuilt API, post the exact request and response on its backend issue and get the owner's OK; keep the PR a draft until that API merges.

## 2026-11-07 · Copying another PR's file
Seen in: #216 copied `api/logs.ts` from #211. Two PRs adding one file conflict, and the copy carried #211's open review points.
Rule: one client per API. Import it once its PR merges, or agree with its owner who adds it.

## 2026-11-07 · Generated test data passed off as recorded
Seen in: #215. Four "recordings" came from a random generator, so the tests they fed could not catch a wrong answer.
Rule: fixtures that claim to be recorded come from the real system; anything generated says so in its metadata.

## 2026-11-07 · A test that passes on the old code proves nothing
Seen in: #218, where the first history-search test passed before the fix.
Rule: run a new regression test against the code before the fix and see it fail; if it is timing-dependent, make it deterministic.

## 2026-11-07 · Effects that write the URL must depend on it
Seen in: #218 (history search) and #211 (logs). A debounce timer kept an old `setFilters` and dropped filters chosen while it waited.
Rule: list every value an effect reads in its dependencies; never silence `react-hooks/exhaustive-deps`.

## 2026-11-07 · Counters change in SQL, not through a loaded entity
Seen in: #218 (Codex R03). Saving a loaded user wrote an old token version over a logout committed meanwhile.
Rule: increment counters with one `UPDATE … SET x = x + 1`; a loaded copy can be stale by the time it is saved.

## 2026-11-08 · Two PRs, one migration number
Seen in: #219 and #220 both used V17; merged together, Flyway would refuse to start.
Rule: just before merging, check the highest `V` on `main`. A migration not yet on `main` may be renamed or edited; one on `main` never is.

## 2026-11-08 · Secrets only where they are needed
Seen in: #219. The raw personal access token sat in the security context for the whole request.
Rule: once a credential is checked, carry its id, not its value.

## 2026-11-09 · Hand-written data again, now a rule
Seen in: #224, after #215. Metric and deploy evidence came from values typed into the code, not the recordings merged in #215.
Rule: promoted to `ai-service/AGENTS.md` ("Evidence comes from the recordings").
