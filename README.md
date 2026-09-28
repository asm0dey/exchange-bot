# exchange-bot

A Telegram bot that introduces people who want opposite sides of the same
currency exchange — in a group chat, or privately with no names either way
until both of you agree to pass them. It passes names and gets out of the way —
it never holds money, quotes a price, or settles anything.

## Setup

1. Create a bot with @BotFather and copy the token.
2. `cp .env.example .env`
3. `./gradlew keygen -q >> .env` and add values for `BOT_TOKEN`, `DB_FILE_KEY`, `DB_USER_PW`.
4. `docker compose up -d`
5. Add the bot to your group. Anyone can post; admins set the currencies.

## Using it

    /sell 1000 EUR    you're handing over 1000 EUR
    /buy 1000 EUR     you want to receive 1000 EUR
    /status           who's waiting
    /cancel a1        withdraw yours
    /done a1 @someone you two swapped
    /reopen           undo your last /done
    /settings         this chat's currencies and limits
    /forget           erase what's stored about you here
    /help             this list, from the bot itself

`/sell` and `/buy` are two ways of saying the same four things: what matters is
which currency you hand over. Selling euros and buying roubles are the same
side, so they never match each other.

Admins: `/pair EUR RUB`, `/tolerance 20`, `/tif 7`, and `/fanout on` to let
interests stated privately be shown in this chat.

## Privately

Say it once to the bot and it is shown in every chat you share with it that has
fan-out on, and worked bot-wide against people you share no chat with at all.
Nobody's name passes either way until you both press to give it up.

    /sell 10 EUR for RUB   you're handing over 10 EUR, wanting RUB
    /buy 10 RUB for EUR    the same thing, said from the other end
    /tolerance 20          your own size tolerance, 1-100
    /status                your interests and where each still rests
    /cancel a1             withdraw one, every showing with it
    /done a1               you two swapped
    /settings              your size tolerance
    /forget                erase your own side and everything personal

`/forget` privately erases your privately stated interests, your size
tolerance, your name give-ups and any messages here — but not what is showing
in a group, because that is a record in that group. `/forget all` reaches those
too, in every group the bot shares with you.

## The app

The same things, in a Telegram Mini App: what's resting, posting, taking the other
side of a request, cancelling, done and its confirmation, and your size tolerance.
In a group, type `/app` for a button that opens that group's requests. Privately,
tap the menu button next to the message field.

The app is off until you set it up (below). Without it the bot opens no port.

## Setting up the app

Telegram only opens a mini app from a public **HTTPS** address with a certificate
browsers trust. The bot itself speaks plain HTTP on port 8080 inside its container.
Your reverse proxy sits in between: it holds the certificate and forwards to the bot.

    Telegram app ──HTTPS──▶ your reverse proxy ──HTTP :8080──▶ exchange-bot container

### What you need

- **A domain name** you control, for example `example.com`.
- **A subdomain for the app**, for example `exchange.example.com`. The app must be at
  the root of its own hostname; a path like `example.com/exchange/` does not work.
- **A reverse proxy** on a host that is reachable from the internet on port 443
  (Caddy, nginx, Traefik, …), with a trusted certificate for that subdomain. Let's
  Encrypt is fine; a self-signed certificate is not.

### 1. Point the subdomain at your server

At your DNS provider, add a record for the subdomain pointing at the server that runs
the reverse proxy:

    exchange.example.com.   A   203.0.113.10

Check it resolves before going on: `dig +short exchange.example.com` should print
your server's IP.

### 2. Let the proxy reach the bot

The bot listens on port 8080 inside the container and `compose.yaml` does not publish
it. Pick one:

- **The proxy runs in Docker too:** put both on one network. Add to the bot service
  in `compose.deploy.yaml`:

      networks: [proxy]

  and at the bottom of the file:

      networks:
        proxy:
          external: true

  (`docker network create proxy` once, and attach your proxy container to it.) The
  proxy then reaches the bot as `exchange-bot:8080`.
- **The proxy runs directly on the host:** publish the port on localhost only, so
  it is not open to the internet:

      ports:
        - "127.0.0.1:8080:8080"

  The proxy then reaches the bot as `127.0.0.1:8080`.

### 3. Configure the proxy

Forward everything on the subdomain to the bot. Nothing else is needed: no path
rewriting, no websockets.

**Caddy** (gets the certificate for you):

    exchange.example.com {
        reverse_proxy exchange-bot:8080
    }

**nginx** (certificate from certbot or similar):

    server {
        listen 443 ssl;
        http2 on;
        server_name exchange.example.com;
        ssl_certificate     /etc/letsencrypt/live/exchange.example.com/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/exchange.example.com/privkey.pem;

        location / {
            proxy_pass http://127.0.0.1:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Proto https;
        }
    }

**Traefik** (labels on the bot service in `compose.deploy.yaml`):

    labels:
      - traefik.enable=true
      - traefik.http.routers.exchange.rule=Host(`exchange.example.com`)
      - traefik.http.routers.exchange.entrypoints=websecure
      - traefik.http.routers.exchange.tls.certresolver=letsencrypt
      - traefik.http.services.exchange.loadbalancer.server.port=8080

Use your own resolver and entrypoint names; the ones above are Traefik's usual examples.

### 4. Register the app with BotFather

