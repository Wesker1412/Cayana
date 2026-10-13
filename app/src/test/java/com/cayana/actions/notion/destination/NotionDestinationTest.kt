package com.cayana.actions.notion.destination

import com.cayana.actions.destination.DefaultDestinationRegistry
import com.cayana.actions.model.ActionCandidate
import com.cayana.actions.model.ActionCandidateType
import com.cayana.actions.model.DestinationExecutionResult
import com.cayana.actions.model.RoutingDecision
import com.cayana.actions.notion.auth.FakeCayanaLogger
import com.cayana.actions.notion.auth.NotionAuthRequiredException
import com.cayana.actions.notion.auth.NotionAuthStatus
import com.cayana.actions.notion.auth.NotionAuthorizationManager
import com.cayana.actions.notion.auth.NotionRateLimitedException
import com.cayana.actions.notion.auth.NotionServerException
import com.cayana.actions.notion.client.NotionClient
import com.cayana.actions.notion.client.NotionCreatedPage
import com.cayana.actions.notion.client.NotionSearchResponse
import com.cayana.actions.notion.client.NotionTarget
import com.cayana.actions.notion.client.NotionTargetType
import com.cayana.actions.notion.data.NotionActionDao
import com.cayana.actions.notion.data.NotionActionEntity
import com.cayana.actions.router.DefaultActionRouter
import com.cayana.actions.router.DefaultActionRoutingHistoryTracker
import com.cayana.actions.classifier.ActionClassifier
import com.cayana.core.common.Result
import com.cayana.memory.model.MemoryItem
import com.cayana.source.SourceType
import com.cayana.ui.settings.repository.InMemorySettingsRepository
import com.cayana.ui.settings.repository.NotionTransmissionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId

class NotionDestinationTest {

    private lateinit var dao: FakeNotionActionDao
    private lateinit var authManager: FakeNotionAuthManager
    private lateinit var notionClient: FakeNotionClient
    private lateinit var settingsRepository: InMemorySettingsRepository
    private lateinit var logger: FakeCayanaLogger
    private lateinit var destination: NotionDestination

    @Before
    fun setup() {
        dao = FakeNotionActionDao()
        authManager = FakeNotionAuthManager()
        notionClient = FakeNotionClient()
        settingsRepository = InMemorySettingsRepository()
        logger = FakeCayanaLogger()
        destination = NotionDestination(
            notionActionDao = dao,
            notionAuthManager = authManager,
            notionClient = notionClient,
            settingsRepository = settingsRepository,
            logger = logger
        )
    }

    @Test
    fun notionDestinationDefaultIsOffAndUnconfigured() = runTest {
        assertFalse(destination.isConfigured())
        assertFalse(destination.isAuthorized())
    }

