package com.oguzhanaslann.roomer.model

/** Outcome of parsing one schema file. Parsing never throws for bad input (spec FR-12, NFR-06). */
sealed interface ParseResult {
    data class Success(val database: Database) : ParseResult
    data class Failure(val error: SchemaError) : ParseResult
}

/** Typed parse error. [reason] is human readable and does not repeat [filePath]. */
sealed interface SchemaError {
    val filePath: String
    val reason: String

    /** The file is not syntactically valid JSON (or is empty / not a JSON object). */
    data class MalformedJson(override val filePath: String, override val reason: String) : SchemaError

    /** `formatVersion` is present but not supported. [found] is the raw value from the file. */
    data class UnsupportedFormat(
        override val filePath: String,
        val found: String,
        val supported: Int = SUPPORTED_FORMAT_VERSION,
    ) : SchemaError {
        override val reason: String
            get() = "Unsupported schema formatVersion $found (supported: $supported)."
    }

    /** A required field is absent or has the wrong JSON type. [fieldPath] e.g. `database.entities[2].tableName`. */
    data class MissingField(
        override val filePath: String,
        val fieldPath: String,
        override val reason: String,
    ) : SchemaError

    /** A foreign key, index or primary key refers to a table or column that does not exist. */
    data class UnknownReference(override val filePath: String, override val reason: String) : SchemaError

    companion object {
        const val SUPPORTED_FORMAT_VERSION: Int = 1
    }
}
