package com.sentrysmp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommandRequest(val command: String)

@Serializable
data class CommandResponse(val output: List<String>, val success: Boolean)

@Serializable
data class PlayerInfo(val name: String, val uuid: String)

@Serializable
data class PlayersResponse(val players: List<PlayerInfo>)

@Serializable
data class BanEntry(val name: String, val uuid: String, val reason: String?)

@Serializable
data class BanlistResponse(val banned: List<BanEntry>)

@Serializable
data class PlayerStatsResponse(
	val player: String,
	val coins: Long? = null,
	val money: Double? = null,
	val rank: String? = null,
	val error: String? = null
)

@Serializable
data class ScoreEntry(
	val rank: Int,
	@SerialName("minecraftUsername") val minecraftUsername: String,
	@SerialName("totalPaid") val totalPaid: Double,
	@SerialName("transactionCount") val transactionCount: Int,
	@SerialName("lastPayment") val lastPayment: String? = null
)

@Serializable
data class ScoreboardResponse(
	val entries: List<ScoreEntry> = emptyList(),
	val period: String? = null
)
