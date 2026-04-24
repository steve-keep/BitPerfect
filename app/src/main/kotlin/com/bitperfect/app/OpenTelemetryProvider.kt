package com.bitperfect.app

import android.app.Application
import android.util.Log
import com.bitperfect.core.utils.AppLogger
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter

object OpenTelemetryProvider {
    private const val TAG = "OpenTelemetryProvider"
    private const val INSTRUMENTATION_SCOPE_NAME = "BitPerfect"

    private var rum: OpenTelemetryRum? = null

    fun initialize(application: Application) {
        val endpointUrl = BuildConfig.OTLP_ENDPOINT
        val authToken = BuildConfig.OTLP_AUTH_TOKEN

        if (endpointUrl.isEmpty() || authToken.isEmpty()) {
            Log.d(TAG, "OTLP Endpoint or Auth Token is empty. Disabling OpenTelemetry.")
            setupAppLoggerFallback()
            return
        }

        val authHeaders = mapOf("Authorization" to "Basic $authToken")
        val tracesUrl = "$endpointUrl/v1/traces"
        val logsUrl = "$endpointUrl/v1/logs"

        try {
            rum = OpenTelemetryRum.builder(application)
                .addLogRecordExporterCustomizer {
                    OtlpHttpLogRecordExporter.builder()
                        .setEndpoint(logsUrl)
                        .setHeaders { authHeaders }
                        .build()
                }
                .addSpanExporterCustomizer {
                    OtlpHttpSpanExporter.builder()
                        .setEndpoint(tracesUrl)
                        .setHeaders { authHeaders }
                        .build()
                }
                .build()

            Log.d(TAG, "RUM initialization success. RUM session ID: " + rum?.rumSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "RUM initialization failure", e)
        }

        setupAppLoggerIntegration()
    }

    private fun setupAppLoggerIntegration() {
        AppLogger.logCallback = { tag, message, level, throwable ->
            val severity = when (level) {
                Log.DEBUG -> Severity.DEBUG
                Log.INFO -> Severity.INFO
                Log.ERROR -> Severity.ERROR
                Log.WARN -> Severity.WARN
                else -> Severity.DEBUG
            }

            val logMessage = if (throwable != null) {
                "[$tag] $message\n${Log.getStackTraceString(throwable)}"
            } else {
                "[$tag] $message"
            }

            sendLog(logMessage, severity)
        }
    }

    private fun setupAppLoggerFallback() {
        AppLogger.logCallback = null
    }

    private val logger: Logger?
        get() = rum?.openTelemetry?.logsBridge?.get(INSTRUMENTATION_SCOPE_NAME)

    private fun sendLog(message: String, severity: Severity = Severity.DEBUG) {
        logger?.logRecordBuilder()
            ?.setBody(message)
            ?.setSeverity(severity)
            ?.emit()
    }

    private val tracer: Tracer?
        get() = rum?.openTelemetry?.tracerProvider?.get(INSTRUMENTATION_SCOPE_NAME)

    fun startSpan(name: String) = tracer?.spanBuilder(name)?.startSpan()
}
