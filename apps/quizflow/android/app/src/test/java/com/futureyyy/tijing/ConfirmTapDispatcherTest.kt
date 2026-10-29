package com.futureyyy.tijing

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmTapDispatcherTest {
    @Test
    fun retryBypassesPreviouslySuccessfulTextDeliveryAndUsesCoordinates() {
        val calls = mutableListOf<String>()
        ConfirmTapDispatcher.dispatch(
            tapText = { calls += "text"; it(true) },
            tapCoordinates = { calls += "coordinates"; it(true) },
            completion = { calls += "complete:$it" },
            preferCoordinates = true,
        )
        assertEquals(listOf("coordinates", "complete:true"), calls)
    }

    @Test
    fun retryWithoutCoordinatesStillUsesAvailableTextControl() {
        val calls = mutableListOf<String>()
        ConfirmTapDispatcher.dispatch(
            tapText = { calls += "text"; it(true) },
            tapCoordinates = null,
            completion = { calls += "complete:$it" },
            preferCoordinates = true,
        )
        assertEquals(listOf("text", "complete:true"), calls)
    }

    @Test
    fun fallsBackToModelCoordinatesWhenTextConfirmationIsUnavailable() {
        val calls = mutableListOf<String>()

        ConfirmTapDispatcher.dispatch(
            tapText = { completion ->
                calls += "text"
                completion(false)
            },
            tapCoordinates = { completion ->
                calls += "coordinates"
                completion(true)
            },
            completion = { succeeded -> calls += "complete:$succeeded" },
        )

        assertEquals(listOf("text", "coordinates", "complete:true"), calls)
    }

    @Test
    fun usesTextConfirmationWithoutSendingAModelCoordinateTap() {
        val calls = mutableListOf<String>()

        ConfirmTapDispatcher.dispatch(
            tapText = { completion ->
                calls += "text"
                completion(true)
            },
            tapCoordinates = { completion ->
                calls += "coordinates"
                completion(true)
            },
            completion = { succeeded -> calls += "complete:$succeeded" },
        )

        assertEquals(listOf("text", "complete:true"), calls)
    }

    @Test
    fun failsOnlyAfterTextAndCoordinateConfirmationAreBothUnavailable() {
        val calls = mutableListOf<String>()

        ConfirmTapDispatcher.dispatch(
            tapText = { completion ->
                calls += "text"
                completion(false)
            },
            tapCoordinates = null,
            completion = { succeeded -> calls += "complete:$succeeded" },
        )

        assertEquals(listOf("text", "complete:false"), calls)
    }
}
