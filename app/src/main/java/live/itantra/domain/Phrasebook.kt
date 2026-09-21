package live.itantra.domain

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class Message(
    val id: Int,
    val category: String,
    val slots: List<String>,
    val text: Map<String, String>,
    val alternates: Map<String, List<String>>,
)

data class GazetteerEntry(val id: Int, val text: Map<String, String>)

data class Candidate(
    val messageId: Int,
    val score: Double,
    val slots: Map<String, Int>,
    val template: String,
    val category: String,
)

data class MatchResult(
    val candidates: List<Candidate>,
    val masked: String,
    val accepted: Boolean,
    val reason: String,
) {
    val best: Candidate? get() = candidates.firstOrNull()
    val margin: Double
        get() = if (candidates.size < 2) 1.0 else candidates[0].score - candidates[1].score
}

class Phrasebook(json: String) {

    val languages: List<String>
    val messages: Map<Int, Message>
    val gazetteer: List<GazetteerEntry>
    private val numberWords: Map<String, Map<String, Int>>
    private val fillers: Map<String, Set<String>>

    private val surfaces: Map<String, List<Triple<Int, String, List<String>>>>

    private val gazIndex: Map<String, List<Triple<GazetteerEntry, String, String>>>

    init {
        val root = JSONObject(json)

        languages = root.getJSONArray("languages").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }

        numberWords = root.optJSONObject("numbers")?.let { obj ->
            obj.keys().asSequence().associateWith { lang ->
                val m = obj.getJSONObject(lang)
                m.keys().asSequence().associateWith { m.getInt(it) }
            }
        } ?: emptyMap()

