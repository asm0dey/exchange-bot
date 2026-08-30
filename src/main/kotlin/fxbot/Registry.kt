package fxbot

/**
 * Process-global wiring, set once in main(). The framework invokes top-level
 * handler functions, so they need a way to reach their dependencies.
 */
object Registry {
    lateinit var requests: RequestRepository
    lateinit var settings: ChatSettingsRepository
    lateinit var rates: RateService
    lateinit var service: RequestService
    lateinit var lifecycle: LifecycleService
    lateinit var buttons: ButtonService
}
