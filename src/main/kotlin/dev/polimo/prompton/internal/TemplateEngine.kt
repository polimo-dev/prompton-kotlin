package dev.polimo.prompton.internal

import dev.polimo.prompton.LintReason
import dev.polimo.prompton.MissingVariableException
import dev.polimo.prompton.TemplateParseException
import dev.polimo.prompton.TemplateRenderException

// ---------------------------------------------------------------------------
// Lexer

internal sealed interface RawToken {
    val source: String
}

internal data class TextToken(
    override val source: String,
) : RawToken

internal data class OutputToken(
    override val source: String,
    val body: String,
    val trimLeft: Boolean,
    val trimRight: Boolean,
) : RawToken

internal data class TagToken(
    override val source: String,
    val body: String,
    val trimLeft: Boolean,
    val trimRight: Boolean,
) : RawToken

internal object TemplateLexer {
    fun scan(source: String): List<RawToken> {
        val tokens = mutableListOf<RawToken>()
        var index = 0
        while (index < source.length) {
            val next = nextMarker(source, index)
            if (next < 0) {
                tokens += TextToken(source.substring(index))
                break
            }
            if (next > index) tokens += TextToken(source.substring(index, next))
            val isOutput = source[next + 1] == '{'
            val close = if (isOutput) "}}" else "%}"
            val end = source.indexOf(close, next + 2)
            if (end < 0) {
                throw TemplateParseException(
                    if (isOutput) "Expected '}}'" else "Expected '%}'",
                )
            }
            val whole = source.substring(next, end + 2)
            val trimLeft = whole.length > 2 && whole[2] == '-'
            val trimRight = whole.length > 4 && whole[whole.length - 3] == '-'
            val bodyStart = next + 2 + (if (trimLeft) 1 else 0)
            val bodyEnd = end - (if (trimRight) 1 else 0)
            val body = source.substring(bodyStart, maxOf(bodyStart, bodyEnd)).trim()
            tokens +=
                if (isOutput) {
                    OutputToken(whole, body, trimLeft, trimRight)
                } else {
                    TagToken(whole, body, trimLeft, trimRight)
                }
            index = end + 2
        }
        return applyWhitespaceControl(tokens)
    }

    private fun nextMarker(
        source: String,
        from: Int,
    ): Int {
        var i = from
        while (i < source.length - 1) {
            if (source[i] == '{' && (source[i + 1] == '{' || source[i + 1] == '%')) return i
            i += 1
        }
        return -1
    }

    private fun applyWhitespaceControl(tokens: List<RawToken>): List<RawToken> {
        val result = tokens.toMutableList()
        for (i in result.indices) {
            val token = result[i]
            val trimLeft: Boolean
            val trimRight: Boolean
            when (token) {
                is OutputToken -> {
                    trimLeft = token.trimLeft
                    trimRight = token.trimRight
                }

                is TagToken -> {
                    trimLeft = token.trimLeft
                    trimRight = token.trimRight
                }

                is TextToken -> continue
            }
            if (trimLeft && i > 0) {
                val previous = result[i - 1]
                if (previous is TextToken) result[i - 1] = TextToken(previous.source.trimEnd())
            }
            if (trimRight && i < result.size - 1) {
                val next = result[i + 1]
                if (next is TextToken) result[i + 1] = TextToken(next.source.trimStart())
            }
        }
        return result
    }
}

// ---------------------------------------------------------------------------
// Expressions

internal sealed interface Expr

internal data class LiteralExpr(
    val value: Any?,
) : Expr

internal sealed interface Accessor

internal data class FieldAccessor(
    val name: String,
) : Accessor

internal data class IndexAccessor(
    val index: Int,
) : Accessor

internal data class VariableExpr(
    val root: String,
    val accessors: List<Accessor>,
    val source: String,
) : Expr

internal data class BinaryExpr(
    val op: String,
    val left: Expr,
    val right: Expr,
) : Expr

internal data class FilterCall(
    val name: String,
    val args: List<Expr>,
)

