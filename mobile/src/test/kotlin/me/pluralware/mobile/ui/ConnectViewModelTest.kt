package me.pluralware.mobile.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.repository.InMemoryTokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private val system = SystemInfo("sys", "uuid-sys", "Sample", null)
    private val tokens = InMemoryTokenStore()
    private val steps = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        onConnected: suspend (SystemInfo) -> Unit = { steps += "connected:${it.uuid}" },
        validate: suspend (PluralKitToken) -> SystemInfo = { system },
    ) = ConnectViewModel(
        tokenStore = tokens,
        sendToWatch = { steps += "watch:${it.raw}" },
        onConnected = onConnected,
        validate = validate,
    )

    @Test
    fun `a connection the account can't take says so and saves nothing`() {
        val vm = viewModel(onConnected = { error("Couldn't clear the last system's sharing.") })
        vm.onInputChange("token")

        vm.connect()

        assertNull(tokens.tokenFlow.value)
        assertTrue(steps.isEmpty())
        assertEquals("Couldn't clear the last system's sharing.", vm.state.value.error)
        assertFalse(vm.state.value.validating)
    }

    @Test
    fun `a valid token is checked, claimed, sent on and saved, in that order`() {
        val vm = viewModel()
        vm.onInputChange("  token  ")

        vm.connect()

        assertEquals(listOf("connected:uuid-sys", "watch:token"), steps)
        assertEquals(PluralKitToken("token"), tokens.tokenFlow.value)
    }

    @Test
    fun `a rejected token says so and saves nothing`() {
        val vm = viewModel { throw PluralKitHttpException(401, "Unauthorized") }
        vm.onInputChange("bad")

        vm.connect()

        assertNull(tokens.tokenFlow.value)
        assertTrue(steps.isEmpty())
        assertTrue(vm.state.value.error!!.contains("wasn't accepted"))
        assertFalse(vm.state.value.validating)
    }

    @Test
    fun `an unreachable PluralKit says so`() {
        val vm = viewModel { throw java.io.IOException("") }
        vm.onInputChange("token")

        vm.connect()

        assertTrue(vm.state.value.error!!.contains("Couldn't reach PluralKit"))
    }

    @Test
    fun `typing again clears the error`() {
        val vm = viewModel { throw PluralKitHttpException(401, "Unauthorized") }
        vm.onInputChange("bad")
        vm.connect()
        assertNotNull(vm.state.value.error)

        vm.onInputChange("better")

        assertNull(vm.state.value.error)
    }

    @Test
    fun `a blank token does nothing`() {
        val vm = viewModel()
        vm.onInputChange("   ")

        vm.connect()

        assertFalse(vm.state.value.validating)
        assertNull(tokens.tokenFlow.value)
    }

    @Test
    fun `a second tap while checking is ignored`() {
        val answer = CompletableDeferred<SystemInfo>()
        var checks = 0
        val vm = viewModel {
            checks++
            answer.await()
        }
        vm.onInputChange("token")

        vm.connect()
        assertTrue(vm.state.value.validating)
        vm.connect()
        answer.complete(system)

        assertEquals(1, checks)
        assertEquals(PluralKitToken("token"), tokens.tokenFlow.value)
    }
}
