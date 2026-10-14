package dev.polimo.prompton

import java.util.Collections

internal object Decisions {
    fun render(
        template: DecisionTemplate,
        variables: Map<String, Any?>?,
        engine: TemplateEngine,
    ): RenderedDecision {
        validate(template.state, template.questions)
        val vars = variables ?: emptyMap()
        val state = renderValue(template.state, vars, engine)
        val questions =
            literalMap(
                template.questions.mapValues { (_, question) ->
                    val raw = objectMap(question, "Decisions question must be an object")
                    val rendered = LinkedHashMap(raw)
                    raw["instructions"]?.let {
                        rendered["instructions"] = renderValue(it, vars, engine)
                    }
                    if (raw.containsKey("criteria")) {
                        rendered["criteria"] = renderValue(raw["criteria"], vars, engine)
                    }
                    rendered
                },
            )
        validate(state, questions)
        return RenderedDecision(state = state, questions = questions)
    }

    fun validate(
        state: Any?,
        questions: Map<String, Any?>,
    ) {
        requireGuidance(state, "Decisions state must be a string, JSON object, or JSON array")
        if (questions.isEmpty()) throw PreparedRequestException("Decisions questions must be a non-empty object")
        for ((name, question) in questions) {
            if (name.isBlank()) throw PreparedRequestException("Decisions question names must be non-empty strings")
            validateQuestion(name, question)
        }
    }

    fun json(value: Any?): Boolean =
        when (value) {
            null, is String, is Number, is Boolean -> true
            is List<*> -> value.all(::json)
            is Map<*, *> -> value.all { (key, nested) -> key is String && json(nested) }
            else -> false
        }

    fun freeze(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                Collections.unmodifiableMap(
                    value.entries.associate { (key, nested) -> key.toString() to freeze(nested) },
                )

            is List<*> -> Collections.unmodifiableList(value.map(::freeze))
            else -> value
        }

    @Suppress("UNCHECKED_CAST")
    fun map(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun freezeMap(value: Map<String, Any?>): Map<String, Any?> = freeze(value) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun objectMap(
        value: Any?,
        message: String,
    ): Map<String, Any?> = value as? Map<String, Any?> ?: throw PreparedRequestException(message)

    private fun literalMap(value: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        value.forEach { (key, nested) -> out[key] = nested }
        return out
    }

    private fun renderValue(
        value: Any?,
        variables: Map<String, Any?>,
        engine: TemplateEngine,
    ): Any? =
        when (value) {
            is String -> Template.render(value, variables, engine)
            is List<*> -> value.map { renderValue(it, variables, engine) }
            is Map<*, *> ->
                literalMap(
                    value.entries.associate { (key, nested) ->
                        key.toString() to renderValue(nested, variables, engine)
                    },
                )

            else -> value
        }

    private fun validateQuestion(
        name: String,
        value: Any?,
    ) {
        val question = objectMap(value, "Decisions question $name must be an object")
        val unknown = question.keys - setOf("type", "instructions", "criteria")
        if (unknown.isNotEmpty()) {
            throw PreparedRequestException("Decisions question $name accepts only type, instructions, and criteria")
        }
        val type = question["type"]
        when (type) {
            "choice" -> validateChoice(name, question)
            "noul" -> validateNoul(name, question)
            "score" -> validateScore(name, question)
            else -> throw PreparedRequestException("Decisions question $name has an unsupported type")
        }
    }

    private fun validateChoice(
        name: String,
        question: Map<String, Any?>,
    ) {
        validateInstructions(name, question)
        val criteria = objectMap(
            question["criteria"],
            "Decisions choice question $name criteria must be a non-empty object",
        )
        if (criteria.isEmpty()) {
            throw PreparedRequestException("Decisions choice question $name criteria must be a non-empty object")
        }
        if (criteria.size > 255) {
            throw PreparedRequestException("Decisions choice question $name criteria must contain at most 255 choices")
        }
        if (criteria.any { (choice, guidance) -> choice.isBlank() || !(guidance == null || isGuidance(guidance)) }) {
            throw PreparedRequestException("Decisions choice question $name criteria must map choices to guidance")
        }
    }

    private fun validateNoul(
        name: String,
        question: Map<String, Any?>,
    ) {
        validateInstructions(name, question)
        val criteria = question["criteria"] ?: return
        val criteriaMap = objectMap(criteria, "Decisions noul question $name criteria must include true and false")
        if (criteriaMap.keys != setOf("true", "false")) {
            throw PreparedRequestException("Decisions noul question $name criteria must include true and false")
        }
        if (!isGuidance(criteriaMap["true"]) || !isGuidance(criteriaMap["false"])) {
            throw PreparedRequestException("Decisions noul question $name criteria values must be guidance")
        }
    }

    private fun validateScore(
        name: String,
        question: Map<String, Any?>,
    ) {
        validateInstructions(name, question)
        val criteria = question["criteria"] as? List<*>
        if (criteria == null || criteria.size !in 2..10 || !criteria.all(::isGuidance)) {
            throw PreparedRequestException(
                "Decisions score question $name criteria must be a list of 2 to 10 guidance values",
            )
        }
    }

    private fun validateInstructions(
        name: String,
        question: Map<String, Any?>,
    ) {
        if (!isGuidance(question["instructions"])) {
            throw PreparedRequestException("Decisions question $name must include instructions")
        }
    }

    private fun requireGuidance(
        value: Any?,
        message: String,
    ) {
        if (!isGuidance(value)) throw PreparedRequestException(message)
    }

    private fun isGuidance(value: Any?): Boolean = value is String || value is Map<*, *> || value is List<*>
}
