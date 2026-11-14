package it.dogior.hadEnough

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.IdentityHashMap

internal class NuvioDom {
    private val nodes = ArrayList<Element>()
    private val ids = IdentityHashMap<Element, Int>()
    private val roots = IdentityHashMap<Element, Int>()

    private fun idOf(e: Element): Int {
        ids[e]?.let { return it }
        nodes.add(e)
        ids[e] = nodes.size - 1
        return nodes.size - 1
    }

    private fun rootOf(e: Element): Element {
        var cur: Element = e
        while (true) cur = cur.parent() ?: return cur
    }

    private fun docIndex(root: Element): Int = roots.getOrPut(root) { roots.size }

    private fun key(e: Element): List<Int> {
        val path = ArrayList<Int>()
        var cur: Element = e
        while (true) {
            val p = cur.parent() ?: break
            path.add(0, cur.elementSiblingIndex())
            cur = p
        }
        path.add(0, docIndex(cur))
        return path
    }

    private fun ordered(list: Collection<Element>): List<Element> {
        val seen = IdentityHashMap<Element, Boolean>()
        val unique = list.filter { seen.put(it, true) == null }
        return unique.sortedWith { x, y ->
            val a = key(x)
            val b = key(y)
            var r = 0
            var i = 0
            while (r == 0 && i < maxOf(a.size, b.size)) {
                r = (a.getOrElse(i) { -1 }).compareTo(b.getOrElse(i) { -1 })
                i++
            }
            r
        }
    }

    private fun idsJson(list: Collection<Element>): String =
        JSONArray(ordered(list).map { idOf(it) }).toString()

    private fun matches(e: Element, sel: String): Boolean =
        try { e.`is`(sel) } catch (_: Exception) { false }

    private fun selectBelow(e: Element, sel: String): List<Element> =
        try { e.select(sel).filter { it !== e } } catch (_: Exception) { emptyList() }

    private fun textOf(n: Node): String = when (n) {
        is TextNode -> n.wholeText
        is Element -> {
            if (n.tagName() == "script" || n.tagName() == "style") "" else n.childNodes().joinToString("") { textOf(it) }
        }
        else -> ""
    }

    private fun newDoc(html: String): Document {
        val d = Jsoup.parse(html)
        d.outputSettings().prettyPrint(false)
        return d
    }

    private fun quote(s: String?): String? = if (s == null) null else JSONObject.quote(s)

    fun call(op: String, idsJson: String, a: String?, b: String?): String? {
        val arr = JSONArray(idsJson)
        val els = ArrayList<Element>(arr.length())
        for (i in 0 until arr.length()) {
            val el = nodes.getOrNull(arr.optInt(i, -1))
            if (el != null) els.add(el)
        }
        val arg = a ?: ""
        return when (op) {
            "load" -> idOf(newDoc(arg)).toString()
            "fragment" -> {
                val d = Jsoup.parseBodyFragment(arg)
                d.outputSettings().prettyPrint(false)
                idsJson(d.body().children())
            }
            "find" -> idsJson(els.flatMap { selectBelow(it, arg) })
            "filter" -> idsJson(els.filter { matches(it, arg) })
            "not" -> idsJson(els.filter { !matches(it, arg) })
            "is" -> els.any { matches(it, arg) }.toString()
            "has" -> idsJson(els.filter { selectBelow(it, arg).isNotEmpty() })
            "rel" -> {
                val out = ArrayList<Element>()
                for (e in els) {
                    val r: List<Element> = when (arg) {
                        "children" -> e.children().toList()
                        "parent" -> listOfNotNull(e.parent()?.takeIf { it !is Document })
                        "parents" -> e.parents().filter { it !is Document }
                        "closest" -> {
                            var cur: Element? = e
                            var found: Element? = null
                            while (cur != null && cur !is Document) {
                                if (b == null || matches(cur, b)) { found = cur; break }
                                cur = cur.parent()
                            }
                            out.addAll(listOfNotNull(found))
                            continue
                        }
                        "next" -> listOfNotNull(e.nextElementSibling())
                        "nextAll" -> e.nextElementSiblings().toList()
                        "prev" -> listOfNotNull(e.previousElementSibling())
                        "prevAll" -> e.previousElementSiblings().toList()
                        "siblings" -> e.siblingElements().toList()
                        else -> emptyList()
                    }
                    out.addAll(if (b != null) r.filter { matches(it, b) } else r)
                }
                
                if (arg == "prevAll" && els.size == 1) JSONArray(out.map { idOf(it) }).toString() else idsJson(out)
            }
            "attr" -> els.firstOrNull()?.takeIf { it.hasAttr(arg) }?.let { quote(it.attr(arg)) }
            "attribs" -> els.firstOrNull()?.let { e ->
                val o = JSONObject()
                for (at in e.attributes()) o.put(at.key, at.value)
                o.toString()
            }
            "setAttr" -> { els.forEach { if (b == null) it.removeAttr(arg) else it.attr(arg, b) }; "true" }
            "removeAttr" -> { els.forEach { it.removeAttr(arg) }; "true" }
            "text" -> quote(els.joinToString("") { textOf(it) })
            "html" -> els.firstOrNull()?.let { quote(it.html()) }
            "outer" -> quote(els.joinToString("") { it.outerHtml() })
            "tagName" -> els.firstOrNull()?.let { quote(if (it is Document) "root" else it.tagName()) }
            "hasClass" -> els.any { it.hasClass(arg) }.toString()
            "val" -> els.firstOrNull()?.let { e ->
                if (e.tagName() == "textarea") quote(e.text()) else if (e.hasAttr("value")) quote(e.attr("value")) else null
            }
            "prop" -> els.firstOrNull()?.let { e ->
                when {
                    arg == "tagName" || arg == "nodeName" -> quote(e.tagName().uppercase())
                    e.hasAttr(arg) -> quote(e.attr(arg))
                    else -> null
                }
            }
            "remove" -> { els.forEach { it.remove() }; "true" }
            "order" -> idsJson(els)
            else -> null
        }
    }
}
