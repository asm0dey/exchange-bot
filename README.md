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

Inspect the files without stopping the bot:

    docker compose exec bot ls -la /app/data

Back up the volume to a tarball in the current directory:

    docker run --rm -v exchange-bot-data:/data -v "$PWD":/backup alpine \
      tar czf /backup/exchange-bot-data-backup.tar.gz -C /data .

(replace `exchange-bot-data` with the prefixed name from `docker volume ls`
if it differs). Restore into a fresh volume the same way, with `tar xzf`
instead of `tar czf`.

## Keys

Five secrets live only in the environment — `BOT_TOKEN`, `DB_FILE_KEY`,
`DB_USER_PW`, `DATA_KEYSET`, `INDEX_KEYSET`. Lose `DATA_KEYSET` or
`INDEX_KEYSET` and the database is unreadable — there is no recovery path, by
design. Back them up somewhere other than the machine running the bot.

## Documentation

- Design: `docs/superpowers/specs/2026-08-30-exchange-bot-design.md`
- Vocabulary: `CONTEXT.md`
- Decisions: `docs/adr/`
