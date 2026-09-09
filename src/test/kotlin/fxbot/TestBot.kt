package fxbot

import eu.vendeli.tgbot.TelegramBot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

/** One outgoing Telegram call: the method name, and the JSON body that was sent with it. */
data class Call(val path: String, val body: String)

/** A minimal but schema-valid `Message`, since `sendReturning` decodes into the action's real return type. */
private const val MESSAGE_RESULT =
    """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}"""

private const val TRUE_RESULT = """{"ok":true,"result":true}"""

/**
 * A [TelegramBot] whose HTTP client records every outgoing call instead of reaching the
 * network, so a test can assert exactly what was sent — which method, with which body —
 * rather than only that a send was attempted.
 *
 * Every call is answered as a success: a `send*`/`edit*` gets a `Message` back and
 * everything else gets `true`, matching what those methods actually return. A test that
 * needs a Telegram-side FAILURE (a message too old to delete, say) needs its own engine —
 * this one is for the cases where the outcome of each call is not what is under test.
 */
fun recordingBot(sink: MutableList<Call>): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val path = request.url.encodedPath.substringAfterLast('/')
        sink += Call(path, bytes.decodeToString())
        respond(
            content = if (path.startsWith("send") || path.startsWith("edit")) MESSAGE_RESULT else TRUE_RESULT,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
    return TelegramBot(token = "000:fake-token-for-tests", httpClient = client)
}
