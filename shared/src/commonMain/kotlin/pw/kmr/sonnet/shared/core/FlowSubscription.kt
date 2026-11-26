package pw.kmr.sonnet.shared.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Handle returned to native consumers (such as SwiftUI stores) to stop observing a Kotlin [Flow].
 */
class FlowSubscription(
    private val job: Job
) {
    val isActive: Boolean
        get() = job.isActive

    fun cancel() {
        job.cancel()
    }
}

/**
 * Collects [this] flow on [dispatcher] and invokes [onEach] for every emitted value until the
 * returned [FlowSubscription] is cancelled.
 */
fun <T> Flow<T>.subscribe(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    onEach: (T) -> Unit
): FlowSubscription {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val job = scope.launch {
        collect { value ->
            onEach(value)
        }
    }
    return FlowSubscription(job)
}