    @Test
    fun unconfiguredDestinationNeverUploadsContent() = runTest {
        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "My Private Note",
            notes = "Super sensitive local memory text",
            confidence = 0.95f,
            sourceMemoryId = "mem_123"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Failure)
        assertFalse((result as DestinationExecutionResult.Failure).canRetry)
        assertEquals(0, notionClient.createdPages.size)
        assertEquals(0, dao.actions.size)
    }

    @Test
    fun unauthorizedDestinationReturnsNeedsAttentionAndNeverUploads() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page", "page")

        authManager.setAuthStatus(NotionAuthStatus.DISCONNECTED)

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Meeting Summary",
            notes = "Discussed roadmap",
            confidence = 0.9f,
            sourceMemoryId = "mem_456"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun successfulNoteCreationPersistsEntityAndCreatesPage() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page", "page")

        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "valid_bearer_token_abc"

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Project Roadmap",
            notes = "Release MVP next week",
            confidence = 0.95f,
            sourceMemoryId = "mem_789"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Success)
        val success = result as DestinationExecutionResult.Success
        assertNotNull(success.destinationReferenceId)
        assertEquals(1, notionClient.createdPages.size)

        val entity = dao.getAction("mem_789", NotionDestination.ACTION_TYPE_CREATE, "target_page_1")
        assertNotNull(entity)
        assertEquals("CREATED", entity!!.status)
        assertEquals(success.destinationReferenceId, entity.destinationPageId)
    }

    @Test
    fun idempotentExecutionReturnsExistingCreatedPageWithoutRecreating() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        dao.insert(
            NotionActionEntity(
                id = "action_existing",
                sourceMemoryId = "mem_idempotent",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "target_page_1",
                targetTitle = "Notes Page",
                targetType = "PAGE",
                destinationPageId = "page_already_created_123",
                status = "CREATED",
                title = "Existing Title"
            )
        )

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Existing Title",
            confidence = 0.95f,
            sourceMemoryId = "mem_idempotent"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Success)
        assertEquals("page_already_created_123", (result as DestinationExecutionResult.Success).destinationReferenceId)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun databaseTargetDirectWritingReturnsNeedsAttention() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("db_container_1", "Container DB", "database")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "DB Direct Note",
            confidence = 0.9f,
            sourceMemoryId = "mem_db"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        assertTrue((result as DestinationExecutionResult.NeedsAttention).reason.contains("Database"))
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun confirmPendingActionReturnsSkippedForAutoExecution() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)

        dao.insert(
            NotionActionEntity(
                id = "action_pending",
                sourceMemoryId = "mem_pending",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Pending Confirmation"
            )
        )

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Pending Confirmation",
            confidence = 0.95f,
            sourceMemoryId = "mem_pending"
        )

        val result = destination.createNote(candidate)

        assertEquals(DestinationExecutionResult.Skipped, result)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun executeConfirmedNoteAtomicallyClaimsAndCreatesPage() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        dao.insert(
            NotionActionEntity(
                id = "action_to_confirm",
                sourceMemoryId = "mem_to_confirm",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Confirmed Page Title"
            )
        )

        val result = destination.executeConfirmedNote("action_to_confirm")

        assertTrue(result is DestinationExecutionResult.Success)
        assertEquals(1, notionClient.createdPages.size)
        val entity = dao.getActionById("action_to_confirm")
        assertEquals("CREATED", entity!!.status)
    }

    @Test
    fun executeConfirmedNoteFailsIfTargetChanged() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        settingsRepository.updateSelectedNotionTarget("new_target_2", "New Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)

        dao.insert(
            NotionActionEntity(
                id = "action_old_target",
                sourceMemoryId = "mem_old_target",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "old_target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Old Target Title"
            )
        )

        val result = destination.executeConfirmedNote("action_old_target")

        assertTrue(result is DestinationExecutionResult.Failure)
        assertEquals(0, notionClient.createdPages.size)
        val entity = dao.getActionById("action_old_target")
        assertEquals("CANCELLED", entity!!.status)
    }

    @Test
    fun networkTimeoutDowngradesToNeedsAttentionWithoutBlindRetry() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        notionClient.createPageResultToReturn = Result.Error(SocketTimeoutException("Read timeout"))

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Timeout Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_timeout"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        val entity = dao.getAction("mem_timeout", NotionDestination.ACTION_TYPE_CREATE, "target_1")
        assertEquals("NEEDS_ATTENTION", entity!!.status)
    }

    @Test
    fun tokenNeverAppearsInLogs() = runTest {
        val secretToken = "super_secret_bearer_987654321"

        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_page_1", "Notes Page", "page")

        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = secretToken

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Log Privacy Test",
            notes = "Content",
            confidence = 0.9f,
            sourceMemoryId = "mem_privacy"
        )

        destination.createNote(candidate)

        for (log in logger.logs) {
            assertFalse("Token must never leak into logs: $log", log.contains(secretToken))
        }
    }

    @Test
    fun actionRouterEvaluatesNotionAsMemoryOnlyWhenUnconfigured() = runTest {
        val registry = DefaultDestinationRegistry(listOf(destination))
        val fakeClassifier = object : ActionClassifier {
            override fun classify(
                memoryItem: MemoryItem,
                rawText: String?,
                referenceTime: Instant,
                zoneId: ZoneId
            ): List<ActionCandidate> {
                return listOf(
                    ActionCandidate(
                        type = ActionCandidateType.NOTE,
                        title = "Unconfigured Test Note",
                        notes = "Note content",
                        confidence = 0.92f,
                        sourceMemoryId = memoryItem.id
                    )
                )
            }
        }

        val router = DefaultActionRouter(
            classifier = fakeClassifier,
            destinationRegistry = registry,
            routingHistoryTracker = DefaultActionRoutingHistoryTracker(),
            logger = logger
        )

        val memoryItem = MemoryItem(
            id = "mem_unconf",
            sourceType = SourceType.SCREENSHOT,
            capturedAt = 1000L,
            rawText = "Note test"
        )

        val decisions = router.route(memoryItem, "Note test", Instant.now(), ZoneId.systemDefault())

        assertEquals(1, decisions.size)
        assertTrue(decisions[0] is RoutingDecision.MemoryOnly)
        assertEquals("Destination for NOTE is not configured", (decisions[0] as RoutingDecision.MemoryOnly).reason)
    }

    @Test
    fun policyOffInvalidatesOldConfirmation() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        val actionId = "action_policy_off"
        dao.insert(
            NotionActionEntity(
                id = actionId,
                sourceMemoryId = "mem_policy_off",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Pending Note"
            )
        )

        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.OFF)

        val result = destination.executeConfirmedNote(actionId)

        assertTrue(result is DestinationExecutionResult.Failure)
        assertFalse((result as DestinationExecutionResult.Failure).canRetry)
        val entity = dao.getActionById(actionId)
        assertEquals("CANCELLED", entity!!.status)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun disconnectBeforeConfirmedPostBlocksTransfer() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.DISCONNECTED)

        val actionId = "action_disconnect_gate"
        dao.insert(
            NotionActionEntity(
                id = actionId,
                sourceMemoryId = "mem_disconnect",
                actionType = NotionDestination.ACTION_TYPE_CREATE,
                targetId = "target_1",
                targetType = "PAGE",
                status = "CONFIRM_PENDING",
                title = "Disconnect Note"
            )
        )

        val result = destination.executeConfirmedNote(actionId)

        assertTrue(result is DestinationExecutionResult.Failure)
        val entity = dao.getActionById(actionId)
        assertEquals("CANCELLED", entity!!.status)
        assertEquals(0, notionClient.createdPages.size)
    }

    @Test
    fun ambiguousIOExceptionCannotRetryPost() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        notionClient.createPageResultToReturn = Result.Error(IOException("Connection reset by peer"))

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Ambiguous IO Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_io_ambiguous"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        val entity = dao.getAction("mem_io_ambiguous", NotionDestination.ACTION_TYPE_CREATE, "target_1")
        assertNotNull(entity)
        assertEquals("NEEDS_ATTENTION", entity!!.status)

        val claimed = dao.claimRetryAction(entity.id)
        assertEquals(0, claimed)
    }

    @Test
    fun `429And5xxCannotBlindlyRetry`() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        // 429 Rate Limited
        notionClient.createPageResultToReturn = Result.Error(NotionRateLimitedException("Rate limited"))
        val candidate429 = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "429 Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_429"
        )
        val result429 = destination.createNote(candidate429)
        assertTrue(result429 is DestinationExecutionResult.NeedsAttention)
        val entity429 = dao.getAction("mem_429", NotionDestination.ACTION_TYPE_CREATE, "target_1")
        assertEquals("NEEDS_ATTENTION", entity429!!.status)
        assertEquals(0, dao.claimRetryAction(entity429.id))

        // 503 Server Error
        notionClient.createPageResultToReturn = Result.Error(NotionServerException(503, "Service Unavailable"))
        val candidate503 = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "503 Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_503"
        )
        val result503 = destination.createNote(candidate503)
        assertTrue(result503 is DestinationExecutionResult.NeedsAttention)
        val entity503 = dao.getAction("mem_503", NotionDestination.ACTION_TYPE_CREATE, "target_1")
        assertEquals("NEEDS_ATTENTION", entity503!!.status)
        assertEquals(0, dao.claimRetryAction(entity503.id))
    }

    @Test
    fun sameTitleDifferentPageCannotFakeRecovery() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        val action = NotionActionEntity(
            id = "action_fake_recovery",
            sourceMemoryId = "mem_target_orig",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "target_1",
            targetTitle = "Target",
            targetType = "PAGE",
            status = "NEEDS_ATTENTION",
            title = "Same Title Note",
            attemptCount = 1
        )
        dao.insert(action)

        // Case A: Page in different parent
        notionClient.searchTargetsResultToReturn = Result.Success(
            NotionSearchResponse(
                targets = listOf(
                    NotionTarget(
                        id = "page_different_parent",
                        title = "Same Title Note",
                        type = NotionTargetType.PAGE,
                        parentId = "other_parent_999",
                        isSelectable = true
                    )
                )
            )
        )
        notionClient.verifyPageReferenceResultToReturn = Result.Success(true)

        val resultDiffParent = destination.reconcileAction(action)
        assertTrue(resultDiffParent is DestinationExecutionResult.NeedsAttention)
        assertEquals("NEEDS_ATTENTION", dao.getActionById(action.id)!!.status)

        // Case B: Same parent, but missing Cayana provenance
        notionClient.searchTargetsResultToReturn = Result.Success(
            NotionSearchResponse(
                targets = listOf(
                    NotionTarget(
                        id = "page_no_reference",
                        title = "Same Title Note",
                        type = NotionTargetType.PAGE,
                        parentId = "target_1",
                        isSelectable = true
                    )
                )
            )
        )
        notionClient.verifyPageReferenceResultToReturn = Result.Success(false)

        val resultNoRef = destination.reconcileAction(action)
        assertTrue(resultNoRef is DestinationExecutionResult.NeedsAttention)
        assertEquals("NEEDS_ATTENTION", dao.getActionById(action.id)!!.status)
    }

    @Test
    fun existingActionMustMatchOriginalTarget() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        val oldAction = NotionActionEntity(
            id = "action_old",
            sourceMemoryId = "mem_multi_target",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "target_old",
            targetTitle = "Old Target",
            targetType = "PAGE",
            status = "CREATED",
            title = "Multi Target Note",
            destinationPageId = "page_old_1"
        )
        dao.insert(oldAction)

        settingsRepository.updateSelectedNotionTarget("target_new", "New Target", "page")

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Multi Target Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_multi_target"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Success)
        val newAction = dao.getAction("mem_multi_target", NotionDestination.ACTION_TYPE_CREATE, "target_new")
        assertNotNull(newAction)
        assertEquals("target_new", newAction!!.targetId)
        assertEquals("CREATED", newAction.status)
        assertEquals(2, dao.actions.size)
    }

    @Test
    fun schemaFailureDoesNotAssumeName() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("ds_123", "Data Source", "data_source")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        notionClient.createPageResultToReturn = Result.Error(IOException("Schema resolution failed"))

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Schema Fail Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_schema_fail"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.NeedsAttention)
        val entity = dao.getAction("mem_schema_fail", NotionDestination.ACTION_TYPE_CREATE, "ds_123")
        assertNotNull(entity)
        assertEquals("NEEDS_ATTENTION", entity!!.status)
    }

    @Test
    fun errorLogsDoNotLeakPrivateContent() = runTest {
        val privateTitle = "Private Bank Account Info"
        val privateContent = "Password is super_secret_12345"
        val secretToken = "secret_notion_api_token_xyz"
        val rawExceptionMsg = "Connection to 192.168.1.50:443 timed out with sensitive header"

        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")

        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = secretToken

        notionClient.createPageResultToReturn = Result.Error(IOException(rawExceptionMsg))

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = privateTitle,
            notes = privateContent,
            confidence = 0.95f,
            sourceMemoryId = "mem_private_log"
        )

        destination.createNote(candidate)

        for (log in logger.logs) {
            assertFalse("Private title must not leak into logs: $log", log.contains(privateTitle))
            assertFalse("Private content must not leak into logs: $log", log.contains(privateContent))
            assertFalse("Token must not leak into logs: $log", log.contains(secretToken))
            assertFalse("Raw exception message must not leak into logs: $log", log.contains(rawExceptionMsg))
        }
    }

    @Test
    fun autoToAskBeforePostBlocksAutoTransfer() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.AUTO_FOR_SELECTED_TYPES)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        // Switch to ASK_EACH_TIME during execution before POST dispatch
        authManager.onGetAccessToken = {
            settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        }

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Auto To Ask Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_auto_to_ask"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Failure)
        assertFalse((result as DestinationExecutionResult.Failure).canRetry)
        assertEquals(0, notionClient.createdPages.size)
        val action = dao.getAction("mem_auto_to_ask", NotionDestination.ACTION_TYPE_CREATE, "target_1")
        assertNotNull(action)
        assertEquals("CANCELLED", action!!.status)
    }

    @Test
    fun askPolicyCannotUseAutomaticCreatePath() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        val candidate = ActionCandidate(
            type = ActionCandidateType.NOTE,
            title = "Ask Policy Note",
            confidence = 0.95f,
            sourceMemoryId = "mem_ask_path"
        )

        val result = destination.createNote(candidate)

        assertTrue(result is DestinationExecutionResult.Failure)
        assertFalse((result as DestinationExecutionResult.Failure).canRetry)
        assertEquals(0, notionClient.createdPages.size)
        assertEquals(0, dao.actions.size)
    }

    @Test
    fun manualConfirmationRespectsCurrentPolicy() = runTest {
        settingsRepository.updateNotionAuthStatus(NotionAuthStatus.CONNECTED)
        settingsRepository.updateSelectedNotionTarget("target_1", "Target", "page")
        authManager.setAuthStatus(NotionAuthStatus.CONNECTED)
        authManager.tokenToReturn = "token"

        // Case A: Policy is ASK_EACH_TIME -> Manual confirmation succeeds
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.ASK_EACH_TIME)
        val actionAsk = NotionActionEntity(
            id = "action_ask_manual",
            sourceMemoryId = "mem_ask_manual",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "target_1",
            targetType = "PAGE",
            status = "CONFIRM_PENDING",
            title = "Manual Ask Note"
        )
        dao.insert(actionAsk)

        val resultAsk = destination.executeConfirmedNote("action_ask_manual")
        assertTrue(resultAsk is DestinationExecutionResult.Success)
        assertEquals("CREATED", dao.getActionById("action_ask_manual")!!.status)
        assertEquals(1, notionClient.createdPages.size)

        // Case B: Policy is OFF -> Manual confirmation fails and cancels
        settingsRepository.updateNotionTransmissionPolicy(NotionTransmissionPolicy.OFF)
        val actionOff = NotionActionEntity(
            id = "action_off_manual",
            sourceMemoryId = "mem_off_manual",
            actionType = NotionDestination.ACTION_TYPE_CREATE,
            targetId = "target_1",
            targetType = "PAGE",
            status = "CONFIRM_PENDING",
            title = "Manual Off Note"
        )
        dao.insert(actionOff)

        val resultOff = destination.executeConfirmedNote("action_off_manual")
        assertTrue(resultOff is DestinationExecutionResult.Failure)
        assertEquals("CANCELLED", dao.getActionById("action_off_manual")!!.status)
        assertEquals(1, notionClient.createdPages.size) // No additional page created
    }
}