internal data class FilteredExpr(
    val base: Expr,
    val filters: List<FilterCall>,
) : Expr

// ---------------------------------------------------------------------------
// Nodes

internal sealed interface Node

internal data class TextNode(
    val text: String,
) : Node

internal data class OutputNode(
    val expr: FilteredExpr,
) : Node

internal data class ConditionBranch(
    val condition: Expr,
    val body: List<Node>,
)

internal data class IfNode(
    val branches: List<ConditionBranch>,
    val elseBody: List<Node>?,
) : Node

internal data class UnlessNode(
    val condition: Expr,
    val body: List<Node>,
) : Node

internal data class ForNode(
    val variable: String,
    val enumerable: FilteredExpr,
    val body: List<Node>,
    val elseBody: List<Node>?,
) : Node

internal data class AssignNode(
    val target: String,
    val expr: FilteredExpr,
) : Node

internal data object BreakNode : Node

internal data object ContinueNode : Node

// ---------------------------------------------------------------------------
// Expression tokenizer

private data class ExprToken(
    val kind: String,
    val text: String,
    val value: Any? = null,
)

private class ExprLexer(
    private val source: String,
) {
    private var pos = 0

    fun tokenize(): List<ExprToken> {
        val tokens = mutableListOf<ExprToken>()
        while (true) {
            skipSpace()
            if (pos >= source.length) break
            val ch = source[pos]
            when {
                ch == '"' || ch == '\'' -> tokens += readString(ch)
                ch.isDigit() || (ch == '-' && pos + 1 < source.length && source[pos + 1].isDigit()) ->
                    tokens += readNumber()

                ch.isLetter() || ch == '_' -> tokens += readIdent()
                else -> tokens += readOperator()
            }
        }
        return tokens
    }

    private fun skipSpace() {
        while (pos < source.length && source[pos].isWhitespace()) pos += 1
    }

    private fun readString(quote: Char): ExprToken {
        val start = pos
        pos += 1
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != quote) {
            if (source[pos] == '\\' && pos + 1 < source.length) {
                pos += 1
                sb.append(source[pos])
            } else {
                sb.append(source[pos])
            }
            pos += 1
        }
        if (pos >= source.length) throw TemplateParseException("unterminated string in ${source.substring(start)}")
        pos += 1
        return ExprToken("string", sb.toString(), sb.toString())
    }

    private fun readNumber(): ExprToken {
        val start = pos
        if (source[pos] == '-') pos += 1
        var isFloat = false
        while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) {
            if (source[pos] == '.') {
                if (pos + 1 < source.length && source[pos + 1] == '.') break
                isFloat = true
            }
            pos += 1
        }
        val text = source.substring(start, pos)
        val value: Any = if (isFloat) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        return ExprToken("number", text, value)
    }

    private fun readIdent(): ExprToken {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_' || source[pos] == '-')) {
            pos += 1
        }
        return ExprToken("ident", source.substring(start, pos))
    }

    private fun readOperator(): ExprToken {
        val two = if (pos + 1 < source.length) source.substring(pos, pos + 2) else ""
        if (two in setOf("==", "!=", ">=", "<=", "<>")) {
            pos += 2
            return ExprToken("op", two)
        }
        val one = source[pos].toString()
        pos += 1
        return ExprToken("op", one)
    }
}

// ---------------------------------------------------------------------------
// Expression parser

