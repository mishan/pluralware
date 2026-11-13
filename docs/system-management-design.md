# System management on the phone

Editing a system from the phone app. This round covers editing a member and fixing switch
history. Adding and deleting members, groups, privacy settings and the system profile come
later, on the same foundations.

## 1. Scope

- **Edit a member:** name, display name, pronouns, color, description, avatar URL.
- **Fix a switch** in the history the phone shows (the last 10): change when it started, change
  its members, or delete it.
- Corrections reach everything that shows them: the phone's screens, the watch, its complication
  and tile, the relay, and friends, when the current front changed.

Out of scope: creating or deleting members, groups, privacy, the system profile, proxy tags,
avatar uploads (PluralKit takes a URL), banners, offline edit queues, editing on the watch, and
history older than the last 10 switches.

## 2. What PluralKit does (checked against its source)

- `PATCH /members/{uuid}` takes a partial member. A **missing key is left alone**; a key set to
  `null` or `""` **clears** the field. `name` can't be cleared. `color` must be six hex digits,
  no `#`. Limits: name, display name and pronouns 100 characters, description 1000, avatar URL
  256 and a valid URL.
- `PATCH /systems/@me/switches/{uuid}` with `{"timestamp": ...}` moves a switch. PluralKit only
  rejects a time any switch already has (40005), **including the switch being moved**. It does
  not check neighbors or the future, so the app enforces both (section 5).
- `PATCH /systems/@me/switches/{uuid}/members` with a list of member uuids. Rejects duplicates
  (40003) and a list equal to that switch's current one (40004).
- `DELETE /systems/@me/switches/{uuid}` returns 204; an unknown switch is 404 (20007/20008).
- Errors carry `code`, `message` and, for validation failures (40001), `errors`: field name to
  a list of messages.

## 3. Data model

- `Member` gains `description: String?`. That's the only widening; the edit screen needs it.
- `MemberDraft`: what the form holds, every field as typed. `MemberDraft.of(member)` fills it.
- `MemberEdit` (pure, in `:shared`), the one place an edit turns into a request:
  - `normalize`: trim every field; blank becomes "cleared" (except name); color loses a leading
    `#` and is lowercased.
  - `validate(original, draft)`: field → message, using PluralKit's limits, so most mistakes
    never leave the phone. It checks only the fields that would be sent: a stored value PluralKit
    once accepted mustn't block an edit to another field.
  - `changes(original, draft)`: a map holding **only the fields where `normalize(draft)` differs
    from `normalize(original)`**. Normalizing both sides means an uppercase color or trailing
    space from PluralKit doesn't count as an edit. A cleared field maps to `null`. No other code
    builds a member PATCH body.
- `PluralKitHttpException` gains `code: Int?` and `fieldErrors: Map<String, List<String>>`,
  parsed from the error body, so screens can put PluralKit's message next to the right field.

Sending only changed fields matters twice. A field the form didn't touch can't be clobbered.
And PluralKit has no edit-conflict check, so an edit made in Discord to a different field while
the form was open survives.

## 4. Client and repository

`PluralKitClient` gains four calls, each one request:

```kotlin
suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): Member
suspend fun moveSwitch(uuid: String, to: Instant): Switch
suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): Switch
suspend fun deleteSwitch(uuid: String)
```

- `updateMember` builds its JSON body from `changes`, with `JsonNull` for cleared fields; the
  serializer's defaults would drop or include every key. It also updates the client's own
  history-lookup cache, as `registerSwitch` already does.
- `MockPluralKitClient` implements all four against mutable sample data.

`PluralKitRepository` keeps one rule: **after a write, fetch rather than patch.**

- After a member edit, it drops its member cache and fetches the current fronters again. That
  is one extra request, and no cache can be missed.
- After a switch edit, it fetches the current fronters again. `setFronters` updates both
  `currentFronters` and `loadedFronters`, which the watch sync and the surfaces read.
- **A refresh can't undo an edit.** A fetch that started before a write finished could land
  after it with old data. The repository counts writes; a fronters fetch whose count changed
  while it was in flight is dropped and fetched again. History simply reloads after every write.
- **Screens notice members-only changes.** `FrontersViewModel` refreshes today only when the
  switch's uuid or start time changes. It will also compare the members (uuids, names, colors),
  so a rename, or a members fix on the current switch, redraws the home screen on both devices.
- New hook `onFrontCorrected(Switch?)`, beside `onSwitchRegistered` (section 6).

## 5. Fixing a switch

The phone's History rows open a sheet with three separate actions. Each is one request, so none
can half-succeed.

- **Change time.** A date and time picker limited to **after the switch before it and before the
  switch after it** (or now, for the latest).
  - On the phone, History loads 11 switches and shows 10, so the oldest one shown has a known
    lower bound. The watch's History, which shares the ViewModel, keeps loading 10.
  - Just before sending, the repository fetches recent switches again and rechecks the bounds,
    because a switch made in Discord since the list loaded could now sit inside the window. If
    it does, the sheet says so and shows the new limits. A switch made in the moment between
    that check and the request can still slip through; that's accepted.
  - "Now" is the phone's clock, so a phone whose clock runs fast could set a time slightly in
    the future. Also accepted.
- **Change members.** Opens the picker in an edit mode: seeded with this switch's members in
  order, saving with `setSwitchMembers` (never `registerSwitch`, so no new-switch announcement),
  and doing nothing when the list is unchanged (PluralKit would reject it).
- **Delete.** Always confirms first, and the confirmation says what happens: for the latest
  switch, "Front goes back to Sam, since 2:40 PM"; for an older one, "Jo's front will run on
  until 4:00 PM".

