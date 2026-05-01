package com.sentrysmp

import kotlinx.serialization.Serializable

@Serializable
data class CommandRequest(val command: String)

@Serializable
data class CommandResponse(val output: List<String>, val success: Boolean)

@Serializable
data class PlayerInfo(val name: String, val uuid: String)

@Serializable
data class PlayersResponse(val players: List<PlayerInfo>)
