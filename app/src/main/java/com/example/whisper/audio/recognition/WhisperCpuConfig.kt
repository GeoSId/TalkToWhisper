package com.example.whisper.audio.recognition

/**
 * CPU thread configuration for whisper.cpp inference.
 *
 * On mobile devices, using too many threads can actually slow down
 * inference due to thermal throttling and scheduling overhead.
 * The sweet spot for whisper.cpp is typically 2–4 threads on
 * most ARM SoCs.
 */
object WhisperCpuConfig {

    /**
     * Preferred number of CPU threads for whisper.cpp inference.
     *
     * Uses a heuristic: half the available processors, clamped to [2, 4].
     * This avoids saturating big.LITTLE cores while still using NEON
     * parallelism effectively.
     */
    val preferredThreadCount: Int
        get() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
}
