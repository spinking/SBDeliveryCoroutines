package ru.skillbranch.sbdelivery

import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import ru.skillbranch.sbdelivery.data.db.dao.CartDao
import ru.skillbranch.sbdelivery.data.db.dao.DishesDao
import ru.skillbranch.sbdelivery.data.db.entity.CartItemPersist
import ru.skillbranch.sbdelivery.data.network.RestService
import ru.skillbranch.sbdelivery.data.toDishItem
import ru.skillbranch.sbdelivery.repository.DishesRepository
import ru.skillbranch.sbdelivery.screens.dishes.data.DishesUiState
import ru.skillbranch.sbdelivery.screens.dishes.logic.DishesEffHandler
import ru.skillbranch.sbdelivery.screens.dishes.logic.DishesFeature
import ru.skillbranch.sbdelivery.screens.dishes.logic.selfReduce
import ru.skillbranch.sbdelivery.screens.root.logic.Eff
import ru.skillbranch.sbdelivery.screens.root.logic.Msg
import ru.skillbranch.sbdelivery.screens.root.logic.NavigateCommand


/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@ExperimentalCoroutinesApi
class ExampleUnitTest {
    private val testDispatcher = TestCoroutineDispatcher()

    private val mockApi = mockk<RestService>()
    private val dishesDao = mockk<DishesDao>()
    private val cartDao = mockk<CartDao>()
    private val mockChannel = mockk<Channel<Eff.Notification>>()

    private val repository = DishesRepository(mockApi, dishesDao, cartDao)

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

        coEvery { dishesDao.findAllDishes() } returns Stubs.dishesPersist
        coEvery { dishesDao.dishesCounts() } returns 0
        coEvery { dishesDao.insertDishes(any()) } coAnswers {}
        coEvery { mockApi.getDishes(0, 10) } returns Response.success(Stubs.dishesRes)
        coEvery { mockApi.getDishes(10, any()) } returns Response.error(400, "".toResponseBody())


