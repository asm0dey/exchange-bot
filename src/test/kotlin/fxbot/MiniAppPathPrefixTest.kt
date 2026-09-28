package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * `miniAppPathPrefix` / `miniAppMenuUrl` are pure functions derived from `MINIAPP_URL`, so
 * they're tested in isolation here rather than through a running server — `MiniAppServerTest`
 * covers the prefix actually being applied to routing.
 */
class MiniAppPathPrefixTest : StringSpec({
    "a bare hostname with a trailing slash has no prefix" {
        miniAppPathPrefix("https://x/") shouldBe ""
    }
    "a bare hostname with no trailing slash has no prefix" {
        miniAppPathPrefix("https://x") shouldBe ""
    }
    "a path with no trailing slash becomes the prefix" {
        miniAppPathPrefix("https://x/exchange") shouldBe "/exchange"
    }
    "a path with a trailing slash becomes the same prefix" {
        miniAppPathPrefix("https://x/exchange/") shouldBe "/exchange"
    }
    "a query string is stripped" {
        miniAppPathPrefix("https://x/exchange?foo=bar") shouldBe "/exchange"
    }
    "a fragment is stripped" {
        miniAppPathPrefix("https://x/exchange#frag") shouldBe "/exchange"
    }
    "a multi-segment path keeps every segment" {
        miniAppPathPrefix("https://x/a/b/") shouldBe "/a/b"
    }
    "an unparseable URL falls back to the root prefix rather than throwing" {
        miniAppPathPrefix("not a url") shouldBe ""
    }

    "the menu URL for a root deployment is returned unchanged" {
        miniAppMenuUrl("https://x") shouldBe "https://x"
        miniAppMenuUrl("https://x/") shouldBe "https://x/"
    }
    "the menu URL for a path deployment gains a trailing slash when it lacks one" {
        miniAppMenuUrl("https://x/exchange") shouldBe "https://x/exchange/"
    }
    "the menu URL for a path deployment is unchanged when it already has a trailing slash" {
        miniAppMenuUrl("https://x/exchange/") shouldBe "https://x/exchange/"
    }
})