The watch's history stays read-only.

## 6. Friends

When an edit changes who's fronting now, the phone tells friends with the corrected front.

- **What counts, decided from fresh data:** the switch PluralKit returned is the current front
  after the edit (checked against the fronters fetched afterwards), or a delete changed the
  current front. History may be stale (someone may have switched in Discord), so it never
  decides this.
- **Wording:** the message reads as a correction ("Correction: Sam is fronting"), because
  Simple-mode friends (ntfy) keep both notifications. Deleting the only switch sends "Correction:
  switched out" rather than leaving friends with a front that never happened.
- **Time-only edits don't notify:** the members didn't change.
- **Sent even with the relay on.** The announcer's `appSends` check exists so the relay and the
  app don't both send a *new* switch. The relay only handles new switches (`CREATE_SWITCH`), so
  corrections get their own entry point that skips that check.
- **Arriving in order.** Push services don't guarantee order, so a correction could arrive
  before the mistake it corrects. The payload gains `sentAt`, and receivers (the phone's
  Following screen and the web receiver) ignore a message older than the one they're showing.
  Private-mode sends also set Web Push's `Topic` header, so a push service that hasn't
  delivered the mistake yet replaces it. Old receivers ignore the new field.
- **The relay's names.** The relay announces from a snapshot of shared members' names taken at
  upload. A member edit that changes a shared member's name or display name re-uploads the
  relay config.

## 7. The watch

- **Switch fixes.** `SwitchSync.Notice` is a switch's uuid and start time, so a members edit on
  the latest switch wouldn't look like news.
  - The notice gains a hash of the switch's member uuids. `differsFrom`, the push side's
    `distinctUntilChangedBy`, `LastFronter` and `FronterSource.expire` all include it.
  - A notice without the hash (from an older phone) means "don't compare members", so an old
    phone doesn't force a refresh every time.
- **Member edits.** A new `MemberSync` notice on `/pluralware/members`: the token's fingerprint
  and the edit time, no names or other data. It's registered in the watch manifest's listener
  filter. On receipt, the listener service:
  - expires the complication and tile's cached line itself, as it does for switch notices,
    because with the app closed there is no session to do it;
  - delivers the notice to a running session, which drops its member caches and fetches its
    fronters again;
  - drops the member caches of `FronterSurfaces`' own client.

## 8. Phone screens

- **Members**, reached from the home screen: the system's members, searchable like the picker.
  Tapping one opens its editor.
- **Edit member:** a form with the six fields, a color swatch beside the hex field, and the
  avatar shown from its URL.
  - Save is enabled only when something changed and everything is valid.
  - Leaving with unsaved changes asks first.
  - The draft **and the member as it was loaded** survive rotation and process death
    (`rememberSaveable`). Restoring both matters: diffing a restored draft against a freshly
    fetched member would undo any edit made elsewhere in between.
- **History:** rows on the phone become tappable and open the sheet in section 5.

## 9. Failures

| What happens | What the user sees |
|---|---|
| Offline or a timeout | An error with Retry; the draft is kept |
| 401 | The existing error screen with Sign out |
| 429 | "PluralKit is busy, try again in N seconds", from `retry_after` |
| 400, field errors | Each message under its field |
| 40005 (time taken) | "Another switch starts at that time" |
| 404 on a switch | "That switch was already deleted", and History reloads |
| Save tapped twice | One request: Save is disabled while one is in flight |
| The write works, the follow-up fetch fails | The edit is kept; the fetch is retried, and History reloads when it can |

**A request that times out may still have succeeded.** Retrying is safe, but each call needs a
different check that the retry is just repeating a success:

- A member PATCH sets the same values again.
- A repeated members change gets 40004 ("already those members"): treated as success.
- A repeated delete gets 404: treated as success.
- A repeated move gets 40005, because the switch itself already has that time. The app fetches
  the switch, and a matching time means success.

## 10. Tests

| Layer | What | Why |
|---|---|---|
| Unit | `MemberEdit`: normalize, validate and changes, table-driven | The core of "never clobber" |
| Property | Seeded random members and edits, including uppercase hex, `#` and edge whitespace: unchanged fields never appear in `changes`; applying `changes` to the original gives the normalized draft; `changes(m, draftOf(m))` is empty | Hand-picked cases miss odd strings |
| Mutation | Pitest on `MemberEdit` with `tools/mutation.sh`, run by hand (critical-tier layer) | Proves the tests above would catch a quiet change to the diff |
| Client | Exact request bodies and paths against a fake server: absent vs `null`, the three switch calls, error-body parsing | The wire format is where absent vs null goes wrong |
| Repository | Fetch after every write; a stale fetch can't land after a write; `onFrontCorrected` fires only when the current front changed, decided from fresh data | Sections 4 and 6 |
| Failure injection | The fake server fails mid-write: 500, a dropped connection after the request was sent, 429, and each "retry repeats a success" case | Section 9 |
| ViewModel | Fronters redraw on a members-only change; edit form states; the time window from neighbors; delete-confirmation text; picker edit mode | Screen logic without a device |
| Notify | Corrections go out with the relay on; correction wording; receivers drop older messages (Kotlin and web receiver, shared fixtures); the relay config is re-uploaded after a shared member's rename | Section 6 |
| Sync | `Notice` with and without the members hash; `MemberSync` handled with the app closed (the complication's line expires) | Section 7 |
| UI (Robolectric) | The editor (including restore after process death) and the switch sheet | Wiring and labels |

The property test uses a seeded generator written for it, not a new library.
