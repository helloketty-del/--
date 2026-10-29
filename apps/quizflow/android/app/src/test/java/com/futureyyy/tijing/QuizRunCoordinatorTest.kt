package com.futureyyy.tijing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class QuizRunCoordinatorTest {
    @Test
    fun manualTapPerformsOneRecognitionWithoutGestures() {
        val capture = FakeCaptureGateway(
            frame(signature()),
            frame(signature(*(0..8).toList().toIntArray())),
        )
        val model = FakeModelGateway(singleQuestionJson(), singleQuestionJson())
        val gestures = FakeGestureGateway()
        val listener = RecordingListener()
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.MANUAL,
            capture = capture,
            model = model,
            gestures = gestures,
            listener = listener,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()

        assertEquals(RunnerPhase.READY, coordinator.snapshot().phase)
        assertEquals(1, capture.captureCount)
        assertEquals(listOf(1), model.questionNumbers)
        assertEquals(1, listener.answers.size)
        assertEquals(0, gestures.foregroundChecks)
        assertTrue(gestures.calls.isEmpty())
        assertEquals(0, gestures.cancelCount)
        assertTrue(scheduler.delays.isEmpty())

        coordinator.onPrimaryAction()

        assertEquals(2, capture.captureCount)
        assertEquals(listOf(1, 2), model.questionNumbers)
        assertEquals(2, listener.answers.size)
        assertEquals(RunnerPhase.READY, coordinator.snapshot().phase)
    }

    @Test
    fun manualSamePagePausesThenChangedPageRetryAdvancesQuestion() {
        val firstPage = signature()
        val samePage = signature(*(0 until 8).toList().toIntArray())
        val nextPage = signature(*(0 until 9).toList().toIntArray())
        assertEquals(QuizRunCoordinator.MANUAL_SAME_PAGE_DISTANCE, firstPage.hammingDistance(samePage))
        assertEquals(QuizRunCoordinator.MANUAL_SAME_PAGE_DISTANCE + 1, firstPage.hammingDistance(nextPage))
        val capture = FakeCaptureGateway(
            frame(firstPage),
            frame(samePage),
            frame(nextPage),
        )
        val model = FakeModelGateway(singleQuestionJson(), singleQuestionJson())
        val gestures = FakeGestureGateway()
        val listener = RecordingListener()
        val coordinator = coordinator(
            mode = QuizMode.MANUAL,
            capture = capture,
            model = model,
            gestures = gestures,
            listener = listener,
        )

        coordinator.onPrimaryAction()
        coordinator.onPrimaryAction()

        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertEquals(1, coordinator.snapshot().questionNo)
        assertEquals(listOf(1), model.questionNumbers)
        assertEquals("页面似乎未进入下一题，请确认后重试", coordinator.snapshot().errorMessage)
        assertEquals(listOf("页面似乎未进入下一题，请确认后重试"), listener.errors)
        assertTrue(gestures.calls.isEmpty())

        coordinator.retry()

        assertEquals(RunnerPhase.READY, coordinator.snapshot().phase)
        assertEquals(2, coordinator.snapshot().questionNo)
        assertEquals(listOf(1, 2), model.questionNumbers)
        assertEquals(2, listener.answers.size)
        assertTrue(gestures.calls.isEmpty())
    }

    @Test
    fun autoModePausesBeforeCaptureWhenTargetIsNotForeground() {
        val capture = FakeCaptureGateway()
        val model = FakeModelGateway()
        val gestures = FakeGestureGateway().apply { foreground = false }
        val listener = RecordingListener()
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            listener = listener,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        repeat(QuizRunCoordinator.MAX_START_FOREGROUND_RETRIES) { scheduler.runNext() }

        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertEquals(0, capture.captureCount)
        assertTrue(model.questionNumbers.isEmpty())
        assertTrue(gestures.calls.isEmpty())
        assertEquals(listOf("请打开题目页"), listener.errors)
        assertEquals(QuizRunCoordinator.MAX_START_FOREGROUND_RETRIES + 1, gestures.foregroundChecks)
        assertEquals(
            List(QuizRunCoordinator.MAX_START_FOREGROUND_RETRIES) {
                QuizRunCoordinator.START_FOREGROUND_RETRY_DELAY_MS
            },
            scheduler.delays,
        )
    }

    @Test
    fun autoModeWaitsForATransientForegroundMissBeforeCapturing() {
        val capture = FakeCaptureGateway(autoComplete = false)
        val model = FakeModelGateway()
        val gestures = FakeGestureGateway().apply { foreground = false }
        val listener = RecordingListener()
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            listener = listener,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        assertEquals(RunnerPhase.CAPTURING, coordinator.snapshot().phase)
        assertEquals(0, capture.captureCount)

        gestures.foreground = true
        scheduler.runNext()

        assertEquals(1, capture.captureCount)
        assertEquals(RunnerPhase.CAPTURING, coordinator.snapshot().phase)
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun autoTapStartsNextQuestionOnlyAfterDistanceAndStabilityThresholds() {
        val baseline = signature()
        val onlyBelowThresholdChanged = signature(
            *(0 until (QuizRunCoordinator.PAGE_CHANGE_DISTANCE - 1)).toList().toIntArray(),
        )
        val thresholdReached = signature(
            *(0 until QuizRunCoordinator.PAGE_CHANGE_DISTANCE).toList().toIntArray(),
        )
        val nineBitsFromPrevious = signature(
            *((4 until QuizRunCoordinator.PAGE_CHANGE_DISTANCE) +
                (QuizRunCoordinator.PAGE_CHANGE_DISTANCE until QuizRunCoordinator.PAGE_CHANGE_DISTANCE + 5)).toList().toIntArray(),
        )
        val eightBitsFromPrevious = signature(
            *((8 until QuizRunCoordinator.PAGE_CHANGE_DISTANCE) +
                (QuizRunCoordinator.PAGE_CHANGE_DISTANCE until QuizRunCoordinator.PAGE_CHANGE_DISTANCE + 9)).toList().toIntArray(),
        )
        assertEquals(
            QuizRunCoordinator.PAGE_CHANGE_DISTANCE - 1,
            baseline.hammingDistance(onlyBelowThresholdChanged),
        )
        assertEquals(
            QuizRunCoordinator.PAGE_CHANGE_DISTANCE,
            baseline.hammingDistance(thresholdReached),
        )
        assertEquals(9, thresholdReached.hammingDistance(nineBitsFromPrevious))
        assertEquals(8, nineBitsFromPrevious.hammingDistance(eightBitsFromPrevious))

        val capture = FakeCaptureGateway(
            frame(baseline),
            frame(baseline),
            frame(onlyBelowThresholdChanged),
            frame(thresholdReached),
            frame(nineBitsFromPrevious),
            frame(eightBitsFromPrevious),
            frame(signature(40)),
            frame(signature(40)),
        )
        val model = FakeModelGateway(singleQuestionJson(), singleQuestionJson())
        val gestures = FakeGestureGateway()
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        assertEquals(listOf(650L), scheduler.delays)

        scheduler.runNext()
        scheduler.runNext()
        scheduler.runNext()
        assertEquals(1, coordinator.snapshot().questionNo)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)

        scheduler.runNext()

        assertEquals(listOf(650L, 450L, 450L, 450L, 650L), scheduler.delays)
        assertEquals(8, capture.captureCount)
        assertEquals(listOf(1, 2), model.questionNumbers)
        assertEquals(2, gestures.calls.count { it == "answer" })
        assertEquals(2, gestures.calls.count { it == "confirm" })
        assertEquals(2, coordinator.snapshot().questionNo)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
    }

    @Test
    fun multipleChoiceTapsEveryAnswerThenConfirmWithTextFallback() {
        val gestures = FakeGestureGateway()
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = FakeCaptureGateway(frame(signature()), frame(signature())),
            model = FakeModelGateway(multipleQuestionJson(confirmBox = "null")),
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()

        assertEquals(listOf("answer", "answer", "confirm"), gestures.calls)
        assertEquals(listOf<NormalizedBox?>(null), gestures.confirmBoxes)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertEquals(listOf(650L), scheduler.delays)
    }

    @Test
    fun multipleChoiceRetryContinuesFromFirstUnfinishedAnswer() {
        val gestures = FakeGestureGateway(true, false, true, true)
        val scheduler = FakeRunnerScheduler()
        val capture = FakeCaptureGateway(frame(signature()), frame(signature()))
        val model = FakeModelGateway(multipleQuestionJson(confirmBox = "null"))
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()

        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertEquals(listOf(47f, 67f), gestures.answerBoxes.map { it.centerY })
        assertEquals(1, capture.captureCount)
        assertEquals(listOf(1), model.questionNumbers)

        coordinator.retry()

        assertEquals(listOf("answer", "answer", "answer", "confirm"), gestures.calls)
        assertEquals(listOf(47f, 67f, 67f), gestures.answerBoxes.map { it.centerY })
        assertEquals(2, capture.captureCount)
        assertEquals(listOf(1), model.questionNumbers)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertEquals(listOf(650L), scheduler.delays)
    }

    @Test
    fun questionOneHundredContinuesToQuestionOneHundredOneAfterPageChange() {
        val baseline = signature()
        val changed = signature(*(0 until 20).toList().toIntArray())
        val stable = signature(*(0 until 21).toList().toIntArray())
        val nextBaseline = signature(40)
        val gestures = FakeGestureGateway()
        val scheduler = FakeRunnerScheduler()
        val model = FakeModelGateway(
            multipleQuestionJson(confirmBox = "[70, 88, 22, 8]"),
            singleQuestionJson(),
        )
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            startingQuestionNo = 100,
            capture = FakeCaptureGateway(
                frame(baseline),
                frame(baseline),
                frame(changed),
                frame(stable),
                frame(nextBaseline),
                frame(nextBaseline),
            ),
            model = model,
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()

        assertEquals(listOf("answer", "answer", "confirm"), gestures.calls)
        assertEquals(listOf(NormalizedBox(70f, 88f, 22f, 8f)), gestures.confirmBoxes)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)

        scheduler.runNext()
        scheduler.runNext()

        assertEquals(listOf(650L, 450L, 650L), scheduler.delays)
        assertEquals(listOf(100, 101), model.questionNumbers)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertEquals(101, coordinator.snapshot().questionNo)
        assertEquals(3, gestures.calls.count { it == "answer" })
        assertEquals(2, gestures.calls.count { it == "confirm" })
    }

    @Test
    fun transientConfirmFailureRetriesOnlyConfirmWithoutPausing() {
        val gestures = FakeGestureGateway(true, false, true)
        val scheduler = FakeRunnerScheduler()
        val capture = FakeCaptureGateway(frame(signature()), frame(signature()))
        val model = FakeModelGateway(singleQuestionJson())
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()

        assertEquals(listOf("answer", "confirm"), gestures.calls)
        assertEquals(RunnerPhase.EXECUTING, coordinator.snapshot().phase)
        assertEquals(listOf(QuizRunCoordinator.CONFIRM_RETRY_DELAY_MS), scheduler.delays)

        scheduler.runNext()
        assertEquals(listOf("answer", "confirm", "confirm"), gestures.calls)
        assertEquals(2, capture.captureCount)
        assertEquals(listOf(1), model.questionNumbers)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertEquals(listOf(QuizRunCoordinator.CONFIRM_RETRY_DELAY_MS, 650L), scheduler.delays)
    }

    @Test
    fun automaticPagePollRetriesScreenshotTimeoutWithoutRepeatingAnswerOrConfirm() {
        val baseline = signature()
        val changed = signature(*(0 until QuizRunCoordinator.PAGE_CHANGE_DISTANCE).toList().toIntArray())
        val stable = signature(*(0 until (QuizRunCoordinator.PAGE_CHANGE_DISTANCE + 1)).toList().toIntArray())
        val capture = FakeCaptureGateway(autoComplete = false)
        val gestures = FakeGestureGateway()
        val scheduler = FakeRunnerScheduler()
        val listener = RecordingListener()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = FakeModelGateway(singleQuestionJson(), singleQuestionJson()),
            gestures = gestures,
            listener = listener,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        capture.completeActive(Result.success(frame(baseline)))
        capture.completeActive(Result.success(frame(baseline)))

        scheduler.runNext()
        capture.completeActive(Result.failure(IllegalStateException("截图超时")))

        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertTrue(listener.errors.isEmpty())
        assertEquals(
            listOf(QuizRunCoordinator.FIRST_POLL_DELAY_MS, QuizRunCoordinator.CAPTURE_RETRY_DELAY_MS),
            scheduler.delays,
        )

        scheduler.runNext()
        capture.completeActive(Result.success(frame(changed)))
        scheduler.runNext()
        capture.completeActive(Result.success(frame(stable)))

        assertEquals(2, coordinator.snapshot().questionNo)
        assertEquals(RunnerPhase.CAPTURING, coordinator.snapshot().phase)
        assertEquals(listOf("answer", "confirm"), gestures.calls)
        assertEquals(6, capture.captureCount)
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun automaticPagePollPausesAfterThreeConsecutiveScreenshotTimeouts() {
        val baseline = signature()
        val capture = FakeCaptureGateway(autoComplete = false)
        val gestures = FakeGestureGateway()
        val scheduler = FakeRunnerScheduler()
        val listener = RecordingListener()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = FakeModelGateway(singleQuestionJson()),
            gestures = gestures,
            listener = listener,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        capture.completeActive(Result.success(frame(baseline)))
        capture.completeActive(Result.success(frame(baseline)))

        repeat(QuizRunCoordinator.MAX_AUTO_CAPTURE_ATTEMPTS) { attempt ->
            scheduler.runNext()
            capture.completeActive(Result.failure(IllegalStateException("截图超时")))
            if (attempt < QuizRunCoordinator.MAX_AUTO_CAPTURE_ATTEMPTS - 1) {
                assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
            }
        }

        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertEquals("截图超时", coordinator.snapshot().errorMessage)
        assertEquals(listOf("截图超时"), listener.errors)
        assertEquals(listOf("answer", "confirm"), gestures.calls)
        assertEquals(5, capture.captureCount)
    }

    @Test
    fun exhaustedConfirmRetriesPauseThenManualRetryStillRepeatsOnlyConfirm() {
        val gestures = FakeGestureGateway(true, false, false, false, true)
        val scheduler = FakeRunnerScheduler()
        val capture = FakeCaptureGateway(frame(signature()), frame(signature()))
        val model = FakeModelGateway(singleQuestionJson())
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(listOf("answer", "confirm", "confirm", "confirm"), gestures.calls)
        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)

        coordinator.retry()

        assertEquals(listOf("answer", "confirm", "confirm", "confirm", "confirm"), gestures.calls)
        assertEquals(2, capture.captureCount)
        assertEquals(listOf(1), model.questionNumbers)
        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
    }

    @Test
    fun pauseDuringCaptureCancelsOldSingleFlightAndRetryStartsNewCapture() {
        val capture = FakeCaptureGateway(autoComplete = false)
        val model = FakeModelGateway(singleQuestionJson())
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
        )

        coordinator.onPrimaryAction()
        coordinator.pause()
        assertFalse(capture.hasActiveRequest)
        assertEquals(1, capture.cancelledCallbacks.size)

        coordinator.retry()
        assertTrue(capture.hasActiveRequest)
        assertEquals(2, capture.captureCount)

        capture.completeCancelled(0, Result.success(frame(signature())))

        assertEquals(RunnerPhase.CAPTURING, coordinator.snapshot().phase)
        assertTrue(model.questionNumbers.isEmpty())

        capture.completeActive(Result.success(frame(signature(0))))

        assertEquals(listOf(1), model.questionNumbers)
        assertEquals(RunnerPhase.EXECUTING, coordinator.snapshot().phase)
        assertTrue(capture.hasActiveRequest)

        capture.completeActive(Result.success(frame(signature(0))))

        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
    }

    @Test
    fun pauseDuringRecognitionCancelsModelAndRetryStartsOneReplacement() {
        val model = FakeModelGateway(singleQuestionJson(), singleQuestionJson(), autoComplete = false)
        val capture = FakeCaptureGateway(
            frame(signature()),
            frame(signature(0)),
            frame(signature(0)),
        )
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
        )

        coordinator.onPrimaryAction()
        assertEquals(RunnerPhase.RECOGNIZING, coordinator.snapshot().phase)
        assertTrue(model.hasActiveRequest)

        coordinator.pause()
        assertFalse(model.hasActiveRequest)
        assertEquals(1, model.cancelledCallbacks.size)

        coordinator.retry()
        assertEquals(RunnerPhase.RECOGNIZING, coordinator.snapshot().phase)
        assertTrue(model.hasActiveRequest)
        assertEquals(listOf(1, 1), model.questionNumbers)

        model.completeCancelled(0, Result.success(singleQuestionJson()))
        assertEquals(RunnerPhase.RECOGNIZING, coordinator.snapshot().phase)

        model.completeActive(Result.success(singleQuestionJson()))

        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertEquals(3, capture.captureCount)
    }

    @Test
    fun stopCancelsQueuedGesturesAndIgnoresTheirCompletion() {
        val gestures = FakeGestureGateway(autoComplete = false)
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = FakeCaptureGateway(frame(signature())),
            model = FakeModelGateway(singleQuestionJson()),
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        assertEquals(RunnerPhase.EXECUTING, coordinator.snapshot().phase)
        val cancelsBeforeStop = gestures.cancelCount

        coordinator.stop()
        gestures.completeNext(true)

        assertEquals(cancelsBeforeStop + 1, gestures.cancelCount)
        assertEquals(RunnerPhase.STOPPED, coordinator.snapshot().phase)
        assertTrue(scheduler.delays.isEmpty())
    }

    @Test
    fun pauseCancelsQueuedGesturesAndIgnoresTheirCompletion() {
        val gestures = FakeGestureGateway(autoComplete = false)
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = FakeCaptureGateway(frame(signature())),
            model = FakeModelGateway(singleQuestionJson()),
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        val cancelsBeforePause = gestures.cancelCount
        coordinator.pause()
        gestures.completeNext(true)

        assertEquals(cancelsBeforePause + 1, gestures.cancelCount)
        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertTrue(scheduler.delays.isEmpty())
    }

    @Test
    fun malformedJsonPausesWithoutGesture() {
        val gestures = FakeGestureGateway()
        val listener = RecordingListener()
        val scheduler = FakeRunnerScheduler()
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = FakeCaptureGateway(frame(signature())),
            model = FakeModelGateway("{broken-json"),
            gestures = gestures,
            listener = listener,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()

        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertTrue(gestures.calls.isEmpty())
        assertTrue(scheduler.delays.isEmpty())
        assertEquals(1, listener.errors.size)
        assertTrue(listener.errors.single().contains("JSON"))
    }

    @Test
    fun pagePollingRetriesConfirmThreeTimesBeforePausing() {
        val unchangedFrames = Array(QuizRunCoordinator.MAX_POLL_ATTEMPTS * QuizRunCoordinator.MAX_CONFIRM_TAP_ATTEMPTS) {
            frame(signature())
        }
        val scheduler = FakeRunnerScheduler()
        val gestures = FakeGestureGateway()
        val model = FakeModelGateway(singleQuestionJson())
        val capture = FakeCaptureGateway(frame(signature()), frame(signature()), *unchangedFrames)
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = model,
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        repeat(QuizRunCoordinator.MAX_POLL_ATTEMPTS) { scheduler.runNext() }
        repeat(QuizRunCoordinator.MAX_CONFIRM_TAP_ATTEMPTS - 1) {
            scheduler.runNext()
            repeat(QuizRunCoordinator.MAX_POLL_ATTEMPTS) { scheduler.runNext() }
        }

        assertEquals(38, scheduler.delays.size)
        assertEquals(650L, scheduler.delays.first())
        assertEquals(3, scheduler.delays.count { it == QuizRunCoordinator.FIRST_POLL_DELAY_MS })
        assertEquals(2, scheduler.delays.count { it == QuizRunCoordinator.CONFIRM_RETRY_DELAY_MS })
        assertEquals(33, scheduler.delays.count { it == QuizRunCoordinator.SUBSEQUENT_POLL_DELAY_MS })
        assertEquals(RunnerPhase.PAUSED_ERROR, coordinator.snapshot().phase)
        assertEquals(1, coordinator.snapshot().questionNo)
        assertEquals(listOf("answer", "confirm", "confirm", "confirm"), gestures.calls)
        assertEquals(38, capture.captureCount)

        coordinator.retry()

        assertEquals(RunnerPhase.WAITING_PAGE_CHANGE, coordinator.snapshot().phase)
        assertEquals(39, scheduler.delays.size)
        assertEquals(650L, scheduler.delays.last())
        assertEquals(38, capture.captureCount)
        assertEquals(listOf(1), model.questionNumbers)
        assertEquals(listOf("answer", "confirm", "confirm", "confirm", "confirm"), gestures.calls)
    }

    @Test
    fun selectedStateIsCapturedAsBaselineBeforeConfirmPagePollingAndRetriesConfirm() {
        val initial = signature()
        val selected = signature(*(0 until 20).toList().toIntArray())
        val unchangedAfterConfirm = Array(12) { frame(selected) }
        val scheduler = FakeRunnerScheduler()
        val gestures = FakeGestureGateway()
        val capture = FakeCaptureGateway(frame(initial), frame(selected), *unchangedAfterConfirm)
        val coordinator = coordinator(
            mode = QuizMode.AUTO,
            capture = capture,
            model = FakeModelGateway(singleQuestionJson()),
            gestures = gestures,
            scheduler = scheduler,
        )

        coordinator.onPrimaryAction()
        repeat(12) { scheduler.runNext() }

        assertEquals(RunnerPhase.EXECUTING, coordinator.snapshot().phase)
        assertEquals(1, coordinator.snapshot().questionNo)
        assertEquals(null, coordinator.snapshot().errorMessage)
        assertEquals(14, capture.captureCount)
        assertEquals(listOf("answer", "confirm"), gestures.calls)
        assertEquals(QuizRunCoordinator.CONFIRM_RETRY_DELAY_MS, scheduler.delays.last())
    }

    @Test
    fun frameSignatureUsesOneThousandTwentyFourBitsAndIgnoresOuterBars() {
        val distant = signature(0, 63, 64, 127, 128, 191, 192, 255)
        assertEquals(8, signature().hammingDistance(distant))
        assertThrows(IllegalArgumentException::class.java) {
            FrameSignature.fromBits(BooleanArray(FrameSignature.BIT_COUNT - 1))
        }

        val width = 16
        val height = 100
        val luminance = IntArray(width * height)
        for (y in 0 until 12) {
            for (x in 0 until width) luminance[y * width + x] = 255
        }
        for (y in 92 until height) {
            for (x in 0 until width) luminance[y * width + x] = 255
        }

        val fromPixels = FrameSignature.fromLuminancePixels(width, height, luminance)

        assertEquals(1_024, FrameSignature.BIT_COUNT)
        assertEquals(0, signature().hammingDistance(fromPixels))
        assertEquals(12, FrameSignature.cropTop(height))
        assertEquals(92, FrameSignature.cropBottomExclusive(height))
    }

    @Test
    fun frameSignatureDetectsSparseQuestionContentChangesBetweenPages() {
        val width = 64
        val height = 100
        val firstPage = IntArray(width * height) { 0xffffffff.toInt() }
        val secondPage = firstPage.copyOf()

        // These samples fall between the old 16x16 grid points, like changed text on an otherwise stable page.
        repeat(21) { column ->
            val x = column * 2 + 1
            val y = FrameSignature.cropTop(height) + 1
            secondPage[y * width + x] = 0xff101010.toInt()
        }

        val baseline = FrameSignature.fromArgbPixels(width, height, firstPage)
        val nextPage = FrameSignature.fromArgbPixels(width, height, secondPage)

        assertEquals(32, FrameSignature.GRID_SIZE)
        assertTrue(baseline.hammingDistance(nextPage) >= QuizRunCoordinator.PAGE_CHANGE_DISTANCE)
    }

    private fun coordinator(
        mode: QuizMode,
        startingQuestionNo: Int = 1,
        capture: FakeCaptureGateway = FakeCaptureGateway(),
        model: FakeModelGateway = FakeModelGateway(),
        gestures: FakeGestureGateway = FakeGestureGateway(),
        listener: RecordingListener = RecordingListener(),
        scheduler: FakeRunnerScheduler = FakeRunnerScheduler(),
    ) = QuizRunCoordinator(
        mode = mode,
        startingQuestionNo = startingQuestionNo,
        captureGateway = capture,
        modelGateway = model,
        gestureGateway = gestures,
        listener = listener,
        scheduler = scheduler,
    )

    private fun frame(signature: FrameSignature) = CaptureFrame(
        dataUrl = "data:image/jpeg;base64,fixture",
        signature = signature,
    )

    private fun signature(vararg setBits: Int): FrameSignature {
        val bits = BooleanArray(FrameSignature.BIT_COUNT)
        setBits.forEach { bits[it] = true }
        return FrameSignature.fromBits(bits)
    }

    private fun singleQuestionJson() = """
        {
          "question_no": 1,
          "question_type": "single",
          "question": "Question",
          "options": [
            {"index": 0, "text": "A", "box": [8, 43, 84, 8]},
            {"index": 1, "text": "B", "box": [8, 53, 84, 8]}
          ],
          "answer_indices": [1],
          "confirm_box": [20, 88, 60, 8],
          "next_box": null,
          "confidence": 0.95
        }
    """.trimIndent()

    private fun multipleQuestionJson(confirmBox: String) = """
        {
          "question_no": 1,
          "question_type": "multiple",
          "question": "Question",
          "options": [
            {"index": 0, "text": "A", "box": [8, 43, 84, 8]},
            {"index": 1, "text": "B", "box": [8, 53, 84, 8]},
            {"index": 2, "text": "C", "box": [8, 63, 84, 8]}
          ],
          "answer_indices": [0, 2],
          "confirm_box": $confirmBox,
          "next_box": null,
          "confidence": 0.91
        }
    """.trimIndent()

    private class FakeCaptureGateway(
        vararg frames: CaptureFrame,
        private val autoComplete: Boolean = true,
    ) : CaptureGateway {
        private val results = ArrayDeque<Result<CaptureFrame>>().apply {
            frames.forEach { add(Result.success(it)) }
        }
        private var activeCallback: ((Result<CaptureFrame>) -> Unit)? = null
        val cancelledCallbacks = mutableListOf<(Result<CaptureFrame>) -> Unit>()
        var captureCount = 0
        var cancelCount = 0
        val hasActiveRequest: Boolean
            get() = activeCallback != null

        override fun capture(callback: (Result<CaptureFrame>) -> Unit) {
            check(activeCallback == null) { "capture fake only permits one active request" }
            captureCount += 1
            if (autoComplete) {
                callback(results.removeFirst())
            } else {
                activeCallback = callback
            }
        }

        override fun cancel() {
            cancelCount += 1
            activeCallback?.let { callback -> cancelledCallbacks += callback }
            activeCallback = null
        }

        fun completeActive(result: Result<CaptureFrame>) {
            val callback = checkNotNull(activeCallback) { "No active capture request" }
            activeCallback = null
            callback(result)
        }

        fun completeCancelled(index: Int, result: Result<CaptureFrame>) {
            cancelledCallbacks.removeAt(index)(result)
        }
    }

    private class FakeModelGateway(
        vararg responses: String,
        private val autoComplete: Boolean = true,
    ) : ModelGateway {
        private val results = ArrayDeque<Result<String>>().apply {
            responses.forEach { add(Result.success(it)) }
        }
        val questionNumbers = mutableListOf<Int>()
        private var activeCallback: ((Result<String>) -> Unit)? = null
        val cancelledCallbacks = mutableListOf<(Result<String>) -> Unit>()
        var cancelCount = 0
        val hasActiveRequest: Boolean
            get() = activeCallback != null

        override fun analyze(
            frame: CaptureFrame,
            questionNo: Int,
            callback: (Result<String>) -> Unit,
        ) {
            check(activeCallback == null) { "model fake only permits one active request" }
            questionNumbers += questionNo
            if (autoComplete) callback(results.removeFirst()) else activeCallback = callback
        }

        override fun cancel() {
            cancelCount += 1
            activeCallback?.let(cancelledCallbacks::add)
            activeCallback = null
        }

        fun completeActive(result: Result<String>) {
            val callback = checkNotNull(activeCallback) { "No active model request" }
            activeCallback = null
            callback(result)
        }

        fun completeCancelled(index: Int, result: Result<String>) {
            cancelledCallbacks.removeAt(index)(result)
        }
    }

    private class FakeGestureGateway(
        vararg outcomes: Boolean,
        private val autoComplete: Boolean = true,
    ) : GestureGateway {
        val calls = mutableListOf<String>()
        val answerBoxes = mutableListOf<NormalizedBox>()
        val confirmBoxes = mutableListOf<NormalizedBox?>()
        private val outcomes = ArrayDeque<Boolean>().apply { outcomes.forEach(::add) }
        private val pendingCallbacks = ArrayDeque<(Boolean) -> Unit>()
        var foreground = true
        var foregroundChecks = 0
        var cancelCount = 0

        override fun isTargetForeground(): Boolean {
            foregroundChecks += 1
            return foreground
        }

        override fun tap(box: NormalizedBox, callback: (Boolean) -> Unit) {
            calls += "answer"
            answerBoxes += box
            completeOrQueue(callback)
        }

        override fun tapConfirm(box: NormalizedBox?, callback: (Boolean) -> Unit) {
            calls += "confirm"
            confirmBoxes += box
            completeOrQueue(callback)
        }

        override fun cancel() {
            cancelCount += 1
        }

        fun completeNext(success: Boolean) {
            pendingCallbacks.removeFirst()(success)
        }

        private fun completeOrQueue(callback: (Boolean) -> Unit) {
            if (autoComplete) {
                callback(if (outcomes.isEmpty()) true else outcomes.removeFirst())
            } else {
                pendingCallbacks.add(callback)
            }
        }
    }

    private class RecordingListener : RunnerListener {
        val snapshots = mutableListOf<RunnerSnapshot>()
        val answers = mutableListOf<QuizModelResult>()
        val errors = mutableListOf<String>()

        override fun onSnapshot(snapshot: RunnerSnapshot) {
            snapshots += snapshot
        }

        override fun onAnswer(result: QuizModelResult) {
            answers += result
        }

        override fun onError(message: String) {
            errors += message
        }
    }

    private class FakeRunnerScheduler : RunnerScheduler {
        private data class Scheduled(
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        val delays = mutableListOf<Long>()
        private val scheduled = ArrayDeque<Scheduled>()

        override fun schedule(delayMs: Long, action: () -> Unit): RunnerScheduledTask {
            delays += delayMs
            val item = Scheduled(action)
            scheduled.add(item)
            return RunnerScheduledTask { item.cancelled = true }
        }

        fun runNext() {
            while (scheduled.isNotEmpty()) {
                val item = scheduled.removeFirst()
                if (!item.cancelled) {
                    item.action()
                    return
                }
            }
            throw AssertionError("No scheduled action available")
        }
    }
}
