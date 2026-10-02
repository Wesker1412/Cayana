package com.cayana.core.permission

/**
 * Represents the combined state of user UI preference and OS permission.
 * UI preference (wants source enabled) and OS permission state (system granted)
 * must never be collapsed into a single Boolean.
 */
enum class SourceStatus {
    /** The user explicitly turned this source OFF in UI preferences. */
    DISABLED,

    /** The user turned this source ON and the necessary OS permission is GRANTED. */
    ENABLED_AND_AUTHORIZED,

    /** The user turned this source ON, but required OS permission has not yet been granted. */
    ENABLED_PERMISSION_REQUIRED,

    /** The user turned this source ON, but the OS permission was DENIED by the user/system. */
    PERMISSION_DENIED
}
