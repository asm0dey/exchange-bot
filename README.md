# exchange-bot

A Telegram bot that introduces people in the same group chat who want opposite
sides of the same currency exchange. It passes names and gets out of the way —
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

Send `/forget all` to the bot in a private chat to erase your data across
every group it shares with you, not just the current one.

Admins: `/pair EUR RUB`, `/tolerance 20`, `/tif 7`.

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
