package com.knk.manyak.notification.global.error

import java.util.Collections
import java.util.IdentityHashMap

/** 원문 메시지는 읽지 않고 클래스와 원래 발생 위치만 복사한다. */
internal fun sanitizedThrowable(source: Throwable): Throwable {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var remaining = 64
    fun copy(current: Throwable, depth: Int): Throwable {
        if (depth >= 16 || remaining-- <= 0 || !seen.add(current)) {
            return RuntimeException("[exception graph truncated]").apply { stackTrace = emptyArray() }
        }
        return RuntimeException(current.javaClass.name).apply {
            stackTrace = current.stackTrace
            current.cause?.let { initCause(copy(it, depth + 1)) }
            current.suppressed.take(16).forEach { addSuppressed(copy(it, depth + 1)) }
        }
    }
    return copy(source, 0)
}
