# A Telegram Mini App over the same services

**Date:** 2026-09-27
**Status:** Approved design. Not yet implemented.
**Mockups:** `miniapp-mockups/`. See *How closely to follow the mockups*.

The mini app is another way to do what the bot already does: see what is
resting, state a request, cancel it, declare and confirm a done, set a size
tolerance. It works in a group (that chat's requests) and privately (your own
requests, plus anonymous browsing of those resting with no chat behind them).

It adds **no domain concept**. Every action calls an existing service, and the
matcher, as today, is the only thing that names people to each other. "Taking the
other side" of a card is a pre-filled request, not a reply to that card.

## Decisions

| # | Decision | Why |
|---|---|---|
| 1 | Taking a card = the create form pre-filled with the opposite side and the same size | Keeps ADR 0001 and ADR 0008 intact: nothing is reserved, names pass only through the matcher |
| 2 | Same process, same image: a Ktor CIO server next to the long-poll loop | ADR 0004, single process. The user's reverse proxy terminates TLS |
| 3 | Vue 3 + TypeScript + Tailwind v4 + daisyUI, built by Bun, bundled into the jar | Matches the user's other projects (calit, slidev-polls) |
| 4 | v1 covers view, create, take, cancel, done, confirm/refuse, personal tolerance | Admin chat settings and `/forget` stay bot-only |
| 5 | Private Browse shows no-chat requests **without names or tokens** | Resting privately must not reveal you to every bot user, only to counterparties |
| 6 | UI copy never uses `CONTEXT.md` terms | See *Wording* |
| 7 | Cards show the other leg as "≈ N CUR" at the reference rate | People think in both currencies. `CONTEXT.md` is updated to allow it, see *Vocabulary* |
| 8 | Each counterparty's accepted range sits beside the amount, with a "fits" mark | Shows before posting whether the matcher will pair you |
| 9 | Privately, any currency pair can be picked; Browse filters by pair | Private requests are not bound to a chat's pair |

## Architecture

```
Telegram client ──HTTPS──▶ reverse proxy ──HTTP──▶ bot process
                                                   ├─ long-poll loop (unchanged)
                                                   └─ Ktor CIO :MINIAPP_PORT
                                                       ├─ /            SPA (jar resources)
                                                       └─ /api/*       JSON → existing services → Delivery
```

- `Main.kt` starts the server in the same coroutine scope as the bot.
- New config: `MINIAPP_PORT` (listen port), `MINIAPP_URL` (the public HTTPS
  origin behind the proxy), and `MINIAPP_SHORT_NAME` (the app's BotFather short
  name, used to build `t.me/<bot>/<short name>` links). With `MINIAPP_URL` unset
  the server does not start, so existing deployments keep working unchanged.
- The app can be served **at a hostname root or under a path**, given in
  `MINIAPP_URL` (for example `exchange.example.com`, or `example.com/exchange/`): the
  SPA loads its assets and calls the API by relative path, and the server derives a
  mount prefix from `MINIAPP_URL`'s own path (`miniAppPathPrefix`). Telegram requires
  HTTPS with a trusted certificate. The README carries a step-by-step reverse-proxy
  guide (DNS, Caddy / nginx / Traefik, BotFather, checks, troubleshooting) for both a
  subdomain and a path setup.
- On startup the bot calls `setChatMenuButton` with a `MenuButton.WebApp` pointing at
  the public URL (`MINIAPP_URL`). That is how the app opens privately.

### Trust: one root per call

Every `/api` call carries `Authorization: tma <initData>`. One Ktor plugin does this:

1. It rebuilds the data-check-string (every field except `hash`, sorted, joined
   with `\n`) and compares `hex(HMAC_SHA256(dcs, HMAC_SHA256("WebAppData",
   BOT_TOKEN)))` with `hash` in constant time.
2. It rejects `auth_date` older than 24 hours.
3. It parses `user.id` and `user.username`, and puts `Viewer(userId, username)` on the call.