class FakeNotionActionDao : NotionActionDao {
    val actions = mutableMapOf<String, NotionActionEntity>()

    override suspend fun insert(action: NotionActionEntity): Long {
        val conflict = actions.values.any {
            it.sourceMemoryId == action.sourceMemoryId &&
                    it.actionType == action.actionType &&
                    it.targetId == action.targetId
        }
        if (conflict) {
            throw Exception("UNIQUE constraint failed")
        }
        actions[action.id] = action
        return 1L
    }

    override suspend fun getActionById(id: String): NotionActionEntity? = actions[id]
    override suspend fun getById(id: String): NotionActionEntity? = actions[id]

    override suspend fun getAction(sourceMemoryId: String, actionType: String, targetId: String): NotionActionEntity? {
        return actions.values.firstOrNull {
            it.sourceMemoryId == sourceMemoryId && it.actionType == actionType && it.targetId == targetId
        }
    }

    override suspend fun getActionsForMemory(sourceMemoryId: String): List<NotionActionEntity> {
        return actions.values.filter { it.sourceMemoryId == sourceMemoryId }
    }

    override suspend fun updateStatusAndPage(id: String, status: String, destinationPageId: String?, destinationPageUrl: String?, updatedAt: Long): Int {
        val existing = actions[id] ?: return 0
        if (existing.status == "IGNORED") return 0
        actions[id] = existing.copy(status = status, destinationPageId = destinationPageId, destinationPageUrl = destinationPageUrl, updatedAt = updatedAt)
        return 1
    }

