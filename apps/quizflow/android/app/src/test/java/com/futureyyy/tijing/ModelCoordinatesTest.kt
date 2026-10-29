package com.futureyyy.tijing

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ModelCoordinatesTest {
    private fun value(s: String) = JsonParser.parseString(s)

    @Test fun `percentage boxes remain unchanged`() {
        assertEquals(NormalizedBox(8f, 35f, 84f, 8f), ModelCoordinates().box(value("[8,35,84,8]"), "options[0].box"))
    }

    @Test fun `tiny rounding overflow at screen bottom is corrected`() {
        val b = ModelCoordinates().box(value("[20,95,60,5.000001]"), "confirm_box")!!
        assertEquals(100f, b.y + b.height, 0f)
    }

    @Test fun `all-zero absent detection is not clickable`() {
        assertNull(ModelCoordinates().box(value("[0,0,0,0]"), "next_box"))
        assertNull(ModelCoordinates().box(value("null"), "next_box"))
    }

    @Test fun `declared pixels use actual capture size`() {
        assertEquals(NormalizedBox(10f, 90f, 80f, 5f),
            ModelCoordinates("pixels", "xywh", 1000, 2000).box(value("[100,1800,800,100]"), "confirm_box"))
    }

    @Test fun `declared corner boxes are converted to width and height`() {
        assertEquals(NormalizedBox(10f, 90f, 80f, 5f),
            ModelCoordinates("percent", "xyxy").box(value("[10,90,90,95]"), "confirm_box"))
    }

    @Test fun `declared normalized coordinates are scaled once`() {
        val b = ModelCoordinates("normalized").box(value("[0.1,0.9,0.8,0.05]"), "confirm_box")!!
        assertEquals(10f, b.x, 0.001f)
        assertEquals(90f, b.y, 0.001f)
        assertEquals(80f, b.width, 0.001f)
    }

    @Test fun `large overflow is not clamped to a different clickable location`() {
        val e = assertThrows(CoordinateResponseException::class.java) {
            ModelCoordinates().box(value("[90,90,20,20]"), "next_box")
        }
        assertTrue(e.message!!.contains("next_box"))
        assertTrue(e.message!!.contains("重新识别"))
    }

    @Test fun `pixels are never guessed without declared units`() {
        assertThrows(CoordinateResponseException::class.java) {
            ModelCoordinates().box(value("[100,1800,800,100]"), "confirm_box")
        }
    }

    @Test fun `declared pixels without capture dimensions are rejected`() {
        assertThrows(CoordinateResponseException::class.java) {
            ModelCoordinates("pixels").box(value("[10,90,80,5]"), "confirm_box")
        }
    }

    @Test fun `incorrect or inverted box format is rejected`() {
        assertThrows(CoordinateResponseException::class.java) {
            ModelCoordinates("percent", "xyxy").box(value("[90,95,10,90]"), "confirm_box")
        }
        assertThrows(CoordinateResponseException::class.java) {
            ModelCoordinates("unknown").box(value("[10,90,80,5]"), "confirm_box")
        }
    }

    @Test fun `invalid shapes include field name for repair feedback`() {
        for (bad in listOf("[1,2]", "[1,2,3,\"bad\"]", "[10,20,0,5]", "[-5,20,10,5]")) {
            val e = assertThrows(CoordinateResponseException::class.java) {
                ModelCoordinates().box(value(bad), "options[3].box")
            }
            assertTrue(e.message!!.contains("options[3].box"))
        }
    }

    @Test fun `full observation parses declared pixel options and navigation`() {
        val o = FlowObservation.parse("""{"coordinate_space":"pixels","box_format":"xywh","page_kind":"question","confidence":0.99,"question_key":"1","question":"题干","question_complete":true,"options":[{"text":"保护性","box":[100,800,800,100],"selected":true}],"answer_texts":["保护性"],"confirm_box":[100,1800,800,100],"next_box":[0,0,0,0]}""", 1000, 2000)
        assertEquals(NormalizedBox(10f, 40f, 80f, 5f), o.options[0].box)
        assertEquals(NormalizedBox(10f, 90f, 80f, 5f), o.confirm)
        assertNull(o.next)
        assertEquals(FlowAction.Confirm(o.confirm, 1), AutomationPolicy().decide(o))
    }
}
