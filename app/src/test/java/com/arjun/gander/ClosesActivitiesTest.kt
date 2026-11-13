package com.arjun.gander

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/** Every test builds its activities through [ClosesActivities], so none is left running. */
class ClosesActivitiesTest {

    @Test
    fun noTestBuildsAnActivityThatIsNotClosed() {
        val dir = File("src/test/java/com/arjun/gander")
        val open = dir.listFiles()!!
            .filter { it.name.endsWith(".kt") && it.name !in setOf("ClosesActivities.kt", "ClosesActivitiesTest.kt") }
            .filter { "Robolectric.buildActivity(" in it.readText() }
            .map { it.name }
        assertWithMessage("build these through ClosesActivities: $open").that(open).isEmpty()
    }
}
