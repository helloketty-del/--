package com.futureyyy.tijing

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmTapDispatcherTest {
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
