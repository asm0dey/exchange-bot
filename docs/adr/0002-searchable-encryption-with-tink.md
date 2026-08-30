# Sensitive columns encrypted with Tink, identifiers stored as keyed hashes

Requests link a person's identity to their intent to move money, so the
database is encrypted twice over: H2's own file cipher, plus per-row AEAD on a
JSON payload holding everything sensitive. Columns the bot must filter on —
which chat, which person — are stored as keyed HMACs rather than ciphertext, so
equality lookups work without decrypting the table.

## Considered Options

**Google Tink** (chosen). Pure Java, current, and its keyset model supplies key
rotation and key versioning, so no `key_version` column and no bespoke rotation
tool exist in this codebase.

**Cossack Labs Themis.** Secure Cell's Seal mode is the same primitive with an
equally good API, but the JVM binding is JNI over a native library the JAR does
not bundle: libthemis must be installed through the system package manager,
only x86_64 is documented, there is no Alpine or Docker guidance, and the
binding's last release predates this decision by nearly three years. Themis
earns that cost when the same ciphertext must be read from Swift, Go or Python;
this bot is one JVM process.

**Acra.** Its proxy mode only understands the MySQL and PostgreSQL wire
protocols, so it cannot sit in front of an embedded database at all; adopting
it would have meant changing databases and running two more services.

**Hand-rolled JDK crypto.** Viable — AES-GCM with a random nonce is not exotic —
but nonce handling, tag layout and rotation would all have been ours to get
right, and Tink costs one dependency.

## Consequences

The keyed hashes are deterministic, so anyone holding both the database file
and the MAC keyset can count per-chat and per-user activity and confirm a
guessed identifier. That is the price of being able to query at all, and it is
the reason the two keysets never share key material.

Range queries on amounts are impossible. Nothing in the design needs them, and
adding one would mean reconsidering this whole scheme rather than adding an
index.