No handler reads a user id from anywhere else. This plays the role that
`callback_query.from.id` plays for buttons, so every guard already inside
`LifecycleService` (you act only on your own tokens) applies unchanged. A
failure at any step returns `401`.

### Group context

- A new `/app` command in a group replies with a **URL** button to
  `t.me/<bot>/<short name>?startapp=c<chatId>`. Telegram does not allow `web_app`
  buttons in groups.
- A direct-link launch carries `start_param` but **no chat id**. The signature
  proves Telegram delivered the parameter, not that the viewer belongs to the chat.
- So every group-scoped route calls the existing `MembershipProbe.isMember(chatId,
  userId)`, which is `getChatMember`. Results are cached in memory for 60 seconds.
  A non-member gets `403`.
- The SPA reads `start_param`. `c<id>` opens the group view; no parameter opens
  the private view.

### Delivery is shared, not duplicated

Today the command and callback handlers both call the services and send the
resulting messages. The sending half moves into a `Delivery` unit, and handlers
and routes both call it. Whatever route a request takes, the group still sees
the post, counterparties are still pinged, and a declared done still reaches the
counterparty with Confirm/Refuse buttons. Existing command tests are the
regression guard for this extraction.

## API

| Route | Scope | Calls | Then |
|---|---|---|---|
| `GET /api/chat/{id}` | member | chat settings, `RateService.status`, `RequestRepository.resting(id)`, names looked up live | — |
| `POST /api/chat/{id}/requests` | member | `RequestService.post` | `Delivery` |
| `GET /api/me` | viewer | `InterestService.standings`, pending confirmations, `PersonSettingsRepository` | — |
| `GET /api/browse?pair=` | viewer | `resting(NO_CHAT_ID)` minus the viewer's own; `noChatPairs()` for the filter | — |
| `POST /api/interests` | viewer | `InterestService.state` | `Delivery` |
| `POST /api/requests/{token}/cancel` | viewer | `LifecycleService.cancelByToken` | `Delivery` |
| `POST /api/done`, `/api/confirm`, `/api/refuse` | viewer | `LifecycleService.done` / `confirm` / `refuse` | `Delivery` |
| `PUT /api/me/tolerance` | viewer | `PersonSettingsRepository`, validated by `parseTolerancePct` | — |

- **Handles:** requests are addressed by their existing `refToken`, the opaque
  handle the callback buttons already use. Browse rows carry **neither a token nor
  a name**. A Browse row only exposes the fields needed to pre-fill a form: side,
  stated amount and currency, pair, age, and the accepted range.
- **No schema change.** No table, no migration. Display names are looked up live
  and not stored (ADR 0008). The membership cache is memory only.
- **Errors:** a service `Rejected(reason)` or failing `ActionResult` becomes
  `422 {message}` with the bot's own wording, shown inline under the form. A bad or
  stale `initData` is `401`, and the SPA shows "Reopen from Telegram". A non-member
  is `403`.
- **Freshness:** no WebSocket. The SPA refetches when it regains focus, from
  a refresh button, and every 30 seconds while visible. Telegram uses
  swipe-down to close a mini app, so pull-to-refresh would fight it.

## Screens

The mockups are the reference. This section lists what each one must do.

1. **Group view:** the pair header with the reference rate, labelled
   "Reference, not a price". A segmented filter: All / Have EUR / Want EUR. Two
   sections: *Have EUR, want RUB* and *Have RUB, want EUR*, newest first, no
   ranking. Your own card shows *Cancel*, and *Done with …* for each named
   counterparty. Other cards show a button naming what **you** would give
   ("Give EUR" / "Give RUB"). A floating *New request* button.
2. **Pre-filled sheet** (from a card): the opposite side at the same size. It
   shows the counterparty's accepted range with a *fits* mark and a range bar, and
   a one-line summary ("You give 950 EUR for ≈ 89 400 RUB"). The Telegram
   `MainButton` reads "Post in <chat title>".
