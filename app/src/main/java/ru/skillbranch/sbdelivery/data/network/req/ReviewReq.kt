package ru.skillbranch.sbdelivery.data.network.req

import java.io.Serializable

data class ReviewReq (val rating: Int, val text: String ): Serializable