package com.cayana.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cayana.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * App Launch Runtime Smoke Test:
 * 1. Launches MainActivity with CayanaApplication and Koin DI runtime.
 * 2. Verifies HomeScreen displays branding and title.
 * 3. Navigates to SettingsScreen via top-bar action.
 * 4. Verifies SettingsScreen displays source toggles and privacy principles.
 * 5. Navigates back to HomeScreen via top-bar back action.
 * 6. Verifies clean return to HomeScreen without any runtime crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLaunchSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchAndNavigateToSettingsAndBackSmokeTest() {
        // 1. App launches: Home screen displays branding & title
        composeTestRule.onNodeWithText("Cayana").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal Memory Layer").assertIsDisplayed()

        // 2. Navigate to Settings
        composeTestRule.onNodeWithContentDescription("Open Settings").performClick()

        // 3. Settings screen is displayed
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Memory Sources").assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy & Safety Principles").assertIsDisplayed()

        // 4. Return to Home
        composeTestRule.onNodeWithContentDescription("Back to Home").performClick()

        // 5. Back on Home screen cleanly
        composeTestRule.onNodeWithText("Cayana").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal Memory Layer").assertIsDisplayed()
    }
}