    override suspend fun updateStatusAndError(id: String, status: String, lastError: String?, updatedAt: Long): Int {
        val existing = actions[id] ?: return 0
        if (existing.status in listOf("CREATED", "IGNORED")) return 0
        actions[id] = existing.copy(status = status, lastError = lastError, updatedAt = updatedAt)
        return 1
    }

    override suspend fun updateStatus(id: String, status: String, updatedAt: Long): Int {
        val existing = actions[id] ?: return 0
        if (existing.status in listOf("CREATED", "IGNORED")) return 0
        actions[id] = existing.copy(status = status, updatedAt = updatedAt)
        return 1
    }

    override suspend fun claimRetryAction(id: String, maxAttempts: Int, updatedAt: Long): Int {
        val existing = actions[id] ?: return 0
        if ((existing.status == "FAILED" || existing.status == "CREATING_ERROR") && existing.attemptCount < maxAttempts) {
            actions[id] = existing.copy(status = "CREATING", attemptCount = existing.attemptCount + 1, updatedAt = updatedAt)
            return 1
        }
        return 0
    }

    override suspend fun claimConfirmAction(id: String, updatedAt: Long): Int {
        val existing = actions[id] ?: return 0
        if (existing.status == "CONFIRM_PENDING" || existing.status == "NEEDS_ATTENTION") {
            actions[id] = existing.copy(status = "CREATING", attemptCount = existing.attemptCount + 1, updatedAt = updatedAt)
            return 1
        }
        return 0
    }

