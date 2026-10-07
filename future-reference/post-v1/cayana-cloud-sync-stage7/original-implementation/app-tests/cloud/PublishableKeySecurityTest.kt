package com.cayana.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PublishableKeySecurityTest {

    @Test
    fun productionSourcesContainZeroSecretKeysOrDatabasePasswords() {
        val rootDirs = listOf(
            File("app/src/main"),
            File("../app/src/main")
        )
        val mainDir = rootDirs.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find app/src/main directory")

        val forbiddenPatterns = listOf(
            "service_role",
            "sb_secret_",
            "database password",
            "db_password",
            "jwt_secret",
            "jwt signing secret"
        )

        val scannedFiles = mutableListOf<File>()
        val violations = mutableListOf<String>()

        mainDir.walkTopDown().forEach { file ->
            if (file.isFile && (file.extension in listOf("kt", "java", "xml", "json", "properties", "gradle", "kts"))) {
                scannedFiles.add(file)
                val text = file.readText().lowercase()
                for (forbidden in forbiddenPatterns) {
                    if (text.contains(forbidden.lowercase())) {
                        violations.add("Forbidden pattern '$forbidden' found in ${file.path}")
                    }
                }
            }
        }

        assertTrue("Should have scanned production files", scannedFiles.isNotEmpty())
        assertTrue("No forbidden secrets or service keys must exist in production code: $violations", violations.isEmpty())
    }

    @Test
    fun buildGradleContainsOnlyPublishableKeyConfiguration() {
        val gradleFiles = listOf(
            File("app/build.gradle.kts"),
            File("../app/build.gradle.kts")
        )
        val gradleFile = gradleFiles.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find app/build.gradle.kts")

        val text = gradleFile.readText().lowercase()

        assertFalse("service_role key must never be configured in build.gradle.kts", text.contains("service_role"))
        assertFalse("sb_secret_ must never be configured in build.gradle.kts", text.contains("sb_secret_"))
        assertFalse("database password must never be in build.gradle.kts", text.contains("database password"))

        // Publishable key is permitted
        assertTrue(
            "CAYANA_SUPABASE_PUBLISHABLE_KEY must be configured in build.gradle.kts",
            text.contains("cayana_supabase_publishable_key")
        )
    }
}
