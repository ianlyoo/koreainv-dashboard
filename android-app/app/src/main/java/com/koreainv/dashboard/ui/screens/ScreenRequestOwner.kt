package com.koreainv.dashboard.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Confined to the screen's UI coroutine context; never owns repository caches. */
internal class ScreenRequestOwner {
    var version: Long = 0
        private set
    private var job: Job? = null

    fun accepts(requestVersion: Long): Boolean = version == requestVersion

    fun cancel() {
        version += 1
        job?.cancel()
        job = null
    }

    fun <T> launch(
        scope: CoroutineScope,
        load: suspend (requestVersion: Long) -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit,
        onFinished: () -> Unit,
    ) {
        cancel()
        val requestVersion = version
        job = scope.launch {
            try {
                val result = load(requestVersion)
                currentCoroutineContext().ensureActive()
                if (accepts(requestVersion)) onSuccess(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (accepts(requestVersion)) onFailure(error)
            } finally {
                if (accepts(requestVersion)) onFinished()
            }
        }
    }
}
