# PluralWare relay

Optional. Without it, PluralWare tells your friends about the switches you make on your watch.
With it, they also hear about switches made in Discord (`pk;switch`), on the dashboard, or in any
other client. PluralKit sends every switch here, and the relay passes it on.

It's the "phase C" of [docs/notifications-design.md](../docs/notifications-design.md) (§10). In
short:

- It **validates** every PluralKit event against the webhook's signing token. PluralKit requires
  that, and checks it with deliberate bad pings.
- It **ignores** everything but new switches, and ignores switches more than five minutes old
  (backdated or imported).
- It **names only the members you chose to share.** Your phone uploads their display labels. The
  relay never holds your PluralKit token, so it can't read or change your system.
- It **sends** to each friend the same way your watch would: encrypted Web Push for Private
  friends, ntfy for Simple ones.

It has no dependencies. The same code runs as a Cloudflare Worker or under Node 20+.

## Deploy

You need a public **https** address, because PluralKit and your phone both have to reach it. You
also need a long random **admin secret**, which your phone uses to upload settings, e.g.
`openssl rand -base64 32`.

### Cloudflare Workers (free tier is plenty)

```sh
cd relay
cp wrangler.toml.example wrangler.toml
npx wrangler kv namespace create RELAY_KV   # put the id it prints into wrangler.toml
npx wrangler secret put ADMIN_SECRET        # paste your admin secret
npx wrangler deploy                         # prints your https://…workers.dev address
```

### Your own server

```sh
cd relay
ADMIN_SECRET='…' PORT=8787 STATE_FILE=/var/lib/pluralware-relay/state.json node src/server.mjs
```

Put it behind a reverse proxy that terminates TLS, the way you would a self-hosted ntfy. The state
file holds your friends' follow codes and your VAPID key, so it's written readable by its owner
only; keep it that way.

## Connect it

In PluralWare on your phone: **Share with friends → Relay**.

1. Enter the relay's address and admin secret, and save. The app shows a `pk;s webhook …`
   command.
2. Run that command **in a DM with PluralKit**. PluralKit shows a signing token and waits.
3. Paste the token into the app and save. The relay now has what it needs to pass PluralKit's
   check.
4. Reply `yes` to PluralKit, which tests the relay and confirms.
5. Turn on **Send through the relay**. Your watch stops sending, so nobody gets a switch twice.

PluralKit allows one webhook per system, so this replaces any other dispatch integration you had.
If the relay is down for long, PluralKit removes the webhook, and you run step 2 again.

## API

| Request | Auth | What it does |
|---|---|---|
| `POST /pk/<webhook path>` | PluralKit's signing token, in the body | Receives dispatch events. |
| `PUT /config` | `Bearer <admin secret>` | The phone uploads its settings (`RelayConfig` in `shared/.../notify/Relay.kt`). |
| `GET /status` | `Bearer <admin secret>` | Whether it's configured and enabled, friends whose subscriptions are gone, and the last send. |
| `DELETE /config` | `Bearer <admin secret>` | Forget everything; the phone calls this when you disconnect. |

## Test

```sh
cd relay && npm test
```

The tests include the RFC 8291 worked example, reproduced byte for byte. They also share fixture
strings with the Kotlin tests: the uploaded config in `config-interop.test.mjs`, and the
announcement wording in `announce.test.mjs`.
