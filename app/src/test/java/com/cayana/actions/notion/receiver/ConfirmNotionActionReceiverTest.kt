package com.cayana.actions.notion.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.cayana.actions.notion.auth.FakeCayanaLogger
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.client.NotionCreatedPage
import com.cayana.actions.notion.data.NotionActionEntity
import com.cayana.actions.notion.destination.FakeNotionActionDao
import com.cayana.actions.notion.destination.FakeNotionAuthManager
import com.cayana.actions.notion.destination.FakeNotionClient
import com.cayana.actions.notion.destination.NotionDestination
import com.cayana.core.common.Result
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.NotionTransmissionPolicy
import com.cayana.ui.settings.repository.UserSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmNotionActionReceiverTest {

    private lateinit var context: Context
    private lateinit var dao: FakeNotionActionDao
    private lateinit var fakeClient: FakeNotionClient
    private lateinit var destination: NotionDestination
    private lateinit var receiver: ConfirmNotionActionReceiver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dao = FakeNotionActionDao()

        val fakeAuthManager = FakeNotionAuthManager()
        fakeAuthManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        fakeAuthManager.tokenToReturn = "valid_token"

        fakeClient = FakeNotionClient()
        fakeClient.createPageResultToReturn = Result.Success(
            NotionCreatedPage(id = "page_123", url = "https://notion.so/page_123", title = "Confirm Me")
        )

        val settingsRepo = InMemorySettingsRepository(
            initialSettings = UserSettings(
                isNotionConnected = true,
                notionAuthStatus = NotionAuthStatus.CONNECTED,
                notionTransmissionPolicy = NotionTransmissionPolicy.ASK_EACH_TIME,
                selectedNotionTargetId = "target_1",
                selectedNotionTargetTitle = "Target Page",
                selectedNotionTargetType = "PAGE"
            )
        )

        destination = NotionDestination(
            notionActionDao = dao,
            notionAuthManager = fakeAuthManager,
            notionClient = fakeClient,
            settingsRepository = settingsRepo,
            logger = FakeCayanaLogger()
        )

        receiver = ConfirmNotionActionReceiver(
            injectedDestination = destination,
            injectedDao = dao
        )
    }

    @Test
    fun ignoreDecisionTransitionsActionToIgnored() = runTest {
        dao.insert(
            NotionActionEntity(
                id = "action_to_ignore",
                sourceMemoryId = "mem_1",
                actionType = "NOTION_PAGE_CREATE",
                targetId = "target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Ignore Me"
            )
        )

        val intent = Intent().apply {
            putExtra(ConfirmNotionActionReceiver.EXTRA_DECISION, ConfirmNotionActionReceiver.DECISION_IGNORE)
            putExtra(ConfirmNotionActionReceiver.EXTRA_ACTION_ID, "action_to_ignore")
            putExtra(ConfirmNotionActionReceiver.EXTRA_NOTIFICATION_ID, 123)
        }

        receiver.handleIntent(context, intent)

        val action = dao.getActionById("action_to_ignore")
        assertNotNull(action)
        assertEquals("IGNORED", action!!.status)
    }

    @Test
    fun confirmDecisionExecutesConfirmedNote() = runTest {
        dao.insert(
            NotionActionEntity(
                id = "action_to_confirm",
                sourceMemoryId = "mem_2",
                actionType = "NOTION_PAGE_CREATE",
                targetId = "target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Confirm Me"
            )
        )

        val intent = Intent().apply {
            putExtra(ConfirmNotionActionReceiver.EXTRA_DECISION, ConfirmNotionActionReceiver.DECISION_CONFIRM)
            putExtra(ConfirmNotionActionReceiver.EXTRA_ACTION_ID, "action_to_confirm")
            putExtra(ConfirmNotionActionReceiver.EXTRA_NOTIFICATION_ID, 456)
        }

        receiver.handleIntent(context, intent)

        val action = dao.getActionById("action_to_confirm")
        assertNotNull(action)
        assertEquals("CREATED", action!!.status)
        assertEquals("page_123", action.destinationPageId)
    }
}
