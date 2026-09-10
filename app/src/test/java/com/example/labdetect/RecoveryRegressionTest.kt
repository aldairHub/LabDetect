package com.example.labdetect

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import com.example.labdetect.data.KnowledgeApiEquipmentAssistantRepository
import com.example.labdetect.domain.Detection
import com.example.labdetect.domain.EquipmentDetector
import com.example.labdetect.viewmodel.CameraViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowSpeechRecognizer
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryRegressionTest {
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_LabDetect)
            super.onCreate(savedInstanceState)
        }
    }
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val detection = Detection("centrifuga", "Centrífuga", 94f, .2f, .2f, .8f, .8f, false)

    private class FakeDetector : EquipmentDetector {
        var output: List<Detection> = emptyList()
        var entered: CountDownLatch? = null
        var unblock: CountDownLatch? = null
        var fail = false
        override fun detect(bitmap: Bitmap, allowCenterCrop: Boolean): List<Detection> {
            entered?.countDown()
            unblock?.await(3, TimeUnit.SECONDS)
            if (fail) error("Simulated inference failure")
            return output
        }
        override fun close() = Unit
    }

    private fun frame(model: CameraViewModel) {
        model.onImageCaptured(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        awaitIdle(model)
    }

    private fun awaitIdle(model: CameraViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!model.canAcceptFrame() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Inference slot must be released", model.canAcceptFrame())
    }

    @Test fun reopeningDropsOldFramesAndCanDetectAgain() {
        val detector = FakeDetector().apply { output = listOf(detection) }
        val model = CameraViewModel(app, detector)
        model.resumeDetection()
        frame(model); frame(model)
        assertEquals("centrifuga", model.classificationResult.value?.canonicalId)
        detector.entered = CountDownLatch(1)
        detector.unblock = CountDownLatch(1)
        model.onImageCaptured(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        assertTrue(detector.entered!!.await(3, TimeUnit.SECONDS))
        model.pauseDetection()
        assertNull(model.classificationResult.value)
        assertTrue(model.detections.value!!.isEmpty())
        model.resumeDetection()
        detector.unblock!!.countDown()
        awaitIdle(model)
        assertNull("Previous session must not repopulate UI", model.classificationResult.value)
        frame(model)
        assertNull("Reopen must require fresh confirmation", model.classificationResult.value)
        frame(model)
        assertEquals("centrifuga", model.classificationResult.value?.canonicalId)
        detector.output = listOf(detection.copy(canonicalId = "cabina", label = "Cabina"))
        frame(model)
        assertNull("Unconfirmed new class must not retain old label", model.classificationResult.value)
    }

    @Test fun failedFrameDoesNotDisableFollowingFrames() {
        val detector = FakeDetector().apply { fail = true }
        val model = CameraViewModel(app, detector)
        model.resumeDetection()
        frame(model)
        assertNull(model.classificationResult.value)
        detector.fail = false; detector.output = listOf(detection)
        frame(model); frame(model)
        assertNotNull(model.classificationResult.value)
    }

    @Test fun boxesAndLabelsRequireTwoMatchingFrames() {
        val detector = FakeDetector().apply { output = listOf(detection) }
        val model = CameraViewModel(app, detector)
        model.resumeDetection()
        frame(model)
        assertTrue("One frame must not draw a box", model.detections.value!!.isEmpty())
        assertNull(model.classificationResult.value)
        frame(model)
        assertNotNull(model.classificationResult.value)
        detector.output = emptyList()
        frame(model)
        assertTrue("Lost equipment must disappear immediately", model.detections.value!!.isEmpty())
        detector.output = listOf(detection.copy(confidence = 75f))
        frame(model)
        assertTrue(model.detections.value!!.isEmpty())
        detector.output = listOf(detection)
        frame(model)
        assertFalse(model.detections.value!!.single().confirmed)
        assertNull("Both frames need high confidence for a name", model.classificationResult.value)
        frame(model)
        assertNotNull(model.classificationResult.value)
        detector.output = listOf(detection.copy(canonicalId = "cabina", label = "Cabina"))
        frame(model)
        assertTrue("Different classes cannot confirm each other", model.detections.value!!.isEmpty())
        assertNull(model.classificationResult.value)
        model.pauseDetection()
    }

    @Test fun cameraStartsWithExistingPermissionOnEveryResume() {
        val voice = java.io.File(app.filesDir, "voices/piper-daniela-int8")
        voice.mkdirs()
        java.io.File(voice, "es_AR-daniela-high.onnx").createNewFile()
        java.io.File(voice, "tokens.txt").createNewFile()
        java.io.File(voice, "espeak-ng-data").mkdirs()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val fragment = CameraFragment()
        CameraFragment::class.java.getDeclaredField("viewModel" + '$' + "delegate").apply {
            isAccessible = true
            set(fragment, lazyOf(CameraViewModel(app, FakeDetector())))
        }
        val starting = CameraFragment::class.java.getDeclaredField("cameraStarting").apply { isAccessible = true }
        try {
            controller.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment).commitNow()
            assertFalse(fragment.requireView().findViewById<android.view.View>(R.id.viewFinder).isLaidOut)
            assertTrue("Resume must schedule startup before waiting for preview layout", starting.getBoolean(fragment))
            repeat(3) {
                controller.pause().stop()
                assertFalse(starting.getBoolean(fragment))
                controller.start().resume()
                assertTrue("Reopen must schedule a fresh camera session", starting.getBoolean(fragment))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun microphoneAndFragmentRecoverAfterLeavingAndReopening() {
        // Installation isn't part of this lifecycle test; avoid unpacking the bundled voice.
        val voice = java.io.File(app.filesDir, "voices/piper-daniela-int8")
        voice.mkdirs()
        java.io.File(voice, "es_AR-daniela-high.onnx").createNewFile()
        java.io.File(voice, "tokens.txt").createNewFile()
        java.io.File(voice, "espeak-ng-data").mkdirs()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val activity = controller.get()
        val detector = FakeDetector().apply { output = listOf(detection) }
        val model = CameraViewModel(app, detector)
        val fragment = CameraFragment()
        CameraFragment::class.java.getDeclaredField("viewModel" + '$' + "delegate").apply {
            isAccessible = true
            set(fragment, lazyOf(model))
        }
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        model.resumeDetection(); frame(model); frame(model)
        fragment.requireView().findViewById<android.view.View>(R.id.fabMic).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val oldSpeech = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        oldSpeech.triggerOnReadyForSpeech(Bundle())
        controller.pause().stop()
        assertTrue("Recognizer must be released offscreen", oldSpeech.isDestroyed)
        assertNull(model.classificationResult.value)
        controller.start().resume()
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        model.resumeDetection(); frame(model); frame(model)
        fragment.requireView().findViewById<android.view.View>(R.id.fabMic).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val newSpeech = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertNotSame(oldSpeech, newSpeech)
        oldSpeech.triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        assertFalse("Late callback must not destroy the new session", newSpeech.isDestroyed)
        newSpeech.triggerOnError(SpeechRecognizer.ERROR_AUDIO)
        assertTrue(newSpeech.isDestroyed)
        assertTrue(fragment.requireView().findViewById<android.view.View>(R.id.fabMic).isEnabled)
        controller.pause().stop().destroy()
    }

    private fun completed(text: String, web: Boolean = false): String {
        val output = JSONArray()
        if (web) output.put(JSONObject().put("type", "web_search_call").put("status", "completed"))
        output.put(JSONObject().put("type", "message").put("content", JSONArray().put(
            JSONObject().put("type", "output_text").put("text", text))))
        return JSONObject().put("status", "completed").put("output", output).toString()
    }

    @Test fun webFailureIsNotCachedAndNextQuestionCanRecover() = runBlocking {
        var requests = 0
        val repository = KnowledgeApiEquipmentAssistantRepository(app, transport = { _, payload ->
            requests++
            val web = payload.has("tools")
            assertTrue(payload.getInt("max_output_tokens") >= if (web) 4096 else 1536)
            when (requests) {
                1, 3 -> 200 to completed("__SIN_INFORMACION_EN_MANUAL__")
                2 -> 200 to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""
                else -> 200 to completed("El precio varía según el modelo y el proveedor.", true)
            }
        }, apiKeyOverride = "test-only", networkAvailable = { true })
        val first = repository.ask("¿Cuánto cuesta?", "centrifuga", null)
        assertTrue(first.contains("reintentar"))
        val second = repository.ask("¿Cuánto cuesta?", "centrifuga", null)
        assertEquals("El precio varía según el modelo y el proveedor.", second)
        assertEquals(4, requests)
        assertEquals(second, repository.ask("¿Cuánto cuesta?", "centrifuga", null))
        assertEquals(4, requests)
    }

    @Test fun offlineSkipsNetworkAndCancellationIsNotConvertedToAnAnswer() = runBlocking {
        val offline = KnowledgeApiEquipmentAssistantRepository(app,
            transport = { _, _ -> error("Offline must not call the network") },
            apiKeyOverride = "test-only", networkAvailable = { false })
        assertTrue(offline.ask("¿Para qué sirve?", "centrifuga", null).isNotBlank())
        val cancelled = KnowledgeApiEquipmentAssistantRepository(app,
            transport = { _, _ -> throw CancellationException("Screen closed") },
            apiKeyOverride = "test-only", networkAvailable = { true })
        try {
            cancelled.ask("¿Para qué sirve?", "centrifuga", null)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
