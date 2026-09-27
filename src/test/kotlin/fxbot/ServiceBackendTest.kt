package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** Records where each outcome would have been sent, instead of sending it. */
class RecordingDelivery : DeliveryPort {
    val decisions = mutableListOf<Pair<Long, ActionResult>>()
    val posted = mutableListOf<Long>()
    val stated = mutableListOf<Long>()
    val refused = mutableListOf<Pair<String, String>>()
    override suspend fun posted(chatId: Long, result: PostResult.Posted) { posted += chatId }
    override suspend fun stated(userId: Long, result: InterestResult.Stated) { stated += userId }
    override suspend fun decision(chatId: Long, result: ActionResult) { decisions += chatId to result }
    override suspend fun refused(declarerToken: String, mineToken: String) { refused += declarerToken to mineToken }
}

class ServiceBackendTest : StringSpec({
    val ds = memDataSource("miniapp-backend")
    migrate(ds)
    val db = connectExposed(ds)
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto, db = db)
    val settings = ChatSettingsRepository(ds, crypto, db = db)
    val people = PersonSettingsRepository(ds, crypto, db = db)
    val messages = MessageLogRepository(ds, crypto, db = db)
    val rateClient = RateClient(HttpClient(MockEngine { respond("{}") }))
    val rates = RateService(rateClient, RateRepository(ds, db = db))
    val pending = PendingAnnouncementRepository(ds, crypto, db = db)
    val names = NameLookup { null }
    val interests = InterestService(requests, settings, people, rates, rateClient, pending, MembershipProbe { _, _ -> false })
    val lifecycle = LifecycleService(
        requests, settings, rates, people, DoneRefusalRepository(ds, db = db), names,
        AskLookup { token, payload -> messages.offered(token, payload) },
    )
    val delivery = RecordingDelivery()
    val backend = ServiceBackend(
        requests, settings, people, rates, RequestService(requests, settings, rates), interests, lifecycle,
        messages, names, titles = { null }, delivery = delivery,
    )
    val group = -1001L
    settings.save(settings.get(group).copy(pair = CurrencyPair("EUR", "RUB")))
    val ann = Viewer(1, "ann", null)
    val bob = Viewer(2, "bob", null)

    "a group post goes through RequestService and is delivered to that group" {
        backend.postInChat(bob, group, NewRequestBody(Says.WANTS, "950", "EUR")).shouldBeInstanceOf<ApiResult.Ok<*>>()
        delivery.posted shouldBe listOf(group)
    }

    "a refused post carries the bot's own reason" {
        val r = backend.postInChat(bob, group, NewRequestBody(Says.GIVES, "abc", "EUR"))
        r.shouldBeInstanceOf<ApiResult.Refused>().message shouldBe "I couldn't read \"abc\" as an amount. Try: /sell 1000 EUR"
    }

    "the chat view gives a token only on the viewer's own card, and a range only on others'" {
        backend.postInChat(ann, group, NewRequestBody(Says.GIVES, "1000", "EUR"))
        val view = backend.chat(ann, group).shouldBeInstanceOf<ApiResult.Ok<ChatView>>().value
        val mine = view.cards.single { it.mine }
        val theirs = view.cards.first { !it.mine }
        mine.token.shouldNotBeNull()
        mine.range.shouldBeNull()
        theirs.token.shouldBeNull()
        theirs.range.shouldNotBeNull()
        theirs.says shouldBe Says.WANTS
    }

    "browse never carries a name, a username, a token or a short id" {
        interests.state(3, "carol_secret", Verb.SELL, "500", "EUR", "RUB")
        val view = backend.browse(ann).shouldBeInstanceOf<ApiResult.Ok<BrowseView>>().value
        view.cards.shouldHaveSize(1)
        val json = Json.encodeToString(BrowseView.serializer(), view)
        json shouldNotContain "carol_secret"
        requests.openFor(3).forEach { json shouldNotContain it.refToken; json shouldNotContain "\"${it.shortId}\"" }
    }

    "browse leaves out the viewer's own interests" {
        val view = backend.browse(Viewer(3, "carol_secret", null)).shouldBeInstanceOf<ApiResult.Ok<BrowseView>>().value
        view.cards.shouldBeEmpty()
    }

    "cancelling somebody else's token is refused and nothing is delivered" {
        val bobs = requests.openFor(2).first()
        val before = delivery.decisions.size
        backend.cancel(ann, bobs.refToken).shouldBeInstanceOf<ApiResult.Refused>()
        delivery.decisions.size shouldBe before
    }

    "a cancel is answered in the chat the row rests in" {
        val mine = requests.openFor(1).first { it.chatId == group }
        backend.cancel(ann, mine.refToken).shouldBeInstanceOf<ApiResult.Ok<MessageDto>>()
        delivery.decisions.last().first shouldBe group
    }

    "an unanswered ask is pending, and a refusal removes it and tells delivery" {
        val decl = requests.create(group, 5, "dan", Side.OFFER, "EUR", BigDecimal(10), CurrencyPair("EUR", "RUB"), 7)
        val mine = requests.create(group, 1, "ann", Side.BID, "EUR", BigDecimal(10), CurrencyPair("EUR", "RUB"), 7)
        val yes = Cb.confirm(decl.refToken, mine.refToken)
        val no = Cb.refuse(decl.refToken, mine.refToken)
        messages.record(1, 900, listOf(decl.refToken, mine.refToken), listOf(5, 1), "q", listOf(Button("Yes", yes), Button("No", no)))
        val me = backend.me(ann).shouldBeInstanceOf<ApiResult.Ok<MeView>>().value
        me.pending.single().declarerToken shouldBe decl.refToken
        backend.refuse(ann, AnswerBody(decl.refToken, mine.refToken)).shouldBeInstanceOf<ApiResult.Ok<MessageDto>>()
        delivery.refused.last() shouldBe (decl.refToken to mine.refToken)
        messages.dropButton(1, 900, no) // what TelegramDelivery.refused does for real
        backend.me(ann).shouldBeInstanceOf<ApiResult.Ok<MeView>>().value.pending.shouldBeEmpty()
    }

    "tolerance outside 1-100 is refused with the bot's help text" {
        backend.setTolerance(ann, 0).shouldBeInstanceOf<ApiResult.Refused>().message shouldBe TOLERANCE_HELP
        backend.setTolerance(ann, 35).shouldBeInstanceOf<ApiResult.Ok<MeView>>().value.tolerancePct shouldBe 35
    }
})