    override suspend fun claimIgnoreAction(id: String, updatedAt: Long): Int {
        val existing = actions[id] ?: return 0
        if (existing.status == "CONFIRM_PENDING" || existing.status == "NEEDS_ATTENTION") {
            actions[id] = existing.copy(status = "IGNORED", updatedAt = updatedAt)
            return 1
        }
        return 0
    }

    override suspend fun getPendingConfirmActions(): List<NotionActionEntity> = actions.values.filter { it.status == "CONFIRM_PENDING" }
    override suspend fun getInFlightCreatingActions(limit: Int): List<NotionActionEntity> = actions.values.filter { it.status == "CREATING" }.take(limit)
    override suspend fun getNeedsAttentionActions(): List<NotionActionEntity> = actions.values.filter { it.status == "NEEDS_ATTENTION" }
    override suspend fun update(action: NotionActionEntity): Int { actions[action.id] = action; return 1 }
    override suspend fun deleteByMemoryId(sourceMemoryId: String): Int {
        val before = actions.size
        actions.values.removeAll { it.sourceMemoryId == sourceMemoryId }
        return before - actions.size
    }
    override suspend fun getAll(): List<NotionActionEntity> = actions.values.toList()
    override suspend fun clearAll(): Int { val c = actions.size; actions.clear(); return c }
}

