package live.sutra.data

import com.androidengineers.agent_quickstart_android.config.QuickstartConfig
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

class SutraApi(baseUrl: String = QuickstartConfig.backendBaseUrl) {

    private val service: SutraService = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SutraService::class.java)

    suspend fun reportFieldMessage(
        channelName: String,
        fromUid: Int,
        language: String,
        text: String,
        urgency: Int,
        targetLanguage: String = "en",
    ): FieldReportResult = service.report(
        ReportRequest(
            channelName = channelName,
            fromUid = fromUid,
            language = language,
            text = text,
            urgency = urgency,
            targetLanguage = targetLanguage,
        )
    )

    suspend fun relayToField(
        channelName: String,
        text: String,
        language: String,
        urgent: Boolean,
    ): FieldReportResult = service.relay(
        RelayRequest(channelName = channelName, text = text, language = language, urgent = urgent)
    )

    suspend fun pendingOutbound(channelName: String, afterId: Int): List<OutboundMessage> =
        service.outbound(channelName, afterId).messages

    interface SutraService {
        @POST("v1/sutra/report")
        suspend fun report(@Body request: ReportRequest): FieldReportResult

        @POST("v1/sutra/relay")
        suspend fun relay(@Body request: RelayRequest): FieldReportResult

        @GET("v1/sutra/outbound")
        suspend fun outbound(
            @Query("channel_name") channelName: String,
            @Query("after_id") afterId: Int,
        ): OutboundResponse
    }

    data class ReportRequest(
        @SerializedName("channel_name") val channelName: String,
        @SerializedName("from_uid") val fromUid: Int,
        val language: String,
        val text: String,
        val urgency: Int,
        @SerializedName("target_language") val targetLanguage: String,
    )

    data class RelayRequest(
        @SerializedName("channel_name") val channelName: String,
        val text: String,
        val language: String,
        val urgent: Boolean,
        @SerializedName("source_language") val sourceLanguage: String = "en",
    )

    data class FieldReportResult(
        val id: Int,
        val translated: String,
        val urgency: Int,
        val vendor: String?,
        @SerializedName("latency_ms") val latencyMs: Int?,
    )

    data class OutboundResponse(val messages: List<OutboundMessage>)

    data class OutboundMessage(
        val id: Int,
        val language: String,
        val text: String,
        @SerializedName("source_text") val sourceText: String,
        val urgent: Boolean,
    )

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
