package com.tx24.spicyplayer.lyrics.spicy.romanization

/** Links the desktop dictionary when installed, keeping the kana-only fallback available. */
internal class JapaneseTokenizer private constructor(private val delegate: Any) {
    data class Token(
        val surface: String,
        val reading: String?,
        val pronunciation: String?,
        val partOfSpeechLevel1: String,
        val partOfSpeechLevel2: String,
        val position: Int,
    )

    private val tokenize = delegate.javaClass.getMethod("tokenize", String::class.java)
    private val getters = Class.forName("com.atilika.kuromoji.ipadic.Token").let { type ->
        listOf("Surface", "Reading", "Pronunciation", "PartOfSpeechLevel1", "PartOfSpeechLevel2", "Position")
            .map { type.getMethod("get$it") }
    }

    fun tokenize(text: String): List<Token> = (tokenize.invoke(delegate, text) as List<*>).map { token ->
        val values = getters.map { it.invoke(token) }
        Token(values[0] as String, values[1] as String?, values[2] as String?,
            values[3] as String, values[4] as String, values[5] as Int)
    }

    companion object {
        fun open(): JapaneseTokenizer? = runCatching {
            JapaneseTokenizer(Class.forName("com.atilika.kuromoji.ipadic.Tokenizer").getConstructor().newInstance())
        }.getOrNull()
    }
}