3. **Give / receive toggle on a pre-filled sheet:** it switches only the
   **typed currency** (I give 950 EUR ⇄ I receive 89 400 RUB). The range is
   re-expressed in that currency. The combination that would put you on the card's
   own side (here: receiving EUR) is never offered.
4. **Private, Mine:** *To confirm* cards on top (Yes, we swapped / No). Then
   your requests, where each one rests, and the named counterparties with
   *Done with*. The limit is shown as "2 of 5". A tab bar: Mine / Browse /
   Tolerance.
5. **Private, Browse:** a note that nobody is named here, pair chips, anonymous
   cards with the same *Give CUR* buttons.
6. **Private, New request:** a pair picker (two currency selects and a swap
   button), I give / I want, amount. Below the amount, every request resting on
   the other side of that pair, each with its accepted range and whether yours
   fits ("fits 1 of 2"). The `MainButton` reads "Post in all my chats".

## Wording

`CONTEXT.md` terms are for reading the code. The UI uses words for what people do.

| Code | UI |
|---|---|
| Offer / Bid | **Gives** / **Wants**, derived from the typed currency (table below) |
| request / interest / showing | **request**, everywhere. The button says where it goes |
| done, confirmation | "Done with …", "Yes, we swapped" / "No" |

The verb follows the currency the amount was typed in, not only the side:

| Side | Typed in base (EUR) | Typed in quote (RUB) |
|---|---|---|
| Offer | Gives 1 000 EUR | Wants 45 000 RUB |
| Bid | Wants 950 EUR | Gives 20 000 RUB |

The card's second line is always the other leg: "for ≈ N CUR".

## Accepted range

For a counterparty of size *n* (notional, base currency) with tolerance *tₜ*,
and the viewer's tolerance *tₘ*, the viewer's size *s* is a counterparty exactly when

    n · (1 − tₜ)  ≤  s  ≤  n ÷ (1 − tₘ)

This restates `findCounterparties`: the lower bound is their residual limit, the
upper bound is yours. With *tₘ* = 100 there is no upper bound, and the UI shows
"from 760 EUR".

- In a group, both tolerances are the chat's.
- Privately, *tₜ* is the counterparty's own person tolerance and *tₘ* is yours.
  Only the range is shown, never the percentage behind it.
- The server returns each range in the base currency together with the rate. The
  client only scales it for display, and the "fits" mark compares the typed
  amount's notional with that range.
- A unit test pins the range formula to `findCounterparties`: for generated
  sizes and tolerances, *s* inside the range ⇔ the matcher returns the pair.

When no reference rate is available, the ≈ figures are hidden. Ranges are
shown only against requests typed in the same currency, which is what the
matcher can compare without a rate.

## How closely to follow the mockups

The mockups show the idea, not pixels. The built app must keep:

- the same screens, and the same information on each;
- the same controls in roughly the same places (a floating *New request*, per-card
  *Give CUR* buttons, the bottom sheet, the give/receive toggle, the range bar
  beside the amount, the tab bar, `MainButton` for the primary action);
- the same wording.

Spacing, sizes, radii and exact colors may differ where daisyUI or Telegram's
own components do it differently.

**If a mockup element doesn't work in practice** (a Telegram client clips it,
a control is awkward on a real phone, a daisyUI component can't do it cleanly),
stop and report it to the user with a screenshot, instead of quietly
redesigning it. The user wants to experiment with alternatives together.

## Visual design

- **Colors come from Telegram.** A daisyUI custom theme maps its tokens onto the
  `--tg-theme-*` variables (bg, secondary-bg, text, hint, link, button,
  button-text), so light, dark and custom client themes follow automatically.
- **The only colors of our own:** amber for "has the base to give" and teal for
  "wants the base". Never red or green, which read as sell/buy and price moves.
  Every card also says Gives/Wants in words, so color is never the only cue.
- **Type:** the system font stack, no web fonts. `tabular-nums` on every amount.
- **Icons:** Lucide SVG, no emoji.
- **Native controls:** `MainButton` for the primary action, `BackButton` on
  sheets, `HapticFeedback` on post, done and confirm. `prefers-reduced-motion`
  is respected.

