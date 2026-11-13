package com.arjun.gander

import android.app.Activity
import android.content.Intent
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Builds a test's activities and takes each to destroyed when the test ends.
 *
 * Robolectric leaves an activity where a test left it, so a viewer's player went on running
 * into the tests after. Its error could reach the next test's main looper and throw there,
 * as "Receiver not registered" in a test that opened no media, and by OverChromeTest some
 * twenty players were running at once, which starved its wait for a broken track to fail.
 * Closing a viewer runs onDestroy, which releases its player.
 */
class ClosesActivities : ExternalResource() {

    private val built = mutableListOf<ActivityController<*>>()

    fun <T : Activity> build(type: Class<T>, intent: Intent? = null): ActivityController<T> =
        Robolectric.buildActivity(type, intent).also { built += it }

    // Newest first, as a stack of screens would close
    override fun after() {
        built.asReversed().forEach { it.close() }
        built.clear()
    }
}
