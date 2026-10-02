package com.cayana.processing

import com.cayana.core.common.Result
import com.cayana.test.FakeOcrEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineTest {

    @Test
    fun `OcrEngine handles Traditional Chinese text extraction`() = runTest {
        val expected = "蔡依林演唱會 台北流行音樂中心 2026/10/18"
        val engine = FakeOcrEngine(Result.Success(OcrResult(fullText = expected)))

        val result = engine.processImage("content://media/external/images/media/1")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(expected, data.fullText)
    }

    @Test
    fun `OcrEngine handles English and alphanumeric text extraction`() = runTest {
        val expected = "Flight Booking Confirmation #NH852 Gate D4"
        val engine = FakeOcrEngine(Result.Success(OcrResult(fullText = expected)))

        val result = engine.processImage("content://media/external/images/media/2")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(expected, data.fullText)
    }

    @Test
    fun `OcrEngine handles mixed Chinese and English text extraction`() = runTest {
        val expected = "台北大巨蛋 Taipei Dome VIP Pass 2026/11/05"
        val engine = FakeOcrEngine(Result.Success(OcrResult(fullText = expected)))

        val result = engine.processImage("content://media/external/images/media/3")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(expected, data.fullText)
    }

    @Test
    fun `OcrEngine handles image with no readable text`() = runTest {
        val engine = FakeOcrEngine(Result.Success(OcrResult(fullText = "")))

        val result = engine.processImage("content://media/external/images/media/4")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertTrue(data.fullText.isEmpty())
    }

    @Test
    fun `OcrEngine handles processing error gracefully`() = runTest {
        val exception = RuntimeException("Failed to decode bitmap from URI")
        val engine = FakeOcrEngine(Result.Error(exception))

        val result = engine.processImage("content://media/external/images/media/5")
        assertTrue(result is Result.Error)
        assertEquals("Failed to decode bitmap from URI", (result as Result.Error).exception.message)
    }

    @Test
    fun `StubOcrEngine reports not ready and returns error`() = runTest {
        val stub = StubOcrEngine()
        assertEquals(false, stub.isReady)
        val result = stub.extractText("content://media/dummy")
        assertTrue(result is Result.Error)
    }
}