        fillers = root.optJSONObject("fillers")?.let { obj ->
            obj.keys().asSequence().associateWith { lang ->
                val arr = obj.getJSONArray(lang)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } ?: emptyMap()

        val msgArr = root.getJSONArray("messages")
        messages = (0 until msgArr.length()).associate { i ->
            val m = msgArr.getJSONObject(i)
            val slotsArr = m.getJSONArray("slots")
            val textObj = m.getJSONObject("t")
            val altObj = m.optJSONObject("alt")
            val id = m.getInt("id")
            id to Message(
                id = id,
                category = m.getString("cat"),
                slots = (0 until slotsArr.length()).map { slotsArr.getString(it) },
                text = textObj.keys().asSequence().associateWith { textObj.getString(it) },
                alternates = altObj?.keys()?.asSequence()?.associateWith { lang ->
                    val a = altObj.getJSONArray(lang)
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyMap(),
            )
        }

        val gazArr = root.getJSONArray("gazetteer")
        gazetteer = (0 until gazArr.length()).map { i ->
            val g = gazArr.getJSONObject(i)
            val t = g.getJSONObject("t")
            GazetteerEntry(g.getInt("id"), t.keys().asSequence().associateWith { t.getString(it) })
        }

        surfaces = languages.associateWith { lang ->
            buildList {
                for (msg in messages.values) {
                    val forms = buildList {
                        msg.text[lang]?.let { add(it) }
                        msg.alternates[lang]?.let { addAll(it) }
                    }
                    for (form in forms) {
                        val key = Normalize.phoneticKey(form, fillersFor(lang))
                        if (key.isNotBlank()) add(Triple(msg.id, key, key.split(" ")))
                    }
                }
            }
        }

        gazIndex = languages.associateWith { lang ->
            gazetteer.mapNotNull { g ->
                g.text[lang]?.let { name ->
                    Triple(
                        g,
                        Normalize.normalize(name, fillersFor(lang)),
                        Normalize.phoneticKey(name, fillersFor(lang)),
                    )
                }
            }
        }
    }

    private fun fillersFor(lang: String): Set<String> = fillers[lang] ?: emptySet()

    private fun mask(text: String, lang: String): Pair<String, MutableMap<String, Int>> {
        val tokens = Normalize.normalize(text, fillersFor(lang)).split(" ").filter { it.isNotBlank() }
        val words = numberWords[lang] ?: emptyMap()
        val slots = mutableMapOf<String, Int>()
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {

            var bestValue: Int? = null
            var bestEnd = i
            var j = i
            while (j < tokens.size) {
                val value = Normalize.parseNumber(tokens.subList(i, j + 1), words) ?: break
                bestValue = value
                bestEnd = j
                j++
            }
            if (bestValue != null) {
                slots.putIfAbsent("n", bestValue)
                out.add(SLOT_N)
                i = bestEnd + 1
                continue
            }
            val place = lookupPlace(tokens[i], lang)
            if (place != null) {
                slots.putIfAbsent("loc", place.id)
                out.add(SLOT_LOC)
                i++
                continue
            }
            out.add(tokens[i])
            i++
        }
        return out.joinToString(" ") to slots
    }

    private fun lookupPlace(token: String, lang: String): GazetteerEntry? {
        val entries = gazIndex[lang] ?: return null
        val norm = Normalize.normalize(token, fillersFor(lang))
        val key = Normalize.phoneticKey(token, fillersFor(lang))
        if (norm.isBlank() || key.isBlank()) return null

        var best: GazetteerEntry? = null
        var bestKeyScore = 0.0
        var bestNormScore = 0.0
        for ((entry, gNorm, gKey) in entries) {
            val k = similarity(key, gKey)
            val n = similarity(norm, gNorm)
            if (max(k, n) > max(bestKeyScore, bestNormScore)) {
                best = entry; bestKeyScore = k; bestNormScore = n
            }
        }
        if (best == null) return null
        if (key.length >= PLACE_MIN_KEY_LEN && bestKeyScore >= PLACE_KEY_THRESHOLD) return best
        if (bestNormScore >= PLACE_SURFACE_THRESHOLD) return best
        return null
    }

    private fun score(qKey: String, qTokens: List<String>, sKey: String, sTokens: List<String>): Double {
        if (sTokens.isEmpty() || qTokens.isEmpty()) return 0.0
        var matched = 0
        for (st in sTokens) {
            val hit = if (st.length <= SHORT_TOKEN) {

                qTokens.contains(st)
            } else {
                qTokens.any { similarity(st, it) >= TOKEN_HIT }
            }
            if (hit) matched++
        }
        val coverage = matched.toDouble() / sTokens.size
        val sequence = similarity(qKey, sKey)
        val qSet = qTokens.toSet()
        val sSet = sTokens.toSet()
        val jaccard = qSet.intersect(sSet).size.toDouble() / qSet.union(sSet).size
        return 0.55 * coverage + 0.25 * sequence + 0.20 * jaccard
    }

    fun match(text: String, lang: String, topK: Int = 5): MatchResult {
        val (masked, slots) = mask(text, lang)
        if (masked.isBlank()) return MatchResult(emptyList(), masked, false, "empty transcript")

        val qKey = Normalize.phoneticKey(masked, fillersFor(lang))
        val qTokens = qKey.split(" ").filter { it.isNotBlank() }
        val forms = surfaces[lang] ?: return MatchResult(emptyList(), masked, false, "language $lang not in phrasebook")

        val bestPerMessage = HashMap<Int, Double>()
        for ((id, sKey, sTokens) in forms) {
            val s = score(qKey, qTokens, sKey, sTokens)
            if (s > (bestPerMessage[id] ?: 0.0)) bestPerMessage[id] = s
        }

        val adjusted = bestPerMessage.mapValues { (id, s) ->
            val required = messages.getValue(id).slots.toSet()
            if ((required - slots.keys).isNotEmpty()) s * MISSING_SLOT_PENALTY else s
        }

        val ranked = adjusted.entries.sortedByDescending { it.value }.take(topK).map { (id, s) ->
            val msg = messages.getValue(id)
            Candidate(
                messageId = id,
                score = s,
                slots = slots.filterKeys { it in msg.slots },
                template = msg.text[lang] ?: msg.text["en"].orEmpty(),
                category = msg.category,
            )
        }

        val result = MatchResult(ranked, masked, false, "")
        return when {
            ranked.isEmpty() -> result.copy(reason = "no candidates")
            ranked[0].score < ACCEPT_SCORE ->
                result.copy(reason = "score %.3f < %.2f".format(ranked[0].score, ACCEPT_SCORE))
            result.margin < ACCEPT_MARGIN ->
                result.copy(reason = "margin %.3f < %.2f (ambiguous)".format(result.margin, ACCEPT_MARGIN))
            else -> result.copy(accepted = true, reason = "accepted")
        }
    }

    fun render(messageId: Int, slots: Map<String, Int>, lang: String): String {
        val msg = messages[messageId] ?: return ""
        var text = msg.text[lang] ?: msg.text["en"] ?: return ""
        slots["n"]?.let { text = text.replace(SLOT_N, it.toString()) }
        slots["loc"]?.let { locId ->
            val place = gazetteer.firstOrNull { it.id == locId }
            text = text.replace(SLOT_LOC, place?.text?.get(lang) ?: place?.text?.get("en") ?: "")
        }
        return text
    }

    fun isAlert(messageId: Int): Boolean =
        messages[messageId]?.category in setOf("event", "need")

    companion object {
        const val SLOT_N = "{n}"
        const val SLOT_LOC = "{loc}"

        const val ACCEPT_SCORE = 0.64
        const val ACCEPT_MARGIN = 0.06

        private const val TOKEN_HIT = 0.75
        private const val SHORT_TOKEN = 2
        private const val MISSING_SLOT_PENALTY = 0.80
        private const val PLACE_MIN_KEY_LEN = 4
        private const val PLACE_KEY_THRESHOLD = 0.80
        private const val PLACE_SURFACE_THRESHOLD = 0.85

        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val distance = levenshtein(a, b)
            return 1.0 - distance.toDouble() / max(a.length, b.length)
        }

        private fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
                }
                val swap = previous; previous = current; current = swap
            }
            return previous[b.length]
        }
    }
}
