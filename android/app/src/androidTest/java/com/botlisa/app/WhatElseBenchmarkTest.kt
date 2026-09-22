package com.botlisa.app

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * On-device latency benchmark for [OnDeviceLlm.generateWhatElse] -- a
 * repeatable measurement so a future prompt, predict-length, or model
 * change can be checked against real numbers instead of a guess. This is
 * a real device test (needs the actual model + native libs + Context), not
 * a JVM unit test, hence living under src/androidTest rather than src/test
 * alongside PromptComposerTest.
 *
 * Needs the on-device model already downloaded on the target device
 * (Settings -> "What else?" suggestions -> Download model); skips itself
 * (does not fail) otherwise, via [assumeTrue], so a normal
 * connectedAndroidTest pass on a machine without the model still goes
 * green.
 *
 * THERMAL WARNING, found by running this test twice back-to-back: 20 heavy
 * LLM inferences in ~100s with no gap is a sustained-load stress test, not
 * what hands-free "what else?" usage looks like (one request, then the
 * caregiver keeps talking/listening for a while). A cool-device run on a
 * Pixel 11 measured mean=3.9s; immediately re-running it pushed
 * PowerManager.currentThermalStatus from NONE to LIGHT (soc_therm 41.7C ->
 * 46.3C) and the *same* 20 phrases came back at mean=9.6s -- 2.5x slower,
 * from throttling the benchmark itself induced. [INTER_CALL_DELAY_MS] adds
 * a gap between calls to keep the device from cooking itself, and each
 * sample records the thermal status so a report you're comparing against a
 * baseline is honest about whether the device was actually cool. Let the
 * device sit idle a few minutes between runs; [assumeTrue] below refuses to
 * start a run already above NONE so a warm re-run doesn't silently produce
 * a worse-looking number for an unrelated reason.
 *
 * Run against a connected/authorized device:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.botlisa.app.WhatElseBenchmarkTest
 *
 * Prints a summary to stdout/logcat (tag "WhatElseBenchmark") and writes a
 * full per-phrase JSON report to
 * <app-external-files-dir>/benchmarks/what_else_benchmark_<timestamp>.json
 * -- `adb pull` that file to keep a copy (see ON_DEVICE_LLM_PLAN.md).
 */
@RunWith(AndroidJUnit4::class)
class WhatElseBenchmarkTest {

    // 20 real caregiver_infant phrases, not synthetic filler: the full
    // 15-entry curated library (phrase_library/phrases.json) plus 5 more
    // from the llm_lab eval cases and few-shot example files -- content
    // this feature actually sees in practice.
    private val samplePhrases = listOf(
        // phrase_library/phrases.json (p001-p015)
        "Пойдём кушать!",
        "Открой ротик.",
        "Вкусно?",
        "Пора спать, малыш.",
        "Закрывай глазки.",
        "Спокойной ночи.",
        "Давай мыть ручки.",
        "Тёплая водичка, да?",
        "Молодец!",
        "Умница какой!",
        "Пойдём гулять.",
        "Какая красивая машинка!",
        "Дай мне ручку.",
        "Смотри, киса!",
        "Ты хочешь пить?",
        // llm_lab/eval/cases/what_else_caregiver_infant.json
        "Давай кушать",
        "Залезай в ванночку.",
        // llm_lab/prompts/examples/what_else.ru.caregiver_infant.json
        "Давай наденем твою пижамку.",
        "Давай почитаем твою любимую книжку.",
        // llm_lab/prompts/examples/how_to_respond.ru.caregiver_infant.json
        "Хочешь ещё?",
    )

    private val languageCode = SupportedLanguages.RUSSIAN.code

