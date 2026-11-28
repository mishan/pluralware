# Working on the phone redesign

This is the brief for a contributor, or a contributor's coding agent, taking on the phone app's
redesign: one home screen for everyone, settings in sections, and per-friend sharing. It says
what to build, in what order, how to work, and how to hand work back. Read it with:

- [docs/phone-ui-design.md](phone-ui-design.md): Home, followed systems' history, Settings, and
  the slices.
- [docs/notifications-design.md §16](notifications-design.md#16-per-friend-sharing): per-friend
  sharing, and the rules that keep older watches and relays safe.
- [CLAUDE.md](../CLAUDE.md): the architecture, commands and conventions. It's written for coding
  agents and applies here in full.

The designs are agreed. If something in them turns out wrong or impossible once you're in the
code, stop and say so (see "Asking"), rather than working around it.

## The work, in order

Each slice is one branch and one pull request, and must be shippable and revertible on its own.
Do them in this order, and don't start the next until the last has been handed back and
accepted.

1. **One home** (phone-ui §1-3, slice 1). The single nav graph, still keyed on the token. The
   Your system card: connected, expanded prompt, collapsed. Systems you follow, one card each.
   The Follow screen. `followed/{instance}` with the kept history. Per-system notification
   links. Following's own screen is removed.
2. **Settings in sections** (phone-ui §4, slice 2). The five sections. ntfy, relay and friends on
   their own screens. Friend detail with rename and remove. Today's global **Who may be named**
   stays in Sharing until slice 3.
3. **Per-friend sharing** (§16), in three branches:
   - **3a**, no behavior change: the relay accepts `labels` and per-friend lists and reports its
     version in `/status`. PluralWare's Following and the browser follow page keep the invite's
     `i` and echo it in every follow code.
   - **3b**: the watch applies per-friend lists, falling back to the global list, and gets the
     compact copy (§16.5).
   - **3c**: the phone's model, migration, announce rule, pending friends, the add and paste
     flows, and the upload with `labels`.

## How to work, for each slice

1. **Read first:** the design sections for the slice, the code they name, and the tests around
   that code. Write down the plan in a few lines: files you'll touch, the tests you'll add.
2. **Tests first:** write the tests the design lists for the slice (§16.6 for slice 3; for UI
   slices, a test per new screen's `...Content` composable and per state). Run each one and watch
   it fail for the reason it's meant to catch. A test that has never failed proves nothing.
3. **Implement** until they pass. Keep the diff to what the slice needs.
4. **Run the gate:** CI's command, with `--rerun-tasks` because incremental builds have
   false-passed here before:

   ```sh
   ./gradlew --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleRelease
   ```

   For changes under `relay/`: `cd relay && npm test`. For `web-receiver/`:
   `node --test 'web-receiver/test/*.test.mjs'`. Then `./gradlew --stop`; the daemons hold a lot
   of memory.
5. **Check it on real screens:** run the app in an emulator, or render the screens through the UI
   tests. Look at both light and dark themes, a long member list, a long system name, no members,
   and no token.
6. **Trim:** reread the whole diff. Delete comments that restate the code, merge near-duplicate
   tests, and check every comment and doc you touched is still true.
7. **Get a second review:** a fresh session or reviewer that hasn't seen your reasoning reads the
   diff against the design. Fix what it finds that's real.
8. **Hand it back** (below), with a short summary: what changed, the tests added and that each
   was seen failing, what you checked by hand, and anything you're unsure of.

### Rules that apply everywhere

- **Tests:** add one only when it checks real behavior: no tests that just restate a constant,
  and no testing a mock in place of the real thing. Group near-identical cases into one
  table-driven test. Never skip, delete or weaken a failing test to get green; say it's failing.
- **Formats that exist twice** must change together, with their shared tests: invites and follow
  codes (`Invites.kt` and `web-receiver/formats.js`), announcement wording (`SwitchAnnouncement`
  and `relay/src/announce.js`), and the relay upload (`RelayTest` and
  `relay/test/config-interop.test.mjs`).
- **Older peers:** a watch, relay or follow page from before your change will meet data from
  after it, and the other way round. §16 says what each must do. Follow it, and test the old
  side's view, not just the new side's.
- **Screenshots:** the README's screenshots are rendered from the real screens. If your change
  shows in them, CI's `screenshots` job fails and uploads the new pictures as an artifact. Say so
  when you hand back; the maintainer will commit them.
- **Comments** explain why, never what the code does, how it changed, or that a review asked for
  it. That history goes in the commit message.
- **Writing:** US English everywhere, in code and prose (`color`, `behavior`, `canceled`). Use
  `-` or `--`, not em or en dashes.
- **No people's names** in anything committed: code, comments, commit messages, docs. Say "a
  tester" or describe the report.
- **No attribution or markers:** no `Co-Authored-By` trailers, no "Generated with" lines, no
  tool or AI signatures, no hidden characters, in commits or files.
- **Don't change** `applicationId` (`org.foggyden.pluralware`, shared by both apps on purpose),
  the version in `gradle.properties`, release workflows, or signing. Releases are the
  maintainer's.

### Commits

- A short imperative subject, capitalized, then a body saying why: what was wrong or missing, and
  what changes for the user. Look at `git log` for the style.
- Commit as yourself; commits don't need to be signed. The maintainer amends them (signing and
  any rewording) before opening the pull request.
- Several commits per slice are fine where each is a coherent step.

## Handing work back

- **Start each slice** on a new branch from `redesign-design`, the branch that carries these docs,
  updated to the latest `main` first: `redesign-1-home`, `redesign-2-settings`,
  `redesign-3a-receivers`, and so on. Once the docs reach `main`, branch from `main` instead.
- **Don't open pull requests or push to this repository.** Push the branch to your own fork, or,
  without a fork, send `git format-patch origin/main..` output. The maintainer amends, opens the
  pull request, and runs CI and the automated review.
- **Review feedback** comes back as comments on that pull request, relayed to you. Answer each with
  the smallest change that fixes it, as a new commit on the same branch, and say why where a
  suggestion is wrong.

## Asking

Stop and ask the maintainer, rather than choosing, when:

- the design is silent or self-contradictory on something a user would notice;
- doing it as designed would leak a member's name to a friend who shouldn't see it, or break an
  older watch, relay or follow page;
- a slice turns out not to be shippable alone;
- you'd need to change a format, a dependency, or anything under "Don't change".

Small choices the design leaves open (spacing, a label's exact wording, which composable to
reuse) are yours. Follow the patterns already in the app.
