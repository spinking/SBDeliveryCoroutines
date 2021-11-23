package ru.skillbranch.sbdelivery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.skillbranch.sbdelivery.screens.cart.logic.CartFeature
import ru.skillbranch.sbdelivery.screens.dish.logic.DishFeature
import ru.skillbranch.sbdelivery.screens.dishes.logic.DishesFeature
import ru.skillbranch.sbdelivery.screens.root.logic.Eff
import ru.skillbranch.sbdelivery.screens.root.logic.IEffectHandler
import ru.skillbranch.sbdelivery.screens.root.logic.Msg
import java.lang.IllegalStateException

@ExperimentalCoroutinesApi
class TestFeature<S, M, E>(
    val initialState: S,
    val initialCommands: Set<Eff>,
    val reducer: (S, M) -> Pair<S, Set<Eff>>,
) {
    private lateinit var _scope: CoroutineScope

    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    private val stateStack: MutableList<S> = mutableListOf()
    private val effStack: MutableList<Eff> = mutableListOf()
    private val msgStack: MutableList<Msg> = mutableListOf()
    private lateinit var _job: Job

    private val mutations: MutableSharedFlow<Msg> = MutableSharedFlow(replay = 100, extraBufferCapacity = 100)

    fun listenLocal(
        scope: CoroutineScope,
        handler: IEffectHandler<E, Msg>
    ) {
        _scope = scope
        _job = _scope.launch(Dispatchers.Main) {
            mutations
                .onEach { msgStack.add(it) }
                .mapNotNull { filterTargetMsg(it) }
                .scan(initialState to initialCommands) { (s, _), m ->
                    reducer(s, m)
                }
                .collect { (s, es) ->
                    stateStack.add(s)
                    _state.emit(s)
                    effStack.addAll(es.toList())
                    launch  {
                        es.mapNotNull(::filterTargetEff)
                            .forEach {
                                handler.handle(it, ::mutate)
                            }
                    }
                }


        }
    }

    fun mutate(mutation: Msg) {
        _scope.launch(Dispatchers.Main) {
            mutations.emit(mutation)
        }
    }

    fun accept(mutation: M) {
        _scope.launch(Dispatchers.Main) {
            mutations.emit(transform(mutation))
        }
    }

    fun test(
        timeout: Long = 1000,
        assertion: (states: List<S>, msgs: List<Msg>, effs: List<Eff>) -> Unit
    ) {
        runBlocking {
            delay(timeout)
            _job.cancel()
            assertion(stateStack, msgStack, effStack)
        }
    }

    private fun transform(msg: M): Msg {
        return when (msg) {
            is DishesFeature.Msg -> Msg.Dishes(msg)
            is DishFeature.Msg -> Msg.Dish(msg)
            is CartFeature.Msg -> Msg.Cart(msg)
            else -> throw IllegalStateException("$msg must be Msg.Dishes | Msg.Dish | Msg.Cart")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterTargetMsg(msg: Msg): M? {
        return when (msg) {
            is Msg.Dishes -> msg.msg as M
            is Msg.Dish -> msg.msg as M
            is Msg.Cart -> msg.msg as M
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterTargetEff(eff: Eff): E? {
        return when (eff) {
            is Eff.Dishes -> eff.eff as E
            is Eff.Dish -> eff.eff as E
            is Eff.Cart -> eff.eff as E
            else -> null
        }
    }

}
