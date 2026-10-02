package com.cayana.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cayana.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * App Launch Runtime Smoke Test:
 * 1. Launches MainActivity on a fresh install (onboarding not completed).
 * 2. Completes Onboarding flow (Welcome -> Sources -> Calendar -> Backup -> Finish).
 * 3. Enters HomeScreen upon completing onboarding.
 * 4. Navigates to SettingsScreen to verify Stage 1 options (Sources, Calendar, Backup).
 * 5. Returns cleanly to HomeScreen without any runtime crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLaunchSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchOnboardingAndNavigateToSettingsAndBackSmokeTest() {
        // 1. Welcome Screen
        composeTestRule.onNodeWithText("Cayana").assertIsDisplayed()
        composeTestRule.onNodeWithText("把你看過的東西，\n變成找得回來的記憶。").assertIsDisplayed()
        composeTestRule.onNodeWithText("開始").performClick()

        // 2. Sources Screen
        composeTestRule.onNodeWithText("讓 Cayana 記住什麼？").assertIsDisplayed()
        composeTestRule.onNodeWithText("Screenshots").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一步").performClick()

        // 3. Calendar Screen
        composeTestRule.onNodeWithText("選擇行事曆").assertIsDisplayed()
        composeTestRule.onNodeWithText("稍後設定").performClick()

        // 4. Backup Screen
        composeTestRule.onNodeWithText("備份你的 Memory").assertIsDisplayed()
        composeTestRule.onNodeWithText("稍後設定").performClick()

        // 5. Finish Screen
        composeTestRule.onNodeWithText("現在可以關掉 Cayana 了。").assertIsDisplayed()
        composeTestRule.onNode(hasText("完成") and hasClickAction()).performClick()

        // Wait for background DataStore write and navigation transition to Home Screen
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText("Personal Memory Layer"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 6. Home Screen
        composeTestRule.onNodeWithText("Cayana").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal Memory Layer").assertIsDisplayed()

        // 7. Navigate to Settings
        composeTestRule.onNodeWithContentDescription("Open Settings").performClick()

        // 8. Settings Screen is displayed
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Memory Sources").assertIsDisplayed()
        composeTestRule.onNodeWithText("Calendar").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Backup").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy & Safety Principles").performScrollTo().assertIsDisplayed()

        // 9. Return to Home
        composeTestRule.onNodeWithContentDescription("Back to Home").performClick()

        // 10. Back on Home screen cleanly
        composeTestRule.onNodeWithText("Cayana").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal Memory Layer").assertIsDisplayed()
    }
}