    /**
     * Single-call PSS footprint of [OnDeviceLlm.generateWhatElse] -- found
     * (2026-09-11, Pixel 11) that the LLM alone already runs at ~3GB, right
     * at the device's own memory.high cgroup ceiling: idle ~147MB -> ~3004MB
     * right after LLM generation. A single call isn't fatal (memory.high is
     * a soft/throttling signal, not an instant kill), but
     * [benchmarkGenerateWhatElse]'s 20 back-to-back calls got this process
     * killed outright by the OS after staying over that line for ~90s. See
     * android/app/benchmarks/README.md.
     *
     * Gloss translation used to run inside generateWhatElse itself (adding
     * ~90MB on top, per that README's older numbers) but now happens
     * separately, one phrase at a time, after the caller has already shown
     * the phrases -- see OnDeviceLlm.generateWhatElse's doc comment -- so
     * this test's PSS delta no longer includes it.
     */
    @Test
    fun checkMemoryFootprint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Skipping: on-device model not downloaded/ready on this device.",
            OnDeviceLlm.canGenerate(context),
        )

        fun pssMb() = Debug.getPss() / 1024

        val beforeMb = pssMb()
        val phrases = OnDeviceLlm.generateWhatElse(context, samplePhrases.first(), languageCode)
        val afterMb = pssMb()

        val summary = "PSS before=${beforeMb}MB after=${afterMb}MB (delta=${afterMb - beforeMb}MB), " +
            "${phrases.size} phrases generated"
        Log.i(TAG, summary)
        println(summary)
        phrases.forEach { Log.i(TAG, "ru=\"${it.ru}\" glossEn=\"${it.glossEn}\"") }
    }

    @Test
    fun benchmarkGenerateWhatElse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assumeTrue(
            "Skipping: on-device model not downloaded/ready on this device " +
                "(Settings -> \"What else?\" suggestions -> Download model).",
            OnDeviceLlm.canGenerate(context),
        )
        assumeTrue(
            "Skipping: device is already thermally throttled (status=${thermalStatus(context)}) -- " +
                "let it cool (idle, screen off, a few minutes) before benchmarking, or results will " +
                "read slower than a real cool-device request.",
            thermalStatus(context) == PowerManager.THERMAL_STATUS_NONE,
        )

        // Warm up once, outside the timed loop -- model load + system-prompt
        // processing is a one-time ~10s cost per OnDeviceLlm.warmUp's own
        // doc comment, a different thing to tune than per-call generation
        // latency, and would otherwise dominate/skew the first sample.
        OnDeviceLlm.warmUp(context, languageCode)

        val samples = samplePhrases.mapIndexed { i, phrase ->
            if (i > 0) delay(INTER_CALL_DELAY_MS)
            val startNanos = System.nanoTime()
            val outcome = runCatching { OnDeviceLlm.generateWhatElse(context, phrase, languageCode) }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
            val phraseCount = outcome.getOrNull()?.size ?: -1
            val thermal = thermalStatus(context)
            Log.i(
                TAG,
                String.format(Locale.US, "[%6.0fms] phrases=%d thermal=%d  \"%s\"", elapsedMs, phraseCount, thermal, phrase),
            )
            Sample(phrase, elapsedMs, phraseCount, thermal, outcome.exceptionOrNull()?.message)
        }

        val durations = samples.map { it.elapsedMs }
        val mean = durations.average()
        // Sample standard deviation (n-1) -- 20 phrases is a sample of the
        // feature's latency distribution, not the whole population of it.
        val stdDev = sqrt(durations.sumOf { (it - mean) * (it - mean) } / (durations.size - 1))
        val min = durations.min()
        val max = durations.max()
        val failureCount = samples.count { it.error != null }
        val maxThermal = samples.maxOf { it.thermalStatus }

        val summary = String.format(
            Locale.US,
            "generateWhatElse over %d phrases on %s: mean=%.0fms std=%.0fms min=%.0fms max=%.0fms " +
                "failures=%d maxThermalStatus=%d (0=NONE)",
            samples.size, Build.MODEL, mean, stdDev, min, max, failureCount, maxThermal,
        )
        Log.i(TAG, summary)
        println(summary)
        if (maxThermal > PowerManager.THERMAL_STATUS_NONE) {
            println(
                "WARNING: thermal status rose above NONE during this run -- later samples likely " +
                    "ran throttled; treat this run's mean as a worst-case, not a typical single-request one.",
            )
        }

        val reportFile = writeReport(context, samples, mean, stdDev, min, max)
        Log.i(TAG, "Full report: ${reportFile.absolutePath}")
        println("Full report: ${reportFile.absolutePath}")
    }

    /** [PowerManager.getCurrentThermalStatus] needs API 29+; this project's
     * minSdk is 24, so fall back to NONE (can't observe it) below that --
     * the on-device LLM feature itself is already gated to API 33+
     * (OnDeviceLlm.isDeviceCapable), so in practice this is always
     * available when this test's assumeTrue above lets it run. */
    private fun thermalStatus(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

    private data class Sample(
        val phrase: String,
        val elapsedMs: Double,
        val phraseCount: Int,
        val thermalStatus: Int,
        val error: String?,
    )

    private fun writeReport(
        context: Context,
        samples: List<Sample>,
        mean: Double,
        stdDev: Double,
        min: Double,
        max: Double,
    ): File {
        val report = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            put("device", Build.MODEL)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("languageCode", languageCode)
            put("interCallDelayMs", INTER_CALL_DELAY_MS)
            put("sampleCount", samples.size)
            put("meanMs", mean)
            put("stdDevMs", stdDev)
            put("minMs", min)
            put("maxMs", max)
            put("maxThermalStatus", samples.maxOf { it.thermalStatus })
            put(
                "samples",
                JSONArray(
                    samples.map { s ->
                        JSONObject().apply {
                            put("phrase", s.phrase)
                            put("elapsedMs", s.elapsedMs)
                            put("phraseCount", s.phraseCount)
                            put("thermalStatus", s.thermalStatus)
                            s.error?.let { put("error", it) }
                        }
                    },
                ),
            )
        }
        val dir = File(context.getExternalFilesDir(null), "benchmarks").apply { mkdirs() }
        val file = File(dir, "what_else_benchmark_${System.currentTimeMillis()}.json")
        file.writeText(report.toString(2))
        return file
    }

    companion object {
        private const val TAG = "WhatElseBenchmark"

        // Gap between calls -- long enough that 20 consecutive inferences
        // don't read as one continuous stress test to the SoC. Not a
        // guarantee against throttling by itself (see the thermal warning
        // above), just less bad than hammering it with zero gap.
        private const val INTER_CALL_DELAY_MS = 4000L
    }
}
