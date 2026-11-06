package neth.iecal.curbox.data.models

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException

//You can setup a custom message to appear here!
data class AppBlockerWarningScreenConfig(
    @field:JsonAdapter(WarningMessagesAdapter::class)
    val message: List<String> = listOf("Message 1", "Message 2"),
    val timeInterval: Long = 120000L, // default cooldown period
    val showUnlockCountdown: Boolean = false,
    val isDynamicIntervalSettingAllowed: Boolean = false,
    val isProceedDisabled: Boolean = false,
    val isWarningDialogHidden: Boolean = false, // perform back/home action directly without showing warning screen
    val proceedDelayInSecs: Int = 15,
    val vibrateAndIncBrightness: Boolean = false,
    val proceedLimitEnabled: Boolean = false,
    val allowedProceeds: Int = 3,
    val proceedsTimeWindowMn: Long = 60L,
    val isQrUnlockRequirementEnabled: Boolean = false,
    val qrKeys: Map<String,Long> = mapOf(), // qr code content -> Duration of unlock (-1 if dynamic timing)
    val isNfcUnlockRequirementEnabled: Boolean = false,
    val nfcKeys: Map<String,Long> = mapOf(), // nfc tag key (written UUID or UID) -> Duration of unlock (-1 if dynamic timing)
    val isTypingRequirementEnabled: Boolean = false,
    val typingSentence: String = "",
    val isIntentRequirementEnabled: Boolean = false,
    val minIntentLength: Int = 1,
    val isAdaptiveMathRequirementEnabled: Boolean = false,
    val adaptiveMathQuestionCount: Int = 3,
    val adaptiveMathStartingLevel: Int = 3,
    val isFocusGoalRequirementEnabled: Boolean = false,
    val focusGoalGroupId: String = "",
    val focusGoalRequiredMinutes: Int = 60,
    /** For app groups, grant access only for the current selected-app session. */
    val isOnOpenConfig: Boolean = false,
    val externalUnlockCondition: ExternalUnlockCondition? = null,
)

class WarningMessagesAdapter : TypeAdapter<List<String>>() {
    override fun read(reader: JsonReader): List<String> {
        // V4 stored one message. Keep it when loading settings, sync, or pending changes.
        if (reader.peek() == JsonToken.STRING) return listOf(reader.nextString())
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw IOException("Expected warning message string or array at ${reader.path}")
        }
        val messages = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.STRING) {
                throw IOException("Expected warning message string at ${reader.path}")
            }
            messages.add(reader.nextString())
        }
        reader.endArray()
        return messages
    }

    override fun write(writer: JsonWriter, messages: List<String>) {
        writer.beginArray()
        messages.forEach { writer.value(it) }
        writer.endArray()
    }
}