In @BotFather: `/newapp`, pick this bot, and answer its questions. When it asks for
the **Web App URL**, give `https://exchange.example.com`. When it asks for a **short
name**, give something like `exchange`. That makes the app's link
`https://t.me/<your bot>/exchange`.

### 5. Tell the bot, and restart

In `.env`:

    MINIAPP_URL=https://exchange.example.com
    MINIAPP_SHORT_NAME=exchange

Then `docker compose -f compose.deploy.yaml up -d`. On start the bot sets its menu
button to open the app, and `/app` starts answering.

### 6. Check it

    curl -sI https://exchange.example.com/ | head -1
    # HTTP/2 200: the app is served

    curl -s -o /dev/null -w '%{http_code}\n' https://exchange.example.com/api/me
    # 401: the API is up and refuses calls that don't come from Telegram

Then open the bot privately in Telegram and tap the menu button, and send `/app` in
a group.

### If it doesn't work

| What you see | Likely cause |
|---|---|
| Telegram shows a blank page or "can't open" | Certificate not trusted, or the proxy isn't forwarding. Check the `curl -sI` above from another machine. |
| The page shows "Open this from Telegram." | You opened the URL in a normal browser, outside Telegram — the app only runs inside the Telegram client. |
| The app worked, then switches to "Reopen from Telegram." | Telegram's session data for the app expired or was rejected; close and reopen it from the chat. |
| `/app` says the app isn't set up | `MINIAPP_URL` or `MINIAPP_SHORT_NAME` is missing from `.env`, or the bot wasn't restarted. |
| The group view says "You're not in this chat" | You're not a member of that group, or the bot was removed from it. |
| `502 Bad Gateway` from the proxy | The proxy can't reach `:8080`: see step 2, and check the bot started with a "mini app listening" line in its log. |

## Runtime

Targets Java 25. The production image (built by `Dockerfile`) runs on
[`bellsoft/hardened-liberica-runtime-container`](https://hub.docker.com/r/bellsoft/hardened-liberica-runtime-container)'s
`jre-25-cds-distroless-glibc` — a JRE-only, shell-less, hardened base image, with
a class-data-sharing archive included for faster startup. It runs as a fixed
non-root uid, `10001`, baked into the image itself (not created by this
project's `Dockerfile`). Because the runtime has no shell, the container's
`ENTRYPOINT` invokes `java` directly against an explicit classpath rather than
the shell launcher script `./gradlew installDist` normally produces; see the
comments in `Dockerfile` and `docs/runtime-notes.md` for what that forced.

## Data

The H2 database lives in the `exchange-bot-data` Docker named volume, mounted
at `/app/data` inside the container — not in a `./data` folder next to the
source. This is deliberate: the container runs as a non-root user, and a
named volume gets its ownership seeded from the image on first use, so that
user can write to it. A bind-mounted host folder doesn't exist until Docker
creates it, and Docker creates it `root`-owned, which the non-root user can't
write into.

Find the actual volume name (Compose prefixes it with the project name):

    docker volume ls | grep exchange-bot-data

Inspect the files without stopping the bot. The runtime image is distroless —
no shell, no `ls` inside the `bot` container — so use a throwaway `alpine`
container against the named volume instead, the same pattern as the backup
recipe below:

    docker run --rm -v exchange-bot-data:/data alpine ls -la /data

Back up the volume to a tarball in the current directory:

    docker run --rm -v exchange-bot-data:/data -v "$PWD":/backup alpine \
      tar czf /backup/exchange-bot-data-backup.tar.gz -C /data .

(replace `exchange-bot-data` with the prefixed name from `docker volume ls`
if it differs). Restore into a fresh volume the same way, with `tar xzf`
instead of `tar czf`.

## Releases

Versions are plain integers — 1, 2, 3. Tagging `v<N>` on `main` runs
`.github/workflows/release.yml`, which publishes two artifacts for that number:

- a self-contained jar on the [GitHub Release](https://github.com/asm0dey/exchange-bot/releases)
- an image at `ghcr.io/asm0dey/exchange-bot:<N>` (also tagged `latest`)

`compose.deploy.yaml` runs a released image instead of building from source —
that is the file to copy to a server, alongside `.env`:

    docker login ghcr.io      # only while the GHCR package is private
    docker compose -f compose.deploy.yaml up -d

The image tag there is a pinned integer, not `latest`: upgrading is a one-digit
edit plus `up -d`, and so is rolling back. `compose.yaml` stays the local
build-from-source file.

The jar is runnable on its own but does *not* carry the container's timezone
pin, so pass it yourself — Exposed round-trips timestamps through the JVM
default zone, and a DST-observing host shifts request expiries without it:

    java -Duser.timezone=UTC -jar exchange-bot-1.jar

## Keys

Five secrets live only in the environment — `BOT_TOKEN`, `DB_FILE_KEY`,
`DB_USER_PW`, `DATA_KEYSET`, `INDEX_KEYSET`. Lose `DATA_KEYSET` or
`INDEX_KEYSET` and the database is unreadable — there is no recovery path, by
design. Back them up somewhere other than the machine running the bot.

## Documentation

- Design: `docs/superpowers/specs/2026-08-30-exchange-bot-design.md`
- Vocabulary: `CONTEXT.md`
- Decisions: `docs/adr/`
