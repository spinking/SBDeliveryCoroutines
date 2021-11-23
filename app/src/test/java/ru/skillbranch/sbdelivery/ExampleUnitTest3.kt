package ru.skillbranch.sbdelivery

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.skillbranch.sbdelivery.data.db.dao.CartDao
import ru.skillbranch.sbdelivery.data.network.RestService
import ru.skillbranch.sbdelivery.data.toCartItem
import ru.skillbranch.sbdelivery.repository.CartRepository
import ru.skillbranch.sbdelivery.screens.cart.data.CartUiState
import ru.skillbranch.sbdelivery.screens.cart.data.ConfirmDialogState
import ru.skillbranch.sbdelivery.screens.cart.logic.CartEffHandler
import ru.skillbranch.sbdelivery.screens.cart.logic.CartFeature
import ru.skillbranch.sbdelivery.screens.cart.logic.selfReduce
import ru.skillbranch.sbdelivery.screens.root.logic.Eff


/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@ExperimentalCoroutinesApi
class ExampleUnitTest3 {
    private val testDispatcher = TestCoroutineDispatcher()

    private val mockApi = mockk<RestService>()
    private val cartDao = mockk<CartDao>()
    private val mockChannel = mockk<Channel<Eff.Notification>>()

    private val repository = CartRepository(mockApi, cartDao)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset main dispatcher to the original Main dispatcher
        testDispatcher.cleanupTestCoroutines()
    }

    @InternalCoroutinesApi
    @Test
    fun sync() {

        coEvery { cartDao.findCartItems() } returns Stubs.cartPersistItems


        val feature = TestFeature<CartFeature.State, CartFeature.Msg, CartFeature.Eff>(
            CartFeature.initialState().copy(),
            CartFeature.initialEffects().mapTo(HashSet(), Eff::Cart),
            CartFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, CartEffHandler(repository, mockChannel))
        }

        feature.test { states, msgs, effs ->
            assertEquals(CartFeature.initialState(), states.first())
            assertEquals(
               CartFeature.initialState().copy(list = CartUiState.Value(Stubs.cartItems)), states.last()
            )
        }
    }

    @InternalCoroutinesApi
    @Test
    fun show_hide_confirm() {
        val initialState = CartFeature.State(list = CartUiState.Value(Stubs.cartItems))


        val feature = TestFeature<CartFeature.State, CartFeature.Msg, CartFeature.Eff>(
            initialState.copy(),
            emptySet(),
            CartFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, CartEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(CartFeature.Msg.ShowConfirm("test", "test"))
            feature.accept(CartFeature.Msg.HideConfirm)
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(confirmDialog = ConfirmDialogState.Show("test", "test")), states.drop(1).first())
            assertEquals(initialState.copy(confirmDialog = ConfirmDialogState.Hide), states.last())
        }
    }

    @InternalCoroutinesApi
    @Test
    fun increment_decrement_items() {
        val initialState = CartFeature.State(list = CartUiState.Value(Stubs.cartItems))

        val incList = Stubs.cartPersistItems
            .toMutableList()
            .also { it[0] = it.first().copy(count = 11) }

        coEvery { cartDao.incrementItemCount(any()) } coAnswers {}
        coEvery { cartDao.decrementItemCount(any()) } coAnswers {}

        coEvery { cartDao.findCartItems() } returns incList

        val feature = TestFeature<CartFeature.State, CartFeature.Msg, CartFeature.Eff>(
            initialState.copy(),
            emptySet(),
            CartFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, CartEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(CartFeature.Msg.IncrementCount("test1"))
        }

        val decList = Stubs.cartPersistItems
            .toMutableList()
            .also { it[0] = it.first().copy(count = 9) }

        coEvery { cartDao.findCartItems() } returns decList

        runBlockingTest {
            feature.accept(CartFeature.Msg.DecrementCount("test1"))
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(list = CartUiState.Value(incList.map { it.toCartItem() })), states.drop(2).first())
            assertEquals(initialState.copy(list = CartUiState.Value(decList.map { it.toCartItem() })), states.last())
        }
    }

    @InternalCoroutinesApi
    @Test
    fun remove_items() {
        val initialState = CartFeature.State(list = CartUiState.Value(Stubs.cartItems), confirmDialog = ConfirmDialogState.Show("test1", "test"))

        val removeList = Stubs.cartPersistItems
            .drop(1)

        coEvery { cartDao.findCartItems() } returns removeList
        coEvery { cartDao.removeItem(any()) } coAnswers {}


        val feature = TestFeature<CartFeature.State, CartFeature.Msg, CartFeature.Eff>(
            initialState.copy(),
            emptySet(),
            CartFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, CartEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(CartFeature.Msg.RemoveFromCart("test1"))
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(list = CartUiState.Value(removeList.map { it.toCartItem() }), confirmDialog = ConfirmDialogState.Hide), states.last())
        }
    }

    @InternalCoroutinesApi
    @Test
    fun send_order() {
        val initialState = CartFeature.State(list = CartUiState.Value(Stubs.cartItems))

        coEvery { cartDao.findCartItems() } returns emptyList()
        coEvery { cartDao.clearCart() } coAnswers {}
        coEvery { mockChannel.send(any()) } coAnswers {}


        val feature = TestFeature<CartFeature.State, CartFeature.Msg, CartFeature.Eff>(
            initialState.copy(),
            emptySet(),
            CartFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, CartEffHandler(repository, mockChannel))
        }

        runBlocking {
            feature.accept(CartFeature.Msg.SendOrder(mapOf("test" to 1)))
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(list = CartUiState.Empty), states.last())
        }

        coVerify { mockChannel.send(Eff.Notification.Text("Заказ оформлен")) }
    }
}