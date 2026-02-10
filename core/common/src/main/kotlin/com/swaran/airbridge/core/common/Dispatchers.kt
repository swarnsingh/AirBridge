package com.swaran.airbridge.core.common

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: AirDispatchers)

enum class AirDispatchers {
    IO,
    Default,
    Main,
    Unconfined
}
