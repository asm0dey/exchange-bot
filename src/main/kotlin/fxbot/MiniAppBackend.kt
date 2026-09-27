package fxbot

import java.math.BigDecimal
import java.math.RoundingMode

/** What the HTTP layer can ask for. The viewer always comes from verified initData. */
interface MiniAppBackend {
    suspend fun chat(viewer: Viewer, chatId: Long): ApiResult<ChatView>
    suspend fun postInChat(viewer: Viewer, chatId: Long, body: NewRequestBody): ApiResult<MessageDto>
    suspend fun me(viewer: Viewer): ApiResult<MeView>
    suspend fun browse(viewer: Viewer): ApiResult<BrowseView>
    suspend fun state(viewer: Viewer, body: NewInterestBody): ApiResult<MessageDto>
    suspend fun cancel(viewer: Viewer, token: String): ApiResult<MessageDto>
    suspend fun done(viewer: Viewer, body: DoneBody): ApiResult<MessageDto>
    suspend fun confirm(viewer: Viewer, body: AnswerBody): ApiResult<MessageDto>
    suspend fun refuse(viewer: Viewer, body: AnswerBody): ApiResult<MessageDto>
    suspend fun setTolerance(viewer: Viewer, pct: Int): ApiResult<MeView>
}

/** Where outcomes are told — the same messages the commands send (Delivery.kt). */
interface DeliveryPort {
    suspend fun posted(chatId: Long, result: PostResult.Posted)
    suspend fun stated(userId: Long, result: InterestResult.Stated)
    suspend fun decision(chatId: Long, result: ActionResult)
    suspend fun refused(declarerToken: String, mineToken: String)
}

private fun BigDecimal.wire(): String = setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

