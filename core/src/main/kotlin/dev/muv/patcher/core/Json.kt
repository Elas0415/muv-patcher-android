package dev.muv.patcher.core

/** 极简 JSON 解析，避免核心模块引入第三方依赖。 */
object Json {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.position == text.length) { "JSON 尾部存在多余内容" }
        return value
    }

    private class Parser(val text: String) {
        var position = 0

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(position < text.length) { "JSON 意外结束" }
            return when (val ch = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else error("非法 JSON 字符 '$ch'")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            require(text.startsWith(word, position)) { "非法字面量" }
            position += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++ // {
            skipWhitespace()
            if (position < text.length && text[position] == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(position < text.length && text[position] == ':') { "对象缺少冒号" }
                position++
                result[key] = readValue()
                skipWhitespace()
                require(position < text.length) { "对象未闭合" }
                when (text[position]) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> error("对象分隔符非法")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            position++ // [
            skipWhitespace()
            if (position < text.length && text[position] == ']') {
                position++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                require(position < text.length) { "数组未闭合" }
                when (text[position]) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> error("数组分隔符非法")
                }
            }
        }

        private fun readString(): String {
            require(position < text.length && text[position] == '"') { "字符串缺少引号" }
            position++
            val builder = StringBuilder()
            while (position < text.length) {
                val ch = text[position++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(position < text.length) { "转义序列不完整" }
                        when (val esc = text[position++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(position + 4 <= text.length) { "Unicode 转义不完整" }
                                builder.append(text.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> error("未知转义 \\$esc")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
            error("字符串未闭合")
        }

        private fun readNumber(): Number {
            val start = position
            if (text[position] == '-') position++
            while (position < text.length && text[position].isDigit()) position++
            var isDouble = false
            if (position < text.length && text[position] == '.') {
                isDouble = true
                position++
                while (position < text.length && text[position].isDigit()) position++
            }
            if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
                isDouble = true
                position++
                if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
                while (position < text.length && text[position].isDigit()) position++
            }
            val raw = text.substring(start, position)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }
    }
}