private class ExprParser(
    private val tokens: List<ExprToken>,
) {
    private var pos = 0

    private fun peek(): ExprToken? = tokens.getOrNull(pos)

    private fun next(): ExprToken? = tokens.getOrNull(pos)?.also { pos += 1 }

    fun atEnd(): Boolean = pos >= tokens.size

    fun parseFiltered(): FilteredExpr {
        val base = parseTerm()
        val filters = mutableListOf<FilterCall>()
        while (peek()?.let { it.kind == "op" && it.text == "|" } == true) {
            next()
            val name =
                next()?.takeIf { it.kind == "ident" }?.text
                    ?: throw TemplateParseException("expected a filter name after '|'")
            val args = mutableListOf<Expr>()
            if (peek()?.let { it.kind == "op" && it.text == ":" } == true) {
                next()
                args += parseTerm()
                while (peek()?.let { it.kind == "op" && it.text == "," } == true) {
                    next()
                    args += parseTerm()
                }
            }
            filters += FilterCall(name, args)
        }
        return FilteredExpr(base, filters)
    }

    fun parseCondition(): Expr {
        var left = parseComparison()
        while (true) {
            val token = peek() ?: return left
            if (token.kind == "ident" && (token.text == "and" || token.text == "or")) {
                next()
                val right = parseComparison()
                left = BinaryExpr(token.text, left, right)
            } else {
                return left
            }
        }
    }

    private fun parseComparison(): Expr {
        val left = parseTerm()
        val token = peek() ?: return left
        val op =
            when {
                token.kind == "op" && token.text in setOf("==", "!=", "<>", ">", "<", ">=", "<=") -> token.text
                token.kind == "ident" && token.text == "contains" -> "contains"
                else -> return left
            }
        next()
        return BinaryExpr(op, left, parseTerm())
    }

    private fun parseTerm(): Expr {
        val token = next() ?: throw TemplateParseException("unexpected end of expression")
        return when (token.kind) {
            "string" -> LiteralExpr(token.value)
            "number" -> LiteralExpr(token.value)
            "ident" ->
                when (token.text) {
                    "true" -> LiteralExpr(true)
                    "false" -> LiteralExpr(false)
                    "nil", "null" -> LiteralExpr(null)
                    "empty", "blank" -> LiteralExpr("")
                    else -> parseVariable(token.text)
                }

            else -> throw TemplateParseException("unexpected token '${token.text}'")
        }
    }

    private fun parseVariable(root: String): VariableExpr {
        val accessors = mutableListOf<Accessor>()
        val source = StringBuilder(root)
        while (true) {
            val token = peek() ?: break
            if (token.kind == "op" && token.text == ".") {
                next()
                val field =
                    next()?.takeIf { it.kind == "ident" }
                        ?: throw TemplateParseException("expected a field name after '.'")
                accessors += FieldAccessor(field.text)
                source.append('.').append(field.text)
            } else if (token.kind == "op" && token.text == "[") {
                next()
                val inner = next() ?: throw TemplateParseException("expected an index after '['")
                when (inner.kind) {
                    "number" -> {
                        accessors += IndexAccessor((inner.value as Number).toInt())
                        source.append('[').append(inner.text).append(']')
                    }

                    "string" -> {
                        accessors += FieldAccessor(inner.value as String)
                        source.append('[').append(inner.text).append(']')
                    }

                    else -> throw TemplateParseException("unsupported index '${inner.text}'")
                }
                val closing = next()
                if (closing == null || closing.text != "]") throw TemplateParseException("Expected ']'")
            } else {
                break
            }
        }
        return VariableExpr(root, accessors, source.toString())
    }
}

// ---------------------------------------------------------------------------
// Template parser

internal object TemplateParser {
    private val WHITESPACE = Regex("\\s+")

    val ALLOWED_TAGS: Set<String> =
        setOf(
            "for",
            "endfor",
            "if",
            "endif",
            "elsif",
            "else",
            "unless",
            "endunless",
            "assign",
            "break",
            "continue",
        )

    fun parse(source: String): List<Node> {
        val tokens = TemplateLexer.scan(source)
        val cursor = Cursor(tokens)
        val (nodes, terminator) = parseNodes(cursor, emptySet())
        if (terminator != null) throw TemplateParseException("Unexpected tag '$terminator'")
        return nodes
    }

    private class Cursor(
        val tokens: List<RawToken>,
    ) {
        var pos = 0
    }