class ServiceBackend(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val people: PersonSettingsRepository,
    private val rates: RateService,
    private val service: RequestService,
    private val interests: InterestService,
    private val lifecycle: LifecycleService,
    private val messages: MessageLogRepository,
    private val names: NameLookup,
    private val titles: suspend (Long) -> String?,
    private val delivery: DeliveryPort,
) : MiniAppBackend {

    override suspend fun chat(viewer: Viewer, chatId: Long): ApiResult<ChatView> {
        val s = settings.get(chatId)
        val status = rates.status(s.pair)
        val resting = requests.resting(chatId)
        val book = nameBookFor(resting, names)
        val cards = resting.sortedByDescending { it.createdAt }.map { r ->
            val mine = r.userId == viewer.userId
            card(r, status.rate, plainName(r, book).let { if (r.username != null) "@$it" else it }, mine).copy(
                token = r.refToken.takeIf { mine },
                counterparties = if (mine) counterpartiesOf(r) else emptyList(),
                range = if (mine) null else rangeOf(r, status.rate, s.tolerancePct, s.tolerancePct),
            )
        }
        return ApiResult.Ok(
            ChatView(chatId, titles(chatId), s.pair.base, s.pair.quote, status.rate?.toPlainString(), status is RateStatus.Stale, cards),
        )
    }

    override suspend fun postInChat(viewer: Viewer, chatId: Long, body: NewRequestBody): ApiResult<MessageDto> =
        when (val r = service.post(chatId, viewer.userId, viewer.username, body.says.verb(), body.amount, body.currency)) {
            is PostResult.Rejected -> ApiResult.Refused(r.reason)
            is PostResult.Posted -> { delivery.posted(chatId, r); ApiResult.Ok(MessageDto("Posted.")) }
        }

    override suspend fun state(viewer: Viewer, body: NewInterestBody): ApiResult<MessageDto> =
        when (val r = interests.state(viewer.userId, viewer.username, body.says.verb(), body.amount, body.currency, body.other)) {
            is InterestResult.Rejected -> ApiResult.Refused(r.reason)
            is InterestResult.Stated -> { delivery.stated(viewer.userId, r); ApiResult.Ok(MessageDto("Posted.")) }
        }

    override suspend fun me(viewer: Viewer): ApiResult<MeView> {
        val open = requests.openFor(viewer.userId)
        val pending = open.flatMap { mine -> messages.openAsksFor(mine.refToken) }.distinct().mapNotNull { (d, m) ->
            val declarer = requests.byRefToken(d)?.takeIf { it.state == RequestState.OPEN } ?: return@mapNotNull null
            val name = nameBookFor(listOf(declarer), names).let { plainName(declarer, it) }
            PendingDto(d, m, name, saysOf(declarer), declarer.statedAmount.wire(), declarer.statedCurrency,
                otherLeg(declarer.pair, declarer.statedCurrency))
        }
        val mine = interests.standings(viewer.userId).map { st ->
            val i = st.interest
            val rate = rates.status(i.pair).rate
            val scopes = listOf(i) + i.interestToken?.let { requests.siblings(it) }.orEmpty()
                .filter { it.state == RequestState.OPEN && it.chatId != NO_CHAT_ID }
            MineDto(
                i.refToken, saysOf(i), i.statedAmount.wire(), i.statedCurrency, otherLeg(i.pair, i.statedCurrency),
                approxOther(i, rate)?.wire(), i.pair.base, i.pair.quote,
                st.chatIds.map { ChatRef(it, titles(it)) },
                scopes.flatMap { counterpartiesOf(it) },
                i.expiresAt.epochSecond,
            )
        }
        return ApiResult.Ok(MeView(people.get(viewer.userId).tolerancePct, MAX_RESTING_INTERESTS, pending, mine))
    }

    override suspend fun browse(viewer: Viewer): ApiResult<BrowseView> {
        val minePct = people.get(viewer.userId).tolerancePct
        val rows = requests.resting(NO_CHAT_ID).filter { it.userId != viewer.userId }
        val rateOf = rows.map { it.pair }.distinct().associateWith { rates.status(it).rate }
        val cards = rows.sortedByDescending { it.createdAt }.map { r ->
            val rate = rateOf[r.pair]
            BrowseCard(
                r.pair.base, r.pair.quote, saysOf(r), r.statedAmount.wire(), r.statedCurrency,
                otherLeg(r.pair, r.statedCurrency), approxOther(r, rate)?.wire(),
                r.createdAt.epochSecond, r.expiresAt.epochSecond,
                rangeOf(r, rate, people.get(r.userId).tolerancePct, minePct),
            )
        }
        val rateMap = rateOf.mapNotNull { (p, v) -> v?.let { "${p.base}/${p.quote}" to it.toPlainString() } }.toMap()
        return ApiResult.Ok(BrowseView(cards, rateMap))
    }

    override suspend fun cancel(viewer: Viewer, token: String): ApiResult<MessageDto> =
        decide(requests.byRefToken(token)) { lifecycle.cancelByToken(viewer.userId, token) }(viewer)

    override suspend fun done(viewer: Viewer, body: DoneBody): ApiResult<MessageDto> =
        decide(requests.byRefToken(body.mineToken)) { lifecycle.doneWithRow(viewer.userId, body.mineToken, body.peerShortId) }(viewer)

    override suspend fun confirm(viewer: Viewer, body: AnswerBody): ApiResult<MessageDto> =
        decide(requests.byRefToken(body.mineToken)) { lifecycle.confirm(viewer.userId, body.declarerToken, body.mineToken) }(viewer)

    override suspend fun refuse(viewer: Viewer, body: AnswerBody): ApiResult<MessageDto> {
        val result = lifecycle.refuse(viewer.userId, body.declarerToken, body.mineToken)
        if (result is ActionResult.Ok) delivery.refused(body.declarerToken, body.mineToken)
        return answer(result)
    }

    override suspend fun setTolerance(viewer: Viewer, pct: Int): ApiResult<MeView> {
        if (parseTolerancePct(pct.toString()) == null) return ApiResult.Refused(TOLERANCE_HELP)
        people.save(people.get(viewer.userId).copy(tolerancePct = pct))
        return me(viewer)
    }

    /**
     * Runs [act] and tells the outcome where the acting row rests: its group, or the person's
     * own chat with the bot for a privately stated interest — where they would have typed it.
     * Refusals are not delivered; the app shows them.
     */
    private fun decide(row: Request?, act: suspend () -> ActionResult): suspend (Viewer) -> ApiResult<MessageDto> = { viewer ->
        val result = act()
        if (result is ActionResult.Ok || result is ActionResult.Asked) {
            val chatId = row?.chatId?.takeIf { it != NO_CHAT_ID } ?: viewer.userId
            delivery.decision(chatId, result)
        }
        answer(result)
    }

    private fun answer(result: ActionResult): ApiResult<MessageDto> = when (result) {
        is ActionResult.Ok, is ActionResult.Asked -> ApiResult.Ok(MessageDto(plainText(result.text)))
        else -> ApiResult.Refused(plainText(result.text))
    }

    private suspend fun counterpartiesOf(mine: Request): List<CounterpartyDto> {
        val found = interests.counterparties(mine).map { it.request }
        val book = nameBookFor(found, names)
        return found.map { p ->
            CounterpartyDto(p.shortId, plainName(p, book).let { if (p.username != null) "@$it" else it }, saysOf(p),
                p.statedAmount.wire(), p.statedCurrency, mine.refToken, mine.chatId,
                if (mine.chatId == NO_CHAT_ID) null else titles(mine.chatId))
        }
    }

    private fun card(r: Request, rate: BigDecimal?, name: String, mine: Boolean) = CardDto(
        r.shortId, mine, saysOf(r), r.statedAmount.wire(), r.statedCurrency, otherLeg(r.pair, r.statedCurrency),
        approxOther(r, rate)?.wire(), name, r.createdAt.epochSecond, r.expiresAt.epochSecond,
    )

    /** Null when the size cannot be known in the base currency — a quote amount with no rate. */
    private fun rangeOf(r: Request, rate: BigDecimal?, theirPct: Int, minePct: Int): RangeDto? {
        val n = notional(r, rate) ?: return null
        val range = acceptedRange(n, theirPct, minePct)
        return RangeDto(range.min.wire(), range.max?.wire())
    }
}
