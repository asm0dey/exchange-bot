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
    "an uppercase path segment keeps its case (paths are case-sensitive)" {
        miniAppPathPrefix("https://x/Exchange/") shouldBe "/Exchange"
    }
    "a double slash right after the host stays in the path as URI parses it" {
        // java.net.URI's authority stops at the first '/', so the second '/' is already
        // part of the path, not a second (empty) path segment collapsed away — this is a
        // deliberate "document what URI gives us" case, not a normalization guarantee.
        miniAppPathPrefix("https://x//exchange/") shouldBe "//exchange"
    }
    "a percent-encoded path segment is decoded, same as an unencoded one" {
        miniAppPathPrefix("https://x/exch%61nge/") shouldBe "/exchange"
    }
    "a port on the host does not affect the path prefix" {
        miniAppPathPrefix("https://x:8443/exchange/") shouldBe "/exchange"
    }
    "a scheme-less string is not a URL this function can make sense of, and that's exactly" +
        " why isValidMiniAppUrl (not this function) gates what Main.kt actually starts" {
        // With no scheme, java.net.URI treats the WHOLE string as a relative-reference path
        // — there is no host component to separate out, so the "prefix" swallows the host
        // too. Documented here as the chosen (if not very meaningful) behaviour; the actual
        // startup guard against exactly this input is isValidMiniAppUrl, tested below.
        miniAppPathPrefix("example.com/exchange/") shouldBe "example.com/exchange"
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
    "the menu URL for a path deployment drops a query string rather than appending after it" {
        miniAppMenuUrl("https://x/exchange?x=1") shouldBe "https://x/exchange/"
    }
    "the menu URL for a path deployment drops a fragment rather than appending after it" {
        miniAppMenuUrl("https://x/exchange#section") shouldBe "https://x/exchange/"
    }
    "the menu URL for a path deployment keeps the port" {
        miniAppMenuUrl("https://x:8443/exchange") shouldBe "https://x:8443/exchange/"
    }

    "a valid https URL with a host is a valid MINIAPP_URL" {
        isValidMiniAppUrl("https://x") shouldBe true
        isValidMiniAppUrl("https://x/exchange/") shouldBe true
    }
    "a valid http URL with a host is a valid MINIAPP_URL" {
        isValidMiniAppUrl("http://x") shouldBe true
    }
    "a scheme-less string is not a valid MINIAPP_URL" {
        isValidMiniAppUrl("example.com/exchange/") shouldBe false
    }
    "an unparseable string is not a valid MINIAPP_URL" {
        isValidMiniAppUrl("not a url") shouldBe false
    }
    "a non-http(s) scheme is not a valid MINIAPP_URL" {
        isValidMiniAppUrl("ftp://x/exchange/") shouldBe false
    }
    "a scheme with no host is not a valid MINIAPP_URL" {
        isValidMiniAppUrl("https:///exchange/") shouldBe false
    }
})
