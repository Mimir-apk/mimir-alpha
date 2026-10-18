package com.gruning.mimir.rewrite.core.companionnative

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryDisplaySelectorTest {
    @Test
    fun `selection filters unsafe candidates sorts fallback and preserves current id across reorder`() {
        val model = SecondaryDisplaySelectionModel()
        assertTrue(
            model.update(
                listOf(
                    SecondaryDisplayCandidate(5, isPresentation = true, isValid = true),
                    SecondaryDisplayCandidate(4, isPresentation = true, isValid = true),
                    SecondaryDisplayCandidate(Display.DEFAULT_DISPLAY, isPresentation = true, isValid = true),
                    SecondaryDisplayCandidate(8, isPresentation = false, isValid = true),
                    SecondaryDisplayCandidate(9, isPresentation = true, isValid = false),
                ),
            ),
        )
        assertEquals(listOf(4, 5), model.snapshot.eligibleIds)
        assertEquals(4, model.snapshot.selectedId)
        assertEquals(null, model.snapshot.soleEligibleId)

        assertTrue(
            "a newly eligible lower id must not retarget the companion surface",
            model.update(
                listOf(
                    SecondaryDisplayCandidate(3, isPresentation = true, isValid = true),
                    SecondaryDisplayCandidate(4, isPresentation = true, isValid = true),
                    SecondaryDisplayCandidate(5, isPresentation = true, isValid = true),
                ),
            ),
        )
        assertEquals(listOf(3, 4, 5), model.snapshot.eligibleIds)
        assertEquals(4, model.snapshot.selectedId)

        assertTrue(
            model.update(listOf(SecondaryDisplayCandidate(7, isPresentation = true, isValid = true))),
        )
        assertEquals(listOf(7), model.snapshot.eligibleIds)
        assertEquals(7, model.snapshot.selectedId)
        assertEquals(7, model.snapshot.soleEligibleId)
    }

    @Test
    fun `snapshot changes before ordered listeners and close stops later callbacks`() {
        val observed = mutableListOf<SecondaryDisplaySnapshot>()
        val callbackSnapshots = mutableListOf<Pair<SecondaryDisplaySnapshot, SecondaryDisplaySnapshot>>()
        val model = SecondaryDisplaySelectionModel()
        val subscription = model.addListener { value ->
            observed += value
            callbackSnapshots += value to model.snapshot
        }

        assertEquals(1, observed.size)
        assertTrue(model.update(listOf(SecondaryDisplayCandidate(4, true, true))))
        assertEquals(listOf<Int?>(null, 4), observed.map(SecondaryDisplaySnapshot::selectedId))
        assertFalse(
            model.update(listOf(SecondaryDisplayCandidate(4, true, true))),
        )

        subscription.close()
        assertTrue(model.update(emptyList()))
        assertEquals(2, observed.size)
        assertTrue(callbackSnapshots.all { (callback, published) -> callback == published })
    }

    @Test
    fun `one failing listener does not starve later consumers`() {
        val failures = mutableListOf<Throwable>()
        val later = mutableListOf<Int?>()
        val model = SecondaryDisplaySelectionModel(onListenerFailure = failures::add)
        model.addListener { error("consumer failure") }
        model.addListener { later += it.selectedId }

        assertTrue(model.update(listOf(SecondaryDisplayCandidate(5, true, true))))
        assertEquals(2, failures.size)
        assertEquals(listOf<Int?>(null, 5), later)
    }

    @Test
    fun `nested update queues newest snapshot and closed listener is skipped`() {
        val observed = mutableListOf<String>()
        val flowAfterNestedUpdate = mutableListOf<Int?>()
        val model = SecondaryDisplaySelectionModel()
        var secondSubscription: java.io.Closeable? = null
        var nested = false

        model.addListener { snapshot ->
            observed += "first:${snapshot.selectedId}"
            if (snapshot.selectedId == 4 && !nested) {
                nested = true
                secondSubscription?.close()
                model.update(listOf(SecondaryDisplayCandidate(5, true, true)))
                flowAfterNestedUpdate += model.flow.value.selectedId
            }
        }
        secondSubscription = model.addListener { snapshot ->
            observed += "second:${snapshot.selectedId}"
        }
        model.addListener { snapshot ->
            observed += "third:${snapshot.selectedId}"
        }

        assertTrue(model.update(listOf(SecondaryDisplayCandidate(4, true, true))))
        assertEquals(
            listOf("first:null", "second:null", "third:null", "first:4", "first:5", "third:5"),
            observed,
        )
        assertEquals(listOf<Int?>(5), flowAfterNestedUpdate)
    }

    @Test
    fun `closed model suppresses future updates and subscriptions`() {
        var callbackCount = 0
        val model = SecondaryDisplaySelectionModel()
        model.addListener { callbackCount += 1 }
        assertEquals(1, callbackCount)

        model.close()

        assertFalse(model.update(listOf(SecondaryDisplayCandidate(4, true, true))))
        model.addListener { callbackCount += 1 }
        assertEquals(1, callbackCount)
    }
}
