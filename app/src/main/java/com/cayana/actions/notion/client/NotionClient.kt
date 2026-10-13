package com.cayana.actions.notion.client

import com.cayana.core.common.Result

interface NotionClient {
    suspend fun searchTargets(
        accessToken: String,
        query: String? = null,
        startCursor: String? = null,
        pageSize: Int = 50
    ): Result<NotionSearchResponse>

    suspend fun createPage(
        accessToken: String,
        targetId: String,
        targetType: NotionTargetType,
        title: String,
        content: String? = null,
        sourceMemoryId: String? = null
    ): Result<NotionCreatedPage>

    suspend fun getDataSourceTitleProperty(
        accessToken: String,
        dataSourceId: String
    ): Result<String>

    suspend fun verifyPageReference(
        accessToken: String,
        pageId: String,
        expectedTargetId: String,
        expectedSourceMemoryId: String
    ): Result<Boolean>
}
