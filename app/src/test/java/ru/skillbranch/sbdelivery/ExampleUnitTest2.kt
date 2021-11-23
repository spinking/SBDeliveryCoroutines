package ru.skillbranch.sbdelivery

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
import ru.skillbranch.sbdelivery.data.network.RestService
import ru.skillbranch.sbdelivery.data.network.req.ReviewReq
import ru.skillbranch.sbdelivery.data.network.res.ReviewRes
import ru.skillbranch.sbdelivery.repository.DishRepository
import ru.skillbranch.sbdelivery.screens.dish.data.DishUiState
import ru.skillbranch.sbdelivery.screens.dish.data.ReviewUiState
import ru.skillbranch.sbdelivery.screens.dish.logic.DishEffHandler
import ru.skillbranch.sbdelivery.screens.dish.logic.DishFeature
import ru.skillbranch.sbdelivery.screens.dish.logic.selfReduce
import ru.skillbranch.sbdelivery.screens.root.logic.Eff
import ru.skillbranch.sbdelivery.screens.root.logic.Msg


/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@ExperimentalCoroutinesApi
class ExampleUnitTest2 {
    private val testDispatcher = TestCoroutineDispatcher()

    private val mockApi = mockk<RestService>()
    private val dishesDao = mockk<DishesDao>()
    private val cartDao = mockk<CartDao>()
    private val mockChannel = mockk<Channel<Eff.Notification>>()

    private val repository = DishRepository(mockApi, dishesDao, cartDao)

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

        coEvery { dishesDao.findDish(any()) } returns Stubs.dishPersist
        coEvery { mockApi.getReviews(any(), 0, 10) } returns Response.success(Stubs.comments)
        coEvery { mockApi.getReviews(any(), 10, any()) } returns Response.error(
            400,
            "".toResponseBody()
        )