class FakeNotionClient : NotionClient {
    val createdPages = mutableListOf<NotionCreatedPage>()
    var createPageResultToReturn: Result<NotionCreatedPage>? = null
    var searchTargetsResultToReturn: Result<NotionSearchResponse>? = null
    var verifyPageReferenceResultToReturn: Result<Boolean>? = null
    var getDataSourceTitlePropertyResultToReturn: Result<String>? = null
    var lastVerifyPageId: String? = null
    var lastVerifyExpectedTargetId: String? = null
    var lastVerifyExpectedSourceMemoryId: String? = null

    override suspend fun searchTargets(
        accessToken: String,
        query: String?,
        startCursor: String?,
        pageSize: Int
    ): Result<NotionSearchResponse> {
        return searchTargetsResultToReturn ?: Result.Success(NotionSearchResponse(emptyList()))
    }

    override suspend fun createPage(
        accessToken: String,
        targetId: String,
        targetType: NotionTargetType,
        title: String,
        content: String?,
        sourceMemoryId: String?
    ): Result<NotionCreatedPage> {
        createPageResultToReturn?.let { return it }
        val page = NotionCreatedPage(id = "page_${createdPages.size + 1}", url = "https://notion.so/p${createdPages.size + 1}", title = title)
        createdPages.add(page)
        return Result.Success(page)
    }