    private fun parseNodes(
        cursor: Cursor,
        stopTags: Set<String>,
    ): Pair<List<Node>, String?> {
        val nodes = mutableListOf<Node>()
        while (cursor.pos < cursor.tokens.size) {
            val token = cursor.tokens[cursor.pos]
            when (token) {
                is TextToken -> {
                    cursor.pos += 1
                    if (token.source.isNotEmpty()) nodes += TextNode(token.source)
                }

                is OutputToken -> {
                    cursor.pos += 1
                    nodes += OutputNode(parseFiltered(token.body))
                }

                is TagToken -> {
                    val name = token.body.split(WHITESPACE, limit = 2).first()
                    if (name in stopTags) return nodes to name
                    if (name !in ALLOWED_TAGS) throw TemplateParseException("Unexpected tag '$name'")
                    cursor.pos += 1
                    nodes += parseTag(cursor, name, token.body.removePrefix(name).trim())
                }
            }
        }
        return nodes to null
    }

    /**
     * A block body that is exactly one whitespace-only text node renders as empty.
     *
     * That is what the reference implementation does, and the conformance suite pins it:
     * `{% unless forloop.last %} {% endunless %}` contributes nothing, while
     * `{% unless forloop.last %},{% endunless %}` contributes a comma.
     */
    private fun normalizeBody(nodes: List<Node>): List<Node> {
        val only = nodes.singleOrNull()
        return if (only is TextNode && only.text.isNotEmpty() && only.text.isBlank()) emptyList() else nodes
    }

    private fun parseTag(
        cursor: Cursor,
        name: String,
        args: String,
    ): Node =
        when (name) {
            "if" -> parseIf(cursor, args)
            "unless" -> parseUnless(cursor, args)
            "for" -> parseFor(cursor, args)
            "assign" -> parseAssign(args)
            "break" -> BreakNode
            "continue" -> ContinueNode
            else -> throw TemplateParseException("Unexpected tag '$name'")
        }

    private fun parseIf(
        cursor: Cursor,
        args: String,
    ): Node {
        val branches = mutableListOf<ConditionBranch>()
        var elseBody: List<Node>? = null
        var condition = parseCondition(args)
        while (true) {
            val (body, terminator) = parseNodes(cursor, setOf("elsif", "else", "endif"))
            when (terminator) {
                "elsif" -> {
                    branches += ConditionBranch(condition, normalizeBody(body))
                    val token = cursor.tokens[cursor.pos] as TagToken
                    cursor.pos += 1
                    condition = parseCondition(token.body.removePrefix("elsif").trim())
                }

                "else" -> {
                    branches += ConditionBranch(condition, normalizeBody(body))
                    cursor.pos += 1
                    val (tail, tailTerminator) = parseNodes(cursor, setOf("endif"))
                    if (tailTerminator != "endif") throw TemplateParseException("Expected 'endif'")
                    cursor.pos += 1
                    elseBody = normalizeBody(tail)
                    return IfNode(branches, elseBody)
                }

                "endif" -> {
                    branches += ConditionBranch(condition, normalizeBody(body))
                    cursor.pos += 1
                    return IfNode(branches, elseBody)
                }

                else -> throw TemplateParseException("Expected 'endif'")
            }
        }
    }

    private fun parseUnless(
        cursor: Cursor,
        args: String,
    ): Node {
        val condition = parseCondition(args)
        val (body, terminator) = parseNodes(cursor, setOf("endunless"))
        if (terminator != "endunless") throw TemplateParseException("Expected 'endunless'")
        cursor.pos += 1
        return UnlessNode(condition, normalizeBody(body))
    }