## Build and deploy

- `web/`: a Bun project (Vue 3, Vite, Tailwind v4, daisyUI, vitest, Playwright).
  Its dependencies come from the npm registry, declared in `web/package.json` and
  locked in `web/bun.lock`. No webjars: they can't compile `.vue` files, TypeScript
  or Tailwind.
- Gradle drives it with `com.github.node-gradle.node`: it downloads a pinned Node
  into `.gradle/` and installs a pinned Bun from npm with it. `processResources`
  depends on `webBuild` (output `build/web/static`, bundled as `static/`), and
  `check` depends on `webTest`. `./gradlew build` needs only a JDK.
- Dockerfile: no extra stage. The Gradle stage copies `web/` and `installDist`
  builds everything. The runtime image stays the distroless Liberica JRE.
- New dependencies: `ktor-server-core`, `ktor-server-cio`,
  `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json` (the existing
  Ktor version ref), and `ktor-server-test-host` for tests. Renovate picks them up.
- Compose: expose `MINIAPP_PORT` on the internal network only; the reverse proxy
  routes to it.

## Testing

1. **`initData` verifier**, the one security-critical unit: a vector signed with
   a test token passes; changing any field, a stale `auth_date`, a missing `hash`,
   or the wrong token fails.
2. **Routes** (`testApplication`, fake services): `401` without auth, `403` for a
   non-member, `422` carries the service's message, and Browse never contains a
   name, a username or a token.
3. **Range formula** ⇔ `findCounterparties`, as a property test (Kotest).
4. **Delivery extraction:** the existing command and callback tests pass
   unchanged.
5. **Frontend unit:** vitest for the API client, the Gives/Wants rule, the
   toggle's same-side exclusion and range scaling.
6. **Frontend E2E** (Playwright, Chromium only, no backend):
   - `page.addInitScript` installs a fake `window.Telegram.WebApp`: `initData`,
     `themeParams` (light and dark sets), `start_param` (`c<chatId>` or empty),
     and stub `MainButton`, `BackButton` and `HapticFeedback` that record calls.
   - `page.route('/api/**')` serves JSON fixtures in `web/e2e/fixtures/`, holding
     the mockups' own sample data (Belgrade Expats, EUR/RUB at 94.12, Marko,
     Jelena, @tomas_k, @ana.p, Ivan's pending done, the Browse rows).
   - **Screens:** all six from `miniapp-mockups/` (1, 2, 2b, 3, 4, 5), each in
     both themes, checked with `toHaveScreenshot`.
   - **Flows:** *Give EUR* on Marko opens the sheet at 950 EUR with "760 – 1 187
     EUR ✓ fits"; the toggle switches to 89 400 RUB and never offers receiving
     EUR; typing 700 flips the mark to not fitting; *Post* sends the expected body
     to `POST /api/chat/{id}/requests`; *Cancel*, *Done with* and *Yes, we
     swapped* call their routes; a `422` fixture shows its message under the
     form; a `401` fixture shows "Reopen from Telegram".
   - **Baselines:** the first run's screenshots are reviewed with the user
     against `miniapp-mockups/*.png` for the criteria above (same idea, controls
     close, not pixel-identical), then committed. From then on a visual
     change fails CI until its baseline is updated on purpose.
   - Runs in CI next to the Gradle tests.

## Vocabulary

`CONTEXT.md` changes:

- **Notional:** "never shown as a price" stays. Add: *the mini app may show it as
  an estimate of the other leg, always marked ≈ and always beside the words
  "reference rate"*.
- New section **UI wording**: the mapping table above, stating that UI copy does
  not use Offer/Bid or interest/showing.

## Out of scope for v1

Admin chat settings, `/forget`, live push updates, browsing other groups' requests
privately, and sending chat messages from the app itself (a direct-link mini app has
no chat access. `Delivery` sends as the bot).
