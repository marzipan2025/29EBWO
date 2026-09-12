package com.artbrain.ebwo.text

/**
 * 문장을 페이지로 앉힌다.
 *
 * **문장 하나가 페이지 하나다.** "가자." 도 "헉" 도 한 페이지를 다 쓴다.
 * 다만 화면에 들어가지 않는 긴 문장은 쪼갠다.
 *
 * 들어가는지 여부는 [fits] 가 판단한다 — 글자 수가 아니라 실제로 그려 본
 * 줄 수로 재야 정확해서, 재는 일은 안드로이드 쪽에 맡기고 여기서는 어디서
 * 자를지만 고른다. 덕분에 이 갈래는 JVM 에서 그대로 시험할 수 있다.
 */
object PageBuilder {

    /** 끊기 좋은 자리. 숫자가 작을수록 좋은 자리다. */
    private fun tierAfter(s: String, i: Int): Int {
        val c = s[i]
        return when {
            // 쉼표·세미콜론·콜론·줄표 뒤 — 뜻이 끊기는 자리
            c in ",;:，、" -> 1
            c in "—–" -> 1
            // 닫는 따옴표·괄호 뒤
            c in "\"'”’»›)]}〉》」』】" -> 1
            // 그 밖에는 빈칸 앞에서만 끊는다
            c == ' ' -> 2
            else -> 0     // 0 = 끊을 자리가 아님
        }
    }

    fun build(sentences: List<String>, fits: (String) -> Boolean): List<String> {
        val out = ArrayList<String>(sentences.size)
        for (s in sentences) out += chop(s, fits)
        return out
    }

    /** 한 문장을 들어가는 크기로 쪼갠다. 들어가면 그대로 하나다. */
    fun chop(sentence: String, fits: (String) -> Boolean): List<String> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptyList()
        if (fits(s)) return listOf(s)

        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val cut = findCut(s, start, fits)
            out += s.substring(start, cut).trim()
            start = cut
            while (start < s.length && s[start] == ' ') start++
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * [start] 에서 시작해 들어가는 만큼 가장 멀리 끊는 자리를 고른다.
     *
     * 들어가는 자리 가운데 가장 먼 곳을 잡되, 좋은 자리(쉼표 따위)가 그
     * 6할 뒤에 있으면 그쪽을 쓴다. 빈칸에서 아슬아슬하게 끊는 것보다
     * 쉼표에서 끊는 편이 읽기 낫고, 6할이면 자리를 크게 버리지도 않는다.
     */
    private fun findCut(s: String, start: Int, fits: (String) -> Boolean): Int {
        var best = -1        // 들어가는 가장 먼 끊을 자리
        var bestGood = -1    // 그 가운데 좋은 자리(tier 1)

        for (i in start until s.length) {
            val t = tierAfter(s, i)
            if (t == 0) continue
            // `i` 뒤에서 끊는다 — 부호는 앞 조각에 남긴다.
            val end = i + 1
            if (!fits(s.substring(start, end).trim())) break
            best = end
            if (t == 1) bestGood = end
        }

        // 문장 끝까지 들어가면 거기서 끝낸다.
        if (fits(s.substring(start).trim())) return s.length

        if (bestGood >= 0 && bestGood >= (best * 0.6).toInt()) return bestGood
        if (best >= 0) return best

        // 끊을 자리가 아예 없다 — 낱말 하나가 화면보다 긴 경우다.
        // 들어가는 만큼 글자 수로 자른다. 한 글자도 안 들어가면 한 글자는 낸다.
        var lo = start + 1
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(s.substring(start, mid))) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(start + 1)
    }
}
