package com.cayana.source

/**
 * Represents the typed outcome of an external source existence check.
 *
 * - [Exists]: The source file definitively exists and is accessible.
 * - [Missing]: The source file is definitively absent / deleted (e.g. MediaStore row gone, FileNotFoundException).
 * - [Unavailable]: The check could not be completed definitively due to permission revocation,
 *   ContentProvider unmounted, SecurityException, or transient I/O issues.
 *   In this case, the source's persisted existence MUST NOT be altered to false.
 */
sealed interface SourceExistence {
    data object Exists : SourceExistence
    data object Missing : SourceExistence
    data class Unavailable(val cause: Throwable? = null) : SourceExistence
}
