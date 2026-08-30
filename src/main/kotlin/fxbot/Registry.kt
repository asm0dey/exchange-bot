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
    lateinit var messages: MessageLogRepository
    lateinit var buttons: ButtonService
    lateinit var forget: ForgetService
    lateinit var admin: AdminService
    lateinit var migration: ChatMigrationService
}
