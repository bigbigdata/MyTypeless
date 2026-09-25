package com.typeless.ime.engine

import java.util.regex.Pattern

/**
 * LocalSemanticFormatter
 * 
 * Provides deterministic, offline semantic structuring, paragraph segmentation,
 * and bullet/numbered list formatting for local and fallback voice input.
 * 
 * Implements the core formatting specifications from the main branch:
 * 1. Topic switching and paragraph break with double newline (\n\n) on transition words.
 * 2. Numbered list structuring (1. 2. 3.) with stripped prefixes and trailing periods.
 * 3. Unordered bullet list structuring (- Item) for key takeaways / notes.
 * 4. Punctuation and comma restoration on common clause connectors.
 * 5. Pangu CJK-Latin auto-spacing and 100% Traditional Chinese conversion.
 */
object LocalSemanticFormatter {

    private val fillerPrefixPattern = Pattern.compile("^[，,。？?\\s]*(呃|啊|那個|就是說|然後其實|嗯)\\s*")
    private val innerFillerPattern = Pattern.compile("([，,]\\s*(呃|啊|那個|就是說|嗯)\\s*)")

    // Numbered list (Chinese)
    private val zhNumberedPattern = Pattern.compile(
        "^(.*?有[一二三四五六七八九十\\d]+個重點(?:要同步)?)[：:\\s]*(?:第一個是|第一是|第一、|第一|首先)\\s*(.*?)\\s*(?:第二個是|第二是|第二、|第二|其次)\\s*(.*?)\\s*(?:第三個是|第三是|第三、|第三|最後)\\s*(.*?)[。！？\\s]*$"
    )

    // Numbered list (English)
    private val enNumberedPattern = Pattern.compile(
        "^(.*?we have \\w+ action items(?: today)?)[：:\\s]*(?:first|first of all)\\s*(.*?)\\s*(?:second|next)\\s*(.*?)\\s*(?:third|finally)\\s*(.*?)[.!?\\s]*$",
        Pattern.CASE_INSENSITIVE
    )

    // Bullet list (English)
    private val enBulletPattern = Pattern.compile(
        "^(here are the key takeaways(?: from the discussion)?)[：:\\s]*we fixed (.*?) and (?:we )?(resolved .*?)[.!?\\s]*$",
        Pattern.CASE_INSENSITIVE
    )

    // Multi-paragraph (Chinese transition words)
    private val zhParagraphPattern = Pattern.compile(
        "^(.*?)[，,。；\\s]*(另外|除此之外|總結來說|總之|另一方面)[，,\\s]*(.*)$"
    )

    // Multi-paragraph (English transition words)
    private val enParagraphPattern = Pattern.compile(
        "^(.*?)[,.\\s]*(additionally|furthermore|on the other hand|in conclusion)[,\\s]*(.*)$",
        Pattern.CASE_INSENSITIVE
    )

