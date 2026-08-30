# Amounts are stored exactly as typed and converted only when compared

A request keeps the amount and currency the person wrote, plus its side; the
notional is derived at comparison time from the current reference rate. The
obvious alternative — deriving it on write — would freeze a rate into the row,
so someone who asked for 1000 EUR would silently come to mean a different
number of euros every time the market moved.

## Consequences

Every listing can echo people's own words back to them, because the bot never
overwrote them. A request stated in either currency is also accepted without a
reference rate being available at all; it simply cannot be compared across
denominations until one is.

Reference rates therefore change which requests match, without any row
changing. That is intended: the comparison is about present-day size, not about
what the market did on the day someone posted.