        val feature = TestFeature<DishesFeature.State, DishesFeature.Msg, DishesFeature.Eff>(
            DishesFeature.initialState(),
            DishesFeature.initialEffects().mapTo(HashSet(), Eff::Dishes),
            DishesFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishesEffHandler(repository, mockChannel))
        }

        feature.test(3000) { states, msgs, effs ->
            assertEquals(DishesFeature.initialState(), states.first())
            assertEquals(DishesUiState.Value(Stubs.dishItems), states.last().list)

            assertEquals(
                listOf(
                    DishesFeature.Msg.ShowLoading,
                    DishesFeature.Msg.ShowDishes(Stubs.dishItems)
                ).map(Msg::Dishes),
                msgs
            )
        }

        coVerify { dishesDao.findAllDishes() }
        coVerify { dishesDao.dishesCounts() }
        coVerify { dishesDao.insertDishes(any()) }
        coVerify(atLeast = 2) { mockApi.getDishes(any(), any()) }
    }

    @InternalCoroutinesApi
    @Test
    fun search_by_dishes() {
        val initialState = DishesFeature.State(list = DishesUiState.Value(Stubs.dishItems))

        coEvery { dishesDao.findDishesFrom(any()) } returns Stubs.searchResult

        val feature = TestFeature<DishesFeature.State, DishesFeature.Msg, DishesFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishesFeature.State::selfReduce,
        )
        TestCoroutineScope().launch {
            feature.listenLocal(this, DishesEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishesFeature.Msg.SearchToggle)
            feature.accept(DishesFeature.Msg.SearchInput("Бургер"))
            feature.accept(DishesFeature.Msg.SearchSubmit("Бургер"))
        }

        feature.test { states, _, effs ->
            assertEquals(initialState.copy(isSearch = true), states.drop(1).first())
            assertEquals(
                initialState.copy(isSearch = true, input = "Бургер"),
                states.drop(2).first()
            )
            assertEquals(
                initialState.copy(
                    isSearch = true,
                    input = "Бургер",
                    list = DishesUiState.Loading
                ), states.drop(3).first()
            )
            assertEquals(
                initialState.copy(
                    isSearch = true,
                    input = "Бургер",
                    list = DishesUiState.Value(Stubs.searchResultItems)
                ),
                states.last()
            )

            assertEquals(listOf(Eff.Dishes(DishesFeature.Eff.SearchDishes("Бургер"))), effs)
        }
    }

    @InternalCoroutinesApi
    @Test
    fun show_suggestions() {
        val initialState = DishesFeature.State(list = DishesUiState.Value(Stubs.dishItems))

        coEvery { dishesDao.findDishesFrom(any()) } returns Stubs.searchResult

        val feature = TestFeature<DishesFeature.State, DishesFeature.Msg, DishesFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishesFeature.State::selfReduce,
        )
        TestCoroutineScope().launch {
            feature.listenLocal(this, DishesEffHandler(repository, mockChannel))
        }
        runBlockingTest {
            feature.accept(DishesFeature.Msg.UpdateSuggestionResult("Бу"))
            feature.accept(DishesFeature.Msg.SuggestionSelect("бургер"))
        }

        feature.test { states, _, effs ->
            assertEquals(
                initialState.copy(suggestions = mapOf("бургер" to 8, "люксембург" to 1)),
                states.drop(2).first()
            )
            assertEquals(
                initialState.copy(
                    input = "бургер",
                    suggestions = emptyMap()
                ),
                states.drop(3).first()
            )
            assertEquals(
                DishesUiState.Value(Stubs.searchResult.map { it.toDishItem() }),
                states.last().list
            )


            assertEquals(
                listOf(
                    DishesFeature.Eff.FindSuggestions("Бу"),
                    DishesFeature.Eff.SearchDishes("бургер")
                ).map(Eff::Dishes),
                effs
            )
        }

    }

    @InternalCoroutinesApi
    @Test
    fun add_and_remove_from_cart() {
        val initialState = DishesFeature.State(list = DishesUiState.Value(Stubs.dishItems))

        coEvery { cartDao.dishCount("test") } returns 0
        coEvery { cartDao.dishCount("test2") } returns 2
        coEvery { cartDao.cartCount() } returns 0
        coEvery { cartDao.updateItemCount("test2", any()) } coAnswers {}
        coEvery { cartDao.addItem(any()) } coAnswers {}
        coEvery { cartDao.removeItem(any()) } coAnswers {}
        coEvery { mockChannel.send(any()) } coAnswers {}

        coEvery { cartDao.decrementItemCount("test2") } coAnswers {}
        coEvery { cartDao.removeItem("test") } coAnswers {}

        val feature = TestFeature<DishesFeature.State, DishesFeature.Msg, DishesFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishesFeature.State::selfReduce,
        )
        TestCoroutineScope().launch {
            feature.listenLocal(this, DishesEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishesFeature.Msg.AddToCart("test", "test"))
            feature.accept(DishesFeature.Msg.AddToCart("test2", "test2"))
            feature.accept(DishesFeature.Msg.RemoveFromCart("test2", "test2"))
            feature.accept(DishesFeature.Msg.RemoveFromCart("test", "test"))
        }

        feature.test { _, _, effs ->
            assertEquals(
                listOf(
                    DishesFeature.Eff.AddToCart(id = "test", title = "test"),
                    DishesFeature.Eff.AddToCart(id = "test2", title = "test2"),
                    DishesFeature.Eff.RemoveFromCart(id = "test2", title = "test2"),
                    DishesFeature.Eff.RemoveFromCart(id = "test", title = "test")
                ).map(Eff::Dishes),
                effs
            )
        }

        coVerify(ordering = Ordering.SEQUENCE) {
            cartDao.dishCount("test")
            cartDao.addItem(CartItemPersist(dishId = "test"))
            cartDao.cartCount()
            mockChannel.send(
                Eff.Notification.Action(
                    "test успешно добавлен в корзину",
                    "Отмена",
                    Msg.Dishes(DishesFeature.Msg.RemoveFromCart("test", "test"))
                )
            )

            cartDao.dishCount("test2")
            cartDao.updateItemCount("test2", 3)
            cartDao.cartCount()
            mockChannel.send(
                Eff.Notification.Action(
                    "test2 успешно добавлен в корзину",
                    "Отмена",
                    Msg.Dishes(DishesFeature.Msg.RemoveFromCart("test2", "test2"))
                )
            )

            cartDao.dishCount("test2")
            cartDao.decrementItemCount("test2")
            cartDao.cartCount()
            mockChannel.send(Eff.Notification.Text("test2 удален из корзины"))

            cartDao.dishCount("test")
            cartDao.removeItem("test")
            cartDao.cartCount()
            mockChannel.send(Eff.Notification.Text("test удален из корзины"))
        }
    }

    @InternalCoroutinesApi
    @Test
    fun click_on_dish() {
        val initialState = DishesFeature.State(list = DishesUiState.Value(Stubs.dishItems))

        val feature = TestFeature<DishesFeature.State, DishesFeature.Msg, DishesFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishesFeature.State::selfReduce,
        )
        TestCoroutineScope().launch {
            feature.listenLocal(this, DishesEffHandler(repository, mockChannel))
        }
        runBlockingTest {
            feature.accept(DishesFeature.Msg.ClickDish("test", "test"))
        }
        feature.test { _, _, effs ->
            assertEquals(
                listOf(
                   Eff.Navigate(NavigateCommand.ToDishItem("test", "test"))
                ),
                effs
            )
        }
    }

}