    private fun parseFor(
        cursor: Cursor,
        args: String,
    ): Node {
        val parts = args.split(WHITESPACE, limit = 3)
        if (parts.size < 3 || parts[1] != "in") throw TemplateParseException("malformed for tag: $args")
        val variable = parts[0]
        val enumerable = parseFiltered(parts[2])
        val (body, terminator) = parseNodes(cursor, setOf("else", "endfor"))
        if (terminator == "else") {
            cursor.pos += 1
            val (elseBody, elseTerminator) = parseNodes(cursor, setOf("endfor"))
            if (elseTerminator != "endfor") throw TemplateParseException("Expected 'endfor'")
            cursor.pos += 1
            return ForNode(variable, enumerable, normalizeBody(body), normalizeBody(elseBody))
        }
        if (terminator != "endfor") throw TemplateParseException("Expected 'endfor'")
        cursor.pos += 1
        return ForNode(variable, enumerable, normalizeBody(body), null)
    }

    private fun parseAssign(args: String): Node {
        val index = args.indexOf('=')
        if (index < 0) throw TemplateParseException("malformed assign tag: $args")
        val target = args.substring(0, index).trim()
        if (target.isEmpty()) throw TemplateParseException("malformed assign tag: $args")
        return AssignNode(target, parseFiltered(args.substring(index + 1).trim()))
    }

    fun parseFiltered(source: String): FilteredExpr {
        val parser = ExprParser(ExprLexer(source).tokenize())
        val expr = parser.parseFiltered()
        if (!parser.atEnd()) throw TemplateParseException("unexpected trailing input in '$source'")
        return expr
    }

    fun parseCondition(source: String): Expr {
        if (source.isBlank()) throw TemplateParseException("empty condition")
        val parser = ExprParser(ExprLexer(source).tokenize())
        val expr = parser.parseCondition()
        if (!parser.atEnd()) throw TemplateParseException("unexpected trailing input in '$source'")
        return expr
    }
}

// ---------------------------------------------------------------------------
// Renderer

private class BreakSignal : RuntimeException(null, null, false, false)

private class ContinueSignal : RuntimeException(null, null, false, false)

private sealed interface Lookup {
    data class Found(
        val value: Any?,
    ) : Lookup

    data class Missing(
        val path: String,
    ) : Lookup
}

internal object TemplateRenderer {
    val ALLOWED_FILTERS: Set<String> = setOf("size", "join", "default")

    private const val BUILTIN_FORLOOP = "forloop"

    fun render(
        nodes: List<Node>,
        variables: Map<String, Any?>,
    ): String {
        val scope = LinkedHashMap<String, Any?>(variables)
        val sb = StringBuilder()
        renderNodes(nodes, scope, sb)
        return sb.toString()
    }

    private fun renderNodes(
        nodes: List<Node>,
        scope: MutableMap<String, Any?>,
        sb: StringBuilder,
    ) {
        for (node in nodes) renderNode(node, scope, sb)
    }

    private fun renderNode(
        node: Node,
        scope: MutableMap<String, Any?>,
        sb: StringBuilder,
    ) {
        when (node) {
            is TextNode -> sb.append(node.text)
            is OutputNode -> sb.append(stringify(evaluate(node.expr, scope, strict = true)))
            is AssignNode -> scope[node.target] = evaluate(node.expr, scope, strict = true)
            is BreakNode -> throw BreakSignal()
            is ContinueNode -> throw ContinueSignal()

            is IfNode -> {
                for (branch in node.branches) {
                    if (truthy(evaluate(branch.condition, scope, strict = false))) {
                        renderNodes(branch.body, scope, sb)
                        return
                    }
                }
                node.elseBody?.let { renderNodes(it, scope, sb) }
            }

            is UnlessNode ->
                if (!truthy(evaluate(node.condition, scope, strict = true))) {
                    renderNodes(node.body, scope, sb)
                }

            is ForNode -> renderFor(node, scope, sb)
        }
    }

