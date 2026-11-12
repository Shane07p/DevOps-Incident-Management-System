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

## 2026-11-11 · History keeps the link it had when it happened
Seen in: #233 (Codex). Uptime joined check results to their monitor's current service, so moving a monitor moved its past checks too.
Rule: a record that must stay true later stores what it belonged to at the time (here `service_id` on each check), not only a link to something that can change.

## 2026-11-11 · A state's start time is its first entry, not its latest
Seen in: #233 (Codex). A second RESOLVED update moved `resolvedAt` forward and could bring an old incident back into the 90-day list.
Rule: take a transition time from the first event that entered the state, and load enough history to see it.

## 2026-11-13 · Network calls inside a database transaction
Seen in: #257 and #258. The AI request (up to 20 s) and the telemetry calls ran inside `@Transactional`, so a few slow investigations could take every database connection.
Rule: read what you need in a short transaction, call the network with none open, then save in another short one (`IncidentEmbeddingService`).

## 2026-11-13 · A test that only checks a helper
Seen in: #259. "The response clock stops" was tested by calling `SlaCalculator` with times the test chose, so it passed whatever the endpoint did.
Rule: assert through the API or the database; a helper called with your own values tests the helper, not the feature.

## 2026-11-13 · Endpoints without an id still go into OrgIsolationTest
Seen in: #261 (Codex). The org settings endpoints had no id in the path, so they were left out of `OrgIsolationTest`.
Rule: every new endpoint is there; one without an id gets a test that org A's change leaves org B's data as it was.

## 2026-11-13 · Migration numbers clash again
Seen in: #257 kept V24 after #259 merged it; #84 and #239 held V25 and V26 while #261 needed V25. Second time after 2026-11-08; the rule is already in `backend/AGENTS.md`.
Rule: reserve nothing; whoever merges next takes the next free number on `main` and renames just before merging.

## 2026-11-13 · Protecting a route means its callers too
Seen in: #262. The token covered the fault and traffic routes but not the log routes, and the scenario recorder kept calling without it and saved runs whose fault never started.
Rule: list every route that reads or changes the state, and update and test each caller in the same PR.