        val feature = TestFeature<DishFeature.State, DishFeature.Msg, DishFeature.Eff>(
            DishFeature.initialState(),
            DishFeature.initialEffects("test").mapTo(HashSet(), Eff::Dish),
            DishFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishEffHandler(repository, mockChannel))
        }

        feature.test { states, _, _ ->
            assertEquals(DishFeature.initialState(), states.first())
            assertEquals(
                DishFeature.initialState().copy(
                    reviews = ReviewUiState.Value(Stubs.comments),
                    content = DishUiState.Value(Stubs.dishContent)
                ), states.last()
            )
        }

        coVerify { dishesDao.findDish("test") }
        coVerify(atLeast = 2) { mockApi.getReviews("test", any(), any()) }
    }

    @InternalCoroutinesApi
    @Test
    fun toggle_like() {
        val initialState = DishFeature.State(
            id = "test",
            title = "test",
            reviews = ReviewUiState.Value(Stubs.comments),
            content = DishUiState.Value(Stubs.dishContent)
        )

        coEvery { dishesDao.findDishesFrom(any()) } returns Stubs.searchResult

        val feature = TestFeature<DishFeature.State, DishFeature.Msg, DishFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishFeature.Msg.ToggleLike)
            feature.accept(DishFeature.Msg.ToggleLike)
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(isLiked = true), states.drop(1).first())
            assertEquals(initialState.copy(isLiked = false), states.drop(2).first())
        }
    }

    @InternalCoroutinesApi
    @Test
    fun increment_decrement_count() {
        val initialState = DishFeature.State(
            id = "test",
            title = "test",
            reviews = ReviewUiState.Value(Stubs.comments),
            content = DishUiState.Value(Stubs.dishContent)
        )

        val feature = TestFeature<DishFeature.State, DishFeature.Msg, DishFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishFeature.Msg.IncrementCount)
            feature.accept(DishFeature.Msg.DecrementCount)
            feature.accept(DishFeature.Msg.DecrementCount)
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(count = 2), states.drop(1).first())
            assertEquals(initialState.copy(count = 1), states.drop(2).first())
            assertEquals(initialState.copy(count = 1), states.last())
        }
    }

    @InternalCoroutinesApi
    @Test
    fun show_hide_dialog() {
        val initialState = DishFeature.State(
            id = "test",
            title = "test",
            reviews = ReviewUiState.Value(Stubs.comments),
            content = DishUiState.Value(Stubs.dishContent)
        )

        val feature = TestFeature<DishFeature.State, DishFeature.Msg, DishFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishFeature.Msg.ShowReviewDialog)
            feature.accept(DishFeature.Msg.HideReviewDialog)
        }

        feature.test { states, _, _ ->
            assertEquals(initialState.copy(isReviewDialog = true), states.drop(1).first())
            assertEquals(initialState.copy(isReviewDialog = false), states.drop(2).first())
        }
    }

    @InternalCoroutinesApi
    @Test
    fun send_review() {
        val initialState = DishFeature.State(
            id = "test",
            title = "test",
            isReviewDialog = true,
            reviews = ReviewUiState.Value(Stubs.comments),
            content = DishUiState.Value(Stubs.dishContent)
        )
        val expectedComment = ReviewRes("test", 1618068000, 5, "test from me")

        coEvery { mockApi.getReviews(any(), 0, 10) } returns Response.success(Stubs.comments)
        coEvery { mockApi.getReviews(any(), 10, any()) } returns Response.error(
            400,
            "".toResponseBody()
        )
        coEvery { mockApi.sendReview(any(), any()) } returns expectedComment

        val feature = TestFeature<DishFeature.State, DishFeature.Msg, DishFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishFeature.Msg.SendReview("test", 5, "test from me"))
        }

        feature.test { states, _, _ ->
            assertEquals(
                initialState.copy(
                    isReviewDialog = false,
                    reviews = ReviewUiState.ValueWithLoading(Stubs.comments)
                ), states.drop(1).first()
            )
            assertEquals(
                initialState.copy(
                    isReviewDialog = false,
                    reviews = ReviewUiState.Value(Stubs.comments + expectedComment)
                ), states.drop(2).first()
            )
        }

        coVerify { mockApi.sendReview("test", ReviewReq(5, "test from me")) }
    }

    @InternalCoroutinesApi
    @Test
    fun add_to_cart() {
        val initialState = DishFeature.State(
            id = "test",
            title = "test",
            reviews = ReviewUiState.Value(Stubs.comments),
            content = DishUiState.Value(Stubs.dishContent),
            count = 8
        )

        coEvery { cartDao.dishCount("test") } returns 0
        coEvery { cartDao.addItem(any()) } coAnswers {}
        var callCount = -1

        coEvery { cartDao.cartCount() } coAnswers {
            callCount++
            if (callCount == 0) 8 else 6
        }
        val feature = TestFeature<DishFeature.State, DishFeature.Msg, DishFeature.Eff>(
            initialState.copy(),
            emptySet(),
            DishFeature.State::selfReduce,
        )

        TestCoroutineScope().launch {
            feature.listenLocal(this, DishEffHandler(repository, mockChannel))
        }

        runBlockingTest {
            feature.accept(DishFeature.Msg.AddToCart("test", 8))
            feature.accept(DishFeature.Msg.AddToCart("test", 6))
        }

        feature.test { states, msgs, _ ->
            assertEquals(
                listOf(
                    Msg.Dish(DishFeature.Msg.AddToCart("test", 8)),
                    Msg.UpdateCartCount(8),
                    Msg.Dish(DishFeature.Msg.AddToCart("test", 6)),
                    Msg.UpdateCartCount(6)
                ),
                msgs
            )
            assertEquals(initialState.copy(count = 1), states.last())
        }

        coVerify { mockChannel.send(Eff.Notification.Text("В корзину добавлено 8 товаров")) }
        coVerify { mockChannel.send(Eff.Notification.Text("В корзину добавлено 6 товаров")) }
    }
}