    private fun renderFor(
        node: ForNode,
        scope: MutableMap<String, Any?>,
        sb: StringBuilder,
    ) {
        val items = iterable(evaluate(node.enumerable, scope, strict = true))
        if (items.isEmpty()) {
            node.elseBody?.let { renderNodes(it, scope, sb) }
            return
        }
        val hadVariable = scope.containsKey(node.variable)
        val previousVariable = scope[node.variable]
        val hadForloop = scope.containsKey(BUILTIN_FORLOOP)
        val previousForloop = scope[BUILTIN_FORLOOP]
        try {
            for ((index, item) in items.withIndex()) {
                scope[node.variable] = item
                scope[BUILTIN_FORLOOP] = forloop(index, items.size)
                try {
                    renderNodes(node.body, scope, sb)
                } catch (_: ContinueSignal) {
                    continue
                }
            }
        } catch (_: BreakSignal) {
            // `break` ends the loop and nothing else.
        } finally {
            if (hadVariable) scope[node.variable] = previousVariable else scope.remove(node.variable)
            if (hadForloop) scope[BUILTIN_FORLOOP] = previousForloop else scope.remove(BUILTIN_FORLOOP)
        }
    }

    private fun forloop(
        index: Int,
        length: Int,
    ): Map<String, Any?> =
        mapOf(
            "index" to (index + 1).toLong(),
            "index0" to index.toLong(),
            "rindex" to (length - index).toLong(),
            "rindex0" to (length - index - 1).toLong(),
            "first" to (index == 0),
            "last" to (index == length - 1),
            "length" to length.toLong(),
        )

    private fun iterable(value: Any?): List<Any?> =
        when (value) {
            null -> emptyList()
            is List<*> -> value
            is Map<*, *> -> value.entries.map { listOf(it.key, it.value) }
            is Iterable<*> -> value.toList()
            else -> listOf(value)
        }

    internal fun evaluate(
        expr: Expr,
        scope: Map<String, Any?>,
        strict: Boolean,
    ): Any? =
        when (expr) {
            is LiteralExpr -> expr.value
            is VariableExpr -> resolveVariable(expr, scope, strict)
            is FilteredExpr -> {
                var value = evaluate(expr.base, scope, strict)
                for (filter in expr.filters) {
                    value = applyFilter(filter, value, scope, strict)
                }
                value
            }

            is BinaryExpr -> evaluateBinary(expr, scope, strict)
        }

    private fun evaluateBinary(
        expr: BinaryExpr,
        scope: Map<String, Any?>,
        strict: Boolean,
    ): Any? {
        if (expr.op == "and") {
            return truthy(evaluate(expr.left, scope, strict)) && truthy(evaluate(expr.right, scope, strict))
        }
        if (expr.op == "or") {
            return truthy(evaluate(expr.left, scope, strict)) || truthy(evaluate(expr.right, scope, strict))
        }
        val left = evaluate(expr.left, scope, strict)
        val right = evaluate(expr.right, scope, strict)
        return when (expr.op) {
            "==" -> valuesEqual(left, right)
            "!=", "<>" -> !valuesEqual(left, right)
            ">" -> compareValues(left, right)?.let { it > 0 } ?: false
            "<" -> compareValues(left, right)?.let { it < 0 } ?: false
            ">=" -> compareValues(left, right)?.let { it >= 0 } ?: false
            "<=" -> compareValues(left, right)?.let { it <= 0 } ?: false
            "contains" -> containsValue(left, right)
            else -> throw TemplateRenderException("unsupported operator '${expr.op}'")
        }
    }

