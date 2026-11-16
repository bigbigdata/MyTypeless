package com.typeless.ime.engine

/**
 * WhisperHallucinationFilter
 *
 * Deterministically strips notorious OpenAI/Groq Whisper (`whisper-large-v3-turbo`) trailing-silence
 * subtitle hallucinations in Traditional & Simplified Chinese (such as "謝謝大家", "謝謝你", "謝謝觀看",
 * "感謝收看", "字幕由 Amara.org 社群提供") without harming genuine standalone "謝謝你" / "謝謝大家" replies.
 */
object WhisperHallucinationFilter {

    /**
     * Pure subtitle/watermark hallucinations that should ALWAYS be stripped even if standalone.
     */
    private val pureWatermarkRegex = Regex(
        "^[，,。！？!?\\s]*(字幕由.*?(提供|製作|制作)|Amara\\.org.*|謝謝觀看|谢谢观看|感謝收看|感谢收看|感謝觀看|感谢观看|謝謝大家的收看|谢谢大家的收看|謝謝大家的觀看|谢谢大家的观看|請訂閱按讚|请订阅按赞)[。！？!?~～\\s]*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Trailing hallucination phrases that Whisper appends during the 0.5s-2.0s silence before the user
     * taps the Stop button. Stripped ONLY when preceded by actual speech content.
     */
    private val trailingHallucinationRegex = Regex(
        "^(.+?)[，,。；;！？!?\\s]+(謝謝大家|谢谢大家|謝謝你|谢谢你|謝謝您|谢谢您|謝謝觀看|谢谢观看|感謝收看|感谢收看|感謝觀看|感谢观看|謝謝大家的收看|谢谢大家的收看|謝謝大家的觀看|谢谢大家的觀看|感謝大家的收看|請訂閱按讚|请订阅按赞|歡迎訂閱|欢迎订阅|字幕由.*?(?:提供|製作|制作)|Subtitles by.*)[。！？!?~～\\s]*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strips trailing Whisper hallucinations while preserving genuine standalone thank-you utterances.
     */
    fun stripTrailingHallucinations(rawText: String): String {
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        if (pureWatermarkRegex.matches(text)) {
            return ""
        }

        var prev = ""
        while (text != prev) {
            prev = text
            val match = trailingHallucinationRegex.matchEntire(text)
            if (match != null) {
                val prefix = match.groupValues[1].trim()
                // Only strip if the preceding content is a real clause (not just a filler word like "呃" or "那個")
                val meaningfulPrefix = prefix.replace(Regex("^[，,。？?\\s]*(呃|啊|那個|就是說|然後其實|嗯)+[，,。？?\\s]*"), "").trim()
                if (meaningfulPrefix.length >= 4) {
                    text = prefix
                }
            }
        }

        return text.trim()
    }
}
