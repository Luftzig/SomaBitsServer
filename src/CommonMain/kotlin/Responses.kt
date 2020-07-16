package se.kth.somabits.common

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(val status: String, val message: String?)