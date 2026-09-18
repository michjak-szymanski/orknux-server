package io.mszymanski.orknux.server.obj

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * A workspace object's shape, said to a model and checked against an answer.
 *
 * The two halves of holding an answer to a shape: what the model is told, and
 * what its answer is measured against. They live together because they have to
 * agree — a schema described one way and checked another is a model that did
 * what it was told and failed anyway.
 *
 * The check is the webhook's rule, spelled the webhook's way: extra fields are
 * allowed, because a model that adds a note beside the fields it was asked for
 * has not broken the contract the workflow was written against; a missing or
 * mistyped field has. Recursion is bounded the same way, because objects can
 * point at each other and an answer should not be able to make us walk a
 * circle. `WebhookAPI` keeps a copy of the same walk for payloads.
 */
@Component
class ObjectShapes(private val objects: WorkflowObjectRepository) {

    /**
     * The shape as a model is told it: a JSON object literal with the type in
     * each field's place, and the field's description beside it where the
     * author wrote one. Not a formal JSON Schema, because what reads this is a
     * model — a worked example is what models actually follow, and the schema
     * dialect would spend tokens saying `"type": "object"` where `{` already
     * has.
     */
    fun described(objectId: Long): String? {
        val shape = objects.findByIdOrNull(objectId) ?: return null
        return buildString {
            append(shape.name)
            shape.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            append("\n")
            append(literal(shape, depth = 0))
        }
    }

    /**
     * What is wrong with [answer] against the shape, in sentences; empty means
     * it complies. Sentences rather than a boolean, because they go back to the
     * model on retry and "no" re-teaches nothing.
     */
    fun problems(objectId: Long, answer: JsonNode): List<String> {
        val shape = objects.findByIdOrNull(objectId)
            ?: return listOf("the shape this answer is held to has been deleted")
        val found = mutableListOf<String>()
        check(answer, shape.properties, path = "", depth = 0, found = found)
        return found
    }

    private fun literal(shape: WorkflowObject, depth: Int): String {
        if (depth > MAX_DEPTH) return "{}"
        val pad = "  ".repeat(depth + 1)
        val fields = shape.properties.joinToString(",\n") { property ->
            val said = property.description?.takeIf { it.isNotBlank() }?.let { "  // $it" }.orEmpty()
            "$pad\"${property.name}\": ${typeOf(property, depth)}$said"
        }
        return "{\n$fields\n${"  ".repeat(depth)}}"
    }

    private fun typeOf(property: ObjectProperty, depth: Int): String = when (property.kind) {
        PropertyKind.STRING -> "string"
        PropertyKind.NUMBER -> "number"
        PropertyKind.BOOLEAN -> "boolean"
        PropertyKind.ARRAY -> "[${elementOf(property, depth)}, …]"
        PropertyKind.OBJECT -> property.refObjectId?.let { ref ->
            objects.findByIdOrNull(ref)?.let { literal(it, depth + 1) }
        } ?: "{…}"
    }

    private fun elementOf(property: ObjectProperty, depth: Int): String = when (property.elementKind) {
        PropertyKind.STRING -> "string"
        PropertyKind.NUMBER -> "number"
        PropertyKind.BOOLEAN -> "boolean"
        PropertyKind.ARRAY -> "[…]"
        PropertyKind.OBJECT, null -> property.refObjectId?.let { ref ->
            objects.findByIdOrNull(ref)?.let { literal(it, depth + 1) }
        } ?: "{…}"
    }

    private fun check(sent: JsonNode, properties: List<ObjectProperty>, path: String, depth: Int, found: MutableList<String>) {
        if (!sent.isObject) {
            found += "${path.ifEmpty { "the answer" }} has to be a JSON object"
            return
        }
        if (depth > MAX_DEPTH) return

        properties.forEach { property ->
            val at = if (path.isEmpty()) property.name else "$path.${property.name}"
            val value = sent.get(property.name)
            if (value == null || value.isNull) {
                found += "$at is missing"
                return@forEach
            }
            when (property.kind) {
                PropertyKind.STRING -> if (!value.isTextual) found += "$at has to be a string"
                PropertyKind.NUMBER -> if (!value.isNumber) found += "$at has to be a number"
                PropertyKind.BOOLEAN -> if (!value.isBoolean) found += "$at has to be true or false"
                PropertyKind.ARRAY ->
                    if (!value.isArray) {
                        found += "$at has to be an array"
                    } else {
                        value.forEachIndexed { index, held -> element(held, property, "$at[$index]", depth, found) }
                    }

                PropertyKind.OBJECT ->
                    if (!value.isObject) {
                        found += "$at has to be an object"
                    } else {
                        nested(value, property, at, depth, found)
                    }
            }
        }
    }

    private fun nested(value: JsonNode, property: ObjectProperty, path: String, depth: Int, found: MutableList<String>) {
        val shape = property.refObjectId?.let { objects.findByIdOrNull(it) } ?: return
        check(value, shape.properties, path, depth + 1, found)
    }

    private fun element(held: JsonNode, property: ObjectProperty, path: String, depth: Int, found: MutableList<String>) {
        when (property.elementKind) {
            PropertyKind.STRING -> if (!held.isTextual) found += "$path has to be a string"
            PropertyKind.NUMBER -> if (!held.isNumber) found += "$path has to be a number"
            PropertyKind.BOOLEAN -> if (!held.isBoolean) found += "$path has to be true or false"
            PropertyKind.ARRAY -> if (!held.isArray) found += "$path has to be an array"
            PropertyKind.OBJECT, null ->
                if (!held.isObject) {
                    found += "$path has to be an object"
                } else {
                    nested(held, property, path, depth, found)
                }
        }
    }

    private companion object {
        /** Deep enough for a shape somebody drew, shallow enough to be a bound. */
        const val MAX_DEPTH = 5
    }
}
