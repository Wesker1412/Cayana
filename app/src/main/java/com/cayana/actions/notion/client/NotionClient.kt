package com.cayana.actions.notion.client

import com.cayana.core.common.Result

interface NotionClient {
    suspend fun searchTargets(accessToken: String): Result<List<NotionTarget>>
    suspend fun createPage(
        accessToken: String,
        targetId: String,
        targetType: NotionTargetType,
        title: String,
        content: String?,
        sourceMemoryId: String? = null
    ): Result<NotionCreatedPage>
}
