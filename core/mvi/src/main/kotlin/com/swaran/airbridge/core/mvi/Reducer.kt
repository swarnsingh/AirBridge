package com.swaran.airbridge.core.mvi

typealias Reducer<I, S> = (state: S, intent: I) -> S
