package io.airbyte.cron.config

import dev.failsafe.RetryPolicy
import io.airbyte.api.client.WorkloadApiClient
import io.airbyte.commons.auth.AuthenticationInterceptor
import io.airbyte.commons.temporal.config.WorkerMode
import io.airbyte.workload.api.client.generated.WorkloadApi
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Value
import io.micronaut.context.env.Environment
import jakarta.inject.Named
import jakarta.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Response
import org.openapitools.client.infrastructure.ClientException
import org.openapitools.client.infrastructure.ServerException
import java.io.IOException
import java.time.Duration
import java.util.Optional

private val logger = KotlinLogging.logger {}

@Factory
class ApiBeanFactory {
  @Singleton
  fun workloadApiClient(
    @Value("\${airbyte.workload-api.base-path}") workloadApiBasePath: String,
    @Value("\${airbyte.workload-api.connect-timeout-seconds}") connectTimeoutSeconds: Long,
    @Value("\${airbyte.workload-api.read-timeout-seconds}") readTimeoutSeconds: Long,
    @Value("\${airbyte.workload-api.retries.delay-seconds}") retryDelaySeconds: Long,
    @Value("\${airbyte.workload-api.retries.max}") maxRetries: Int,
    authenticationInterceptor: AuthenticationInterceptor,
    meterRegistry: Optional<MeterRegistry>,
  ): WorkloadApi {
    val builder: OkHttpClient.Builder = OkHttpClient.Builder()
    builder.addInterceptor(authenticationInterceptor)
    builder.readTimeout(Duration.ofSeconds(readTimeoutSeconds))
    builder.connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))

    val okHttpClient: OkHttpClient = builder.build()
    val metricTags = arrayOf("max-retries", maxRetries.toString())

    val retryPolicy: RetryPolicy<Response> =
      RetryPolicy.builder<Response>()
        .handle(
          listOf(
            IllegalStateException::class.java,
            IOException::class.java,
            UnsupportedOperationException::class.java,
            ClientException::class.java,
            ServerException::class.java,
          ),
        )
        // TODO move these metrics into a centralized metric registery as part of the MetricClient refactor/cleanup
        .onAbort { l ->
          logger.warn { "Attempt aborted.  Attempt count ${l.attemptCount}" }
          meterRegistry.ifPresent { r ->
            r.counter(
              "workload_api_client.abort",
              *metricTags,
              *arrayOf("retry-attempt", l.attemptCount.toString(), "url", l.result.request.url.toString(), "method", l.result.request.method),
            ).increment()
          }
        }
        .onFailure { l ->
          logger.error(l.exception) { "Failed to call ${l.result.request.url}.  Last response: ${l.result}" }
          meterRegistry.ifPresent { r ->
            r.counter(
              "workload_api_client.failure",
              *metricTags,
              *arrayOf("retry-attempt", l.attemptCount.toString(), "url", l.result.request.url.toString(), "method", l.result.request.method),
            ).increment()
          }
        }
        .onRetry { l ->
          logger.warn { "Retry attempt ${l.attemptCount} of $maxRetries. Last response: ${l.lastResult}" }
          meterRegistry.ifPresent { r ->
            r.counter(
              "workload_api_client.retry",
              *metricTags,
              *arrayOf("retry-attempt", l.attemptCount.toString(), "url", l.lastResult.request.url.toString(), "method", l.lastResult.request.method),
            ).increment()
          }
        }
        .onRetriesExceeded { l ->
          logger.error(l.exception) { "Retry attempts exceeded." }
          meterRegistry.ifPresent { r ->
            r.counter(
              "workload_api_client.retries_exceeded",
              *metricTags,
              *arrayOf("retry-attempt", l.attemptCount.toString(), "url", l.result.request.url.toString(), "method", l.result.request.method),
            ).increment()
          }
        }
        .onSuccess { l ->
          logger.debug { "Successfully called ${l.result.request.url}.  Response: ${l.result}, isRetry: ${l.isRetry}" }
          meterRegistry.ifPresent { r ->
            r.counter(
              "workload_api_client.success",
              *metricTags,
              *arrayOf("retry-attempt", l.attemptCount.toString(), "url", l.result.request.url.toString(), "method", l.result.request.method),
            ).increment()
          }
        }
        .withDelay(Duration.ofSeconds(retryDelaySeconds))
        .withMaxRetries(maxRetries)
        .build()

    return WorkloadApiClient(workloadApiBasePath, retryPolicy, okHttpClient).workloadApi
  }

  @Singleton
  @Named("internalApiScheme")
  fun internalApiScheme(environment: Environment): String {
    return if (environment.activeNames.contains(WorkerMode.CONTROL_PLANE)) "http" else "https"
  }
}