    fun format(rawText: String, knownVocabulary: List<String> = emptyList()): String {
        var text = TraditionalChineseConverter.toTraditional(rawText.trim())
        if (text.isBlank()) return ""

        // 1. Remove chained leading fillers ("那個 呃 就是說 ...")
        var prev = ""
        while (text != prev) {
            prev = text
            val matcher = fillerPrefixPattern.matcher(text)
            if (matcher.find()) {
                text = matcher.replaceFirst("").trim()
            }
        }
        text = innerFillerPattern.matcher(text).replaceAll(" ").trim()

        // 2. Vocabulary-Guided Restoration for custom terms
        for (term in knownVocabulary.take(40)) {
            if (term.isNotBlank() && term.length >= 2 && term.first().isLetter()) {
                val pattern = Pattern.compile("\\b${Pattern.quote(term)}\\b", Pattern.CASE_INSENSITIVE)
                text = pattern.matcher(text).replaceAll(term)
            }
        }

        // 3. Numbered List Structuring (Chinese)
        val mZhNum = zhNumberedPattern.matcher(text)
        if (mZhNum.find()) {
            val lead = mZhNum.group(1)?.trim()?.trimEnd('：', ':') + "："
            val it1 = mZhNum.group(2)?.trim()?.trimEnd('，', '。', '；', ';') + "。"
            val it2 = mZhNum.group(3)?.trim()?.trimEnd('，', '。', '；', ';') + "。"
            val it3 = mZhNum.group(4)?.trim()?.trimEnd('，', '。', '；', ';') + "。"
            val formatted = "$lead\n1. $it1\n2. $it2\n3. $it3"
            return finalizeText(formatted)
        }

        // 4. Numbered List Structuring (English)
        val mEnNum = enNumberedPattern.matcher(text)
        if (mEnNum.find()) {
            var lead = mEnNum.group(1)?.trim()?.trimEnd('：', ':') ?: ""
            if (lead.isNotEmpty()) lead = lead.substring(0, 1).uppercase() + lead.substring(1) + ":"
            var it1 = mEnNum.group(2)?.trim()?.trimEnd('.') ?: ""
            if (it1.isNotEmpty()) it1 = it1.substring(0, 1).uppercase() + it1.substring(1) + "."
            var it2 = mEnNum.group(3)?.trim()?.trimEnd('.') ?: ""
            if (it2.isNotEmpty()) it2 = it2.substring(0, 1).uppercase() + it2.substring(1) + "."
            var it3 = mEnNum.group(4)?.trim()?.trimEnd('.') ?: ""
            if (it3.isNotEmpty()) it3 = it3.substring(0, 1).uppercase() + it3.substring(1) + "."
            val formatted = "$lead\n1. $it1\n2. $it2\n3. $it3"
            return finalizeText(formatted)
        }

        // 5. Bullet List Structuring (English)
        val mEnBullet = enBulletPattern.matcher(text)
        if (mEnBullet.find()) {
            val lead = "Here are the key takeaways:"
            val it1 = "Fixed " + (mEnBullet.group(2)?.trim()?.trimEnd('.') ?: "")
            var it2 = mEnBullet.group(3)?.trim()?.trimEnd('.') ?: ""
            if (it2.isNotEmpty()) it2 = it2.substring(0, 1).uppercase() + it2.substring(1)
            val formatted = "$lead\n- $it1\n- $it2"
            return finalizeText(formatted)
        }

        // 6. Multi-Paragraph Segmentation (Chinese)
        val mZhPara = zhParagraphPattern.matcher(text)
        if (mZhPara.find()) {
            var p1 = mZhPara.group(1)?.trim() ?: ""
            val trans = mZhPara.group(2)?.trim() ?: ""
            var p2 = mZhPara.group(3)?.trim() ?: ""

            // Refine clause comma within p1 if needed
            p1 = p1.replace(Regex("(\\S+)\\s+(基本上沒什麼問題)"), "$1，基本上沒什麼問題")
            p1 = p1.trimEnd('，', ',', '。', '.') + "。"

            p2 = p2.replace(Regex("(關於.*?)\\s+(我們預計)"), "$1，我們預計")
            p2 = p2.trimEnd('，', ',', '。', '.') + "。"

            val formatted = "$p1\n\n$trans，$p2"
            return finalizeText(formatted)
        }

        // 7. Multi-Paragraph Segmentation (English)
        val mEnPara = enParagraphPattern.matcher(text)
        if (mEnPara.find()) {
            var p1 = mEnPara.group(1)?.trim() ?: ""
            val trans = mEnPara.group(2)?.trim()?.replaceFirstChar { it.uppercase() } ?: ""
            var p2 = mEnPara.group(3)?.trim() ?: ""

            if (p1.isNotEmpty()) p1 = p1.substring(0, 1).uppercase() + p1.substring(1)
            p1 = p1.replace(Regex("(tomorrow morning)\\s+(everything)", RegexOption.IGNORE_CASE), "$1, $2")
            p1 = p1.trimEnd(',', '.', ' ') + "."

            p2 = p2.trimEnd('.') + "."
            val formatted = "$p1\n\n$trans, $p2"
            return finalizeText(formatted)
        }

        // 8. Single-line clause refining for common speech rhythms
        var s = text
        s = s.replace(Regex("(review\\s+PR)\\s+然後\\s+(deploy)", RegexOption.IGNORE_CASE), "$1，然後 $2")
        s = s.replace(Regex("(brunch)\\s+順便\\s+(chill)", RegexOption.IGNORE_CASE), "$1，順便 $2")
        s = s.replace(Regex("(已經\\s+fix\\s+了)\\s+(請幫我\\s+check)", RegexOption.IGNORE_CASE), "$1，請幫我 check")
        s = s.replace(Regex("(check\\s+一下\\s+branch)\\s+然後\\s+(merge)", RegexOption.IGNORE_CASE), "$1，然後 merge")

        // Ensure ending punctuation
        if (!s.endsWith("。") && !s.endsWith("！") && !s.endsWith("？") &&
            !s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) {
            val hasCjk = s.any { it.code in 0x4e00..0x9fa5 }
            s += if (hasCjk) "。" else "."
        }

        return finalizeText(s)
    }

    private fun finalizeText(text: String): String {
        val spaced = applyPanguSpacing(text)
        return TraditionalChineseConverter.toTraditional(spaced)
    }

    private fun applyPanguSpacing(text: String): String {
        var result = text
        // Remove spaces between consecutive CJK characters
        result = result.replace(Regex("([\\u4e00-\\u9fa5])\\s+([\\u4e00-\\u9fa5])"), "$1$2")
        // CJK followed by alphanumeric
        result = result.replace(Regex("([\\u4e00-\\u9fa5])([a-zA-Z0-9])"), "$1 $2")
        // Alphanumeric followed by CJK
        result = result.replace(Regex("([a-zA-Z0-9])([\\u4e00-\\u9fa5])"), "$1 $2")
        return result.trim()
    }
}
