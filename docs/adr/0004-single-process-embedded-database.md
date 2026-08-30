# One process, one embedded database file

The bot runs as a single JVM process with an embedded H2 file, Flyway for
migrations, and an in-process scheduler. It serves group chats whose membership
is measured in dozens, so the operational simplicity of one container is worth
more than anything a database server would provide.

## Consequences

This is what rules out the database-security products aimed at this exact
problem — they proxy MySQL and PostgreSQL wire protocols, and there is no wire
protocol here.

The neighbouring conference-notifier-bot in the same stack also builds a
GraalVM native image; this one deliberately does not. That machinery cost
reachability-metadata regeneration and build-time initialisation tuning on
every telegram-bot upgrade, for a startup-time benefit a long-running bot does
not need. If someone reaches for it later, that is the cost being re-accepted.

Concurrency is handled by making state transitions conditional in SQL rather
than by locking, which is sufficient for one writer and would need revisiting
if the bot were ever run as more than one instance.