    private fun valuesEqual(
        left: Any?,
        right: Any?,
    ): Boolean {
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun compareValues(
        left: Any?,
        right: Any?,
    ): Int? {
        if (left is Number && right is Number) return left.toDouble().compareTo(right.toDouble())
        if (left is String && right is String) return left.compareTo(right)
        return null
    }

    private fun containsValue(
        left: Any?,
        right: Any?,
    ): Boolean =
        when (left) {
            is String -> left.contains(stringify(right))
            is List<*> -> left.any { valuesEqual(it, right) }
            else -> false
        }

    private fun resolveVariable(
        expr: VariableExpr,
        scope: Map<String, Any?>,
        strict: Boolean,
    ): Any? =
        when (val lookup = lookup(expr, scope)) {
            is Lookup.Found -> lookup.value
            is Lookup.Missing -> if (strict) throw MissingVariableException(lookup.path) else null
        }

    private fun lookup(
        expr: VariableExpr,
        scope: Map<String, Any?>,
    ): Lookup {
        if (!scope.containsKey(expr.root)) return Lookup.Missing(expr.root)
        var current: Any? = scope[expr.root]
        val path = StringBuilder(expr.root)
        for (accessor in expr.accessors) {
            when (accessor) {
                is FieldAccessor -> {
                    path.append('.').append(accessor.name)
                    val map = current as? Map<*, *> ?: return Lookup.Found(null)
                    if (!map.containsKey(accessor.name)) return Lookup.Missing(path.toString())
                    current = map[accessor.name]
                }

                is IndexAccessor -> {
                    path.append('[').append(accessor.index).append(']')
                    val list = current as? List<*> ?: return Lookup.Found(null)
                    current = list.getOrNull(accessor.index)
                }
            }
        }
        return Lookup.Found(current)
    }

    private fun applyFilter(
        filter: FilterCall,
        value: Any?,
        scope: Map<String, Any?>,
        strict: Boolean,
    ): Any? {
        val args = filter.args.map { evaluate(it, scope, strict) }
        return when (filter.name) {
            "size" ->
                when (value) {
                    null -> 0L
                    is String -> value.codePointCount(0, value.length).toLong()
                    is List<*> -> value.size.toLong()
                    is Map<*, *> -> value.size.toLong()
                    else -> 0L
                }

            "join" -> {
                val separator = args.firstOrNull()?.let { stringify(it) } ?: " "
                when (value) {
                    is List<*> -> value.joinToString(separator) { stringify(it) }
                    null -> ""
                    else -> stringify(value)
                }
            }

            "default" -> {
                val fallback = args.firstOrNull()
                if (isBlank(value)) fallback else value
            }

            else -> throw TemplateRenderException("unknown filter: ${filter.name}")
        }
    }

    private fun isBlank(value: Any?): Boolean =
        when (value) {
            null -> true
            false -> true
            is String -> value.isEmpty()
            is List<*> -> value.isEmpty()
            is Map<*, *> -> value.isEmpty()
            else -> false
        }

    internal fun truthy(value: Any?): Boolean =
        when (value) {
            null -> false
            is Boolean -> value
            else -> true
        }

    internal fun stringify(value: Any?): String =
        when (value) {
            null -> ""
            is String -> value
            is Boolean -> value.toString()
            is Double -> formatDouble(value)
            is Float -> formatDouble(value.toDouble())
            is List<*> -> value.joinToString("") { stringify(it) }
            is Map<*, *> -> Ptn.canonicalJson(Ptn.toElement(value))
            else -> value.toString()
        }

    private fun formatDouble(value: Double): String = value.toString().replace("E", "e")
}

// ---------------------------------------------------------------------------
// Lint and variable extraction

internal object TemplateLint {
    private val TAG_NAME = Regex("\\{%-?\\s*([A-Za-z_][A-Za-z0-9_]*)")

    fun lint(source: String): List<LintReason> {
        val reasons = mutableListOf<LintReason>()
        reasons += whitespaceControlReasons(source)

        val disallowedTags =
            TAG_NAME
                .findAll(source)
                .map { it.groupValues[1] }
                .filter { it !in TemplateParser.ALLOWED_TAGS }
                .distinct()
                .map { LintReason("disallowed_tag", it) }
                .toList()

        if (disallowedTags.isNotEmpty()) return (reasons + disallowedTags).distinct()

        val nodes =
            try {
                TemplateParser.parse(source)
            } catch (e: TemplateParseException) {
                return (reasons + LintReason("parse", e.reason)).distinct()
            }

        reasons +=
            TemplateAst
                .filters(nodes)
                .filter { it !in TemplateRenderer.ALLOWED_FILTERS }
                .distinct()
                .map { LintReason("disallowed_filter", it) }

        return reasons.distinct()
    }

