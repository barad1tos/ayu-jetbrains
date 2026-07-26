package dev.ayuislands.glow.waveform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutePlanTest {
    @Test
    fun `visible window clips completed and future spans`() {
        val plan =
            RoutePlan(
                spans =
                    listOf(
                        RouteSpan(perimeter("Project"), 0f),
                        RouteSpan(perimeter("Editor"), SPAN_LENGTH),
                        RouteSpan(perimeter("Commit"), SPAN_LENGTH * 2f),
                    ),
                activeIndex = 1,
                distanceOnSpan = SPAN_LENGTH / 2f,
            )

        val slices = plan.visibleSlices(signalSpan = 15f)

        assertEquals(listOf("Project", "Editor", "Commit"), slices.map(RouteSlice::surfaceId))
        assertTrue(slices.all { slice -> slice.distanceOffset >= 15f })
        assertTrue(
            slices.all { slice ->
                slice.distanceOffset + openWaveformTrack(slice.samples).length <= 45f
            },
        )
    }

    @Test
    fun `slice offset follows the first clipped sample`() {
        val plan =
            RoutePlan(
                spans = listOf(RouteSpan(perimeter("Editor"), 20f)),
                activeIndex = 0,
                distanceOnSpan = 10f,
            )

        val slice = plan.visibleSlices(signalSpan = 2f).single()

        assertEquals(25f, slice.distanceOffset)
        assertEquals(0f, slice.samples.first().distance)
        assertEquals(10f, slice.samples.last().distance)
    }

    private fun perimeter(surfaceId: String): RouteLeg.Perimeter =
        RouteLeg.Perimeter(
            surfaceId = surfaceId,
            entryDistance = 0f,
            direction = TravelDirection.CLOCKWISE,
            handoff = null,
            lapDistance = SPAN_LENGTH,
            length = SPAN_LENGTH,
            samples =
                (0..4).map { index ->
                    val distance = index * SAMPLE_STEP
                    WaveformSample(
                        x = distance,
                        y = 0f,
                        normalX = 0f,
                        normalY = 1f,
                        distance = distance,
                        amplitudeMask = 1f,
                    )
                },
            target = RoutePaintTarget.Root(RouteRootId(surfaceId.hashCode())),
            inwardEdges = emptySet(),
            signalSpan = 10f,
            entrySpan = null,
            spanDistanceRatio = 0f,
        )
}

private const val SAMPLE_STEP = 5f
private const val SPAN_LENGTH = 20f
