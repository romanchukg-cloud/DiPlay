package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.location.HeadingTracker.CourseUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTrackerTest {
    private fun sample(course: Double?, speed: Double?) = CourseSample(0L, course, speed)

    @Test
    fun gpsCourseCountsOnlyWhenMovingFastEnough() {
        val tracker = HeadingTracker(initialDegrees = 10.0)

        // Parking speed: the course is ignored, the stored heading stays.
        assertEquals(CourseUse.IGNORED, tracker.onCourse(sample(200.0, 1.0)))
        assertEquals(10.0, tracker.degrees!!, 0.001)
        assertEquals(HeadingTracker.Source.STORED, tracker.source)

        assertEquals(CourseUse.FORWARD, tracker.onCourse(sample(95.0, 8.0)))
        assertEquals(95.0, tracker.degrees!!, 0.001)
        assertEquals(HeadingTracker.Source.GPS, tracker.source)

        // No course or no speed: nothing changes.
        assertEquals(CourseUse.IGNORED, tracker.onCourse(sample(null, 8.0)))
        assertEquals(CourseUse.IGNORED, tracker.onCourse(sample(120.0, null)))
        assertEquals(95.0, tracker.degrees!!, 0.001)
    }

    @Test
    fun aBackwardsCourseIsReversing() {
        val tracker = HeadingTracker(initialDegrees = 246.0)

        // Reversing fast: the GPS course points behind the car.
        assertEquals(CourseUse.REVERSING, tracker.onCourse(sample(69.0, 3.5)))
        assertEquals(249.0, tracker.degrees!!, 0.001)
        assertEquals(CourseUse.REVERSING, tracker.onCourse(sample(75.0, 3.5)))
        assertEquals(255.0, tracker.degrees!!, 0.001)

        // Forward again: the course and the heading agree.
        assertEquals(CourseUse.FORWARD, tracker.onCourse(sample(250.0, 5.0)))
        assertEquals(250.0, tracker.degrees!!, 0.001)
    }

    @Test
    fun aLastingOppositeCourseMeansTheHeadingWasWrong() {
        // For example, the car was turned around while switched off.
        val tracker = HeadingTracker(initialDegrees = 0.0)
        repeat(HeadingTracker.REVERSING_FIXES_MAX - 1) {
            assertEquals(CourseUse.REVERSING, tracker.onCourse(sample(180.0, 10.0)))
            assertEquals(0.0, tracker.degrees!!, 0.001)
        }
        assertEquals(CourseUse.FORWARD, tracker.onCourse(sample(180.0, 10.0)))
        assertEquals(180.0, tracker.degrees!!, 0.001)
        assertEquals(CourseUse.FORWARD, tracker.onCourse(sample(182.0, 10.0)))
    }

    @Test
    fun aUTurnAtSpeedIsFollowedStepByStep() {
        val tracker = HeadingTracker(initialDegrees = 0.0)
        for (course in listOf(30.0, 60.0, 90.0, 120.0, 150.0, 180.0)) {
            assertEquals(CourseUse.FORWARD, tracker.onCourse(sample(course, 4.0)))
        }
        assertEquals(180.0, tracker.degrees!!, 0.001)
    }

    @Test
    fun gyroTurnsFollowTheCompassDirection() {
        val tracker = HeadingTracker(initialDegrees = 0.0)

        // A right turn is clockwise seen from above: counter-clockwise rotation is negative.
        tracker.onTurn(-90.0)
        assertEquals(90.0, tracker.degrees!!, 0.001)
        assertEquals(HeadingTracker.Source.GYRO, tracker.source)

        // Turning left past north wraps around.
        tracker.onTurn(135.0)
        assertEquals(315.0, tracker.degrees!!, 0.001)
    }

    @Test
    fun nothingToTurnWithoutAHeading() {
        val tracker = HeadingTracker()
        tracker.onTurn(-45.0)
        assertNull(tracker.degrees)
    }

    @Test
    fun differenceTakesTheShortWay() {
        assertEquals(20.0, HeadingTracker.difference(350.0, 10.0), 0.001)
        assertEquals(-20.0, HeadingTracker.difference(10.0, 350.0), 0.001)
        assertEquals(-180.0, HeadingTracker.difference(0.0, 180.0), 0.001)
    }

    @Test
    fun hdtSentenceHasAValidChecksum() {
        val sentence = HdtEncoder.encode(-0.04).trim()
        assertEquals("\$GPHDT,0.0,T", sentence.substringBefore('*'))
        val body = sentence.substring(1, sentence.indexOf('*'))
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        assertEquals(String.format("%02X", checksum), sentence.substringAfter('*'))
        assertEquals("\$GPHDT,90.5,T", HdtEncoder.encode(90.5).trim().substringBefore('*'))
    }
}