    override suspend fun getDataSourceTitleProperty(
        accessToken: String,
        dataSourceId: String
    ): Result<String> {
        return getDataSourceTitlePropertyResultToReturn ?: Result.Success("Name")
    }

    override suspend fun verifyPageReference(
        accessToken: String,
        pageId: String,
        expectedTargetId: String,
        expectedSourceMemoryId: String
    ): Result<Boolean> {
        lastVerifyPageId = pageId
        lastVerifyExpectedTargetId = expectedTargetId
        lastVerifyExpectedSourceMemoryId = expectedSourceMemoryId
        return verifyPageReferenceResultToReturn ?: Result.Success(true)
    }
}

class FakeNotionAuthManager : NotionAuthorizationManager {
    private val _status = MutableStateFlow(NotionAuthStatus.DISCONNECTED)
    override val authStatus: StateFlow<NotionAuthStatus> = _status.asStateFlow()

    var tokenToReturn: String? = null
    var onGetAccessToken: (suspend () -> Unit)? = null

    fun setAuthStatus(status: NotionAuthStatus) {
        _status.value = status
    }

    override suspend fun getAuthorizationUrl(redirectUri: String?): Result<String> = Result.Success("https://notion.so/auth")
    override suspend fun handleAuthorizationCallback(ticket: String, state: String, redirectUri: String?): Result<Unit> = Result.Success(Unit)
    override suspend fun handleAuthorizationFailure(error: String): Result<Unit> = Result.Success(Unit)
    override fun markAuthRequired() {
        _status.value = NotionAuthStatus.AUTH_REQUIRED
    }
    override suspend fun getAccessToken(): Result<String> {
        onGetAccessToken?.invoke()
        val t = tokenToReturn
        return if (t != null) Result.Success(t) else Result.Error(NotionAuthRequiredException("No token"))
    }
    override suspend fun refreshAuthStatus(): NotionAuthStatus = _status.value
    override suspend fun disconnect(): Result<Unit> {
        _status.value = NotionAuthStatus.DISCONNECTED
        tokenToReturn = null
        return Result.Success(Unit)
    }
}
