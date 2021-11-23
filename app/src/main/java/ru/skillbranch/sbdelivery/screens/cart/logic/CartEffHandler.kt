package ru.skillbranch.sbdelivery.screens.cart.logic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import ru.skillbranch.sbdelivery.repository.CartRepository
import ru.skillbranch.sbdelivery.screens.root.logic.Eff
import ru.skillbranch.sbdelivery.screens.root.logic.IEffHandler
import ru.skillbranch.sbdelivery.screens.root.logic.Msg
import javax.inject.Inject

class CartEffHandler @Inject constructor(
    private val repository: CartRepository,
    private val notifyChanel: Channel<Eff.Notification>
) : IEffHandler<CartFeature.Eff, Msg> {
    override suspend fun handle(effect: CartFeature.Eff, commit: (Msg) -> Unit) {

        suspend fun updateCart(){
            val items = repository.loadItems()
            val count = items.sumBy { it.count }
            commit(Msg.UpdateCartCount(count))
            commit(CartFeature.Msg.ShowCart(items).toMsg())
        }

        when(effect){
            is CartFeature.Eff.DecrementItem -> {
                repository.decrementItem(effect.dishId)
                updateCart()
            }
            is CartFeature.Eff.IncrementItem -> {
                repository.incrementItem(effect.dishId)
                updateCart()
            }
            is CartFeature.Eff.LoadCart -> {
                val items = repository.loadItems()
                commit(CartFeature.Msg.ShowCart(items).toMsg())
            }
            is CartFeature.Eff.RemoveItem -> TODO()
            is CartFeature.Eff.SendOrder -> TODO()
        }
    }

    private fun CartFeature.Msg.toMsg(): Msg = Msg.Cart(this)
}