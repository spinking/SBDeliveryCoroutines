package ru.skillbranch.sbdelivery.repository

import ru.skillbranch.sbdelivery.data.db.dao.CartDao
import javax.inject.Inject

interface IRootRepository {
    suspend fun cartCount(): Int
}

class RootRepository @Inject constructor(
    private val cartDao: CartDao
) : IRootRepository{
    override suspend fun cartCount(): Int = cartDao.cartCount() ?: 0
}