    private fun whitespaceControlReasons(source: String): List<LintReason> {
        val tokens =
            try {
                TemplateLexer.scan(source)
            } catch (_: TemplateParseException) {
                return emptyList()
            }
        val markers = mutableListOf<String>()
        for (token in tokens) {
            when (token) {
                is OutputToken -> {
                    if (token.trimLeft) markers += "{{-"
                    if (token.trimRight) markers += "-}}"
                }

                is TagToken -> {
                    if (token.trimLeft) markers += "{%-"
                    if (token.trimRight) markers += "-%}"
                }

                is TextToken -> Unit
            }
        }
        return markers.distinct().map { LintReason("whitespace_control", it) }
    }
}

internal object TemplateAst {
    private val OUTPUT_IDENT = Regex("\\{\\{-?\\s*([A-Za-z_][A-Za-z0-9_]*)")
    private val TAG_IDENT =
        Regex(
            "\\{%-?\\s*(?:if|unless|elsif)\\s+([A-Za-z_][A-Za-z0-9_]*)|" +
                "\\{%-?\\s*for\\s+\\w+\\s+in\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )
    private val RESERVED = setOf("forloop", "true", "false", "nil", "null", "empty", "blank")

    fun variables(source: String): List<String> {
        val nodes =
            try {
                TemplateParser.parse(source)
            } catch (_: TemplateParseException) {
                return regexVariables(source)
            }
        val referenced = mutableListOf<String>()
        val bound = mutableSetOf<String>()
        walk(nodes, referenced, bound, mutableListOf())
        return referenced.filter { it !in bound && it !in RESERVED }.distinct().sorted()
    }

    fun filters(nodes: List<Node>): List<String> {
        val names = mutableListOf<String>()
        walk(nodes, mutableListOf(), mutableSetOf(), names)
        return names
    }

    private fun walk(
        nodes: List<Node>,
        referenced: MutableList<String>,
        bound: MutableSet<String>,
        filters: MutableList<String>,
    ) {
        for (node in nodes) {
            when (node) {
                is TextNode, BreakNode, ContinueNode -> Unit
                is OutputNode -> walkExpr(node.expr, referenced, filters)
                is AssignNode -> {
                    bound += node.target
                    walkExpr(node.expr, referenced, filters)
                }

                is IfNode -> {
                    for (branch in node.branches) {
                        walkExpr(branch.condition, referenced, filters)
                        walk(branch.body, referenced, bound, filters)
                    }
                    node.elseBody?.let { walk(it, referenced, bound, filters) }
                }

                is UnlessNode -> {
                    walkExpr(node.condition, referenced, filters)
                    walk(node.body, referenced, bound, filters)
                }

                is ForNode -> {
                    bound += node.variable
                    walkExpr(node.enumerable, referenced, filters)
                    walk(node.body, referenced, bound, filters)
                    node.elseBody?.let { walk(it, referenced, bound, filters) }
                }
            }
        }
    }

    private fun walkExpr(
        expr: Expr,
        referenced: MutableList<String>,
        filters: MutableList<String>,
    ) {
        when (expr) {
            is LiteralExpr -> Unit
            is VariableExpr -> referenced += expr.root
            is BinaryExpr -> {
                walkExpr(expr.left, referenced, filters)
                walkExpr(expr.right, referenced, filters)
            }

            is FilteredExpr -> {
                walkExpr(expr.base, referenced, filters)
                for (filter in expr.filters) {
                    filters += filter.name
                    for (arg in filter.args) walkExpr(arg, referenced, filters)
                }
            }
        }
    }

    private fun regexVariables(source: String): List<String> {
        val names = mutableListOf<String>()
        OUTPUT_IDENT.findAll(source).forEach { names += it.groupValues[1] }
        TAG_IDENT.findAll(source).forEach { match ->
            match.groupValues
                .drop(1)
                .filter { it.isNotEmpty() }
                .forEach { names += it }
        }
        return names.filter { it !in RESERVED }.distinct().sorted()
    }
}
