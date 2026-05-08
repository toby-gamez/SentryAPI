package com.sentrysmp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class CommandRequest(
	val commands: List<String>,
	val price: Double,
	val cart: List<CartItem>,
	val playerName: String? = null,
	val voucher: String? = null
)

@Serializable
data class CommandResponse(val output: List<String>, val success: Boolean)

@Serializable
data class PlayerInfo(val name: String, val uuid: String, val rank: String? = null)

@Serializable
data class CartServer(
	@kotlinx.serialization.SerialName("Id") val id: Int,
	@kotlinx.serialization.SerialName("Name") val name: String,
	@kotlinx.serialization.SerialName("RCONIP") val rconIP: String,
	@kotlinx.serialization.SerialName("RCONPort") val rconPort: Int,
	@kotlinx.serialization.SerialName("RCONPassword") val rconPassword: String
)

@Serializable
data class CartKey(
	@kotlinx.serialization.SerialName("Id") val id: Int,
	@kotlinx.serialization.SerialName("Name") val name: String,
	@kotlinx.serialization.SerialName("Description") val description: String? = null,
	@kotlinx.serialization.SerialName("Price") val price: Double,
	@kotlinx.serialization.SerialName("Sale") val sale: Double? = null,
	@kotlinx.serialization.SerialName("Image") val image: String? = null,
	@kotlinx.serialization.SerialName("GlobalMaxOrder") val globalMaxOrder: Int? = null,
	@kotlinx.serialization.SerialName("Server") val server: CartServer? = null,
	@kotlinx.serialization.SerialName("Type") val type: String? = null
)

private val CART_ITEM_TYPES = setOf("Key", "Coin", "Other", "Rank", "Battlepass")

object CartItemSerializer : KSerializer<CartItem> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CartItem")

	override fun serialize(encoder: Encoder, value: CartItem) {
		val jsonEncoder = encoder as? JsonEncoder
			?: throw kotlinx.serialization.SerializationException("Only JSON supported")
		val itemJson = jsonEncoder.json.encodeToJsonElement(CartKey.serializer(), value.item)
		jsonEncoder.encodeJsonElement(buildJsonObject {
			put("Key", itemJson)
			put("Quantity", value.quantity)
		})
	}

	override fun deserialize(decoder: Decoder): CartItem {
		val jsonDecoder = decoder as? JsonDecoder
			?: throw kotlinx.serialization.SerializationException("Only JSON supported")
		val obj = jsonDecoder.decodeJsonElement().jsonObject
		val typeKey = CART_ITEM_TYPES.firstOrNull { obj.containsKey(it) }
			?: throw kotlinx.serialization.SerializationException("CartItem must contain one of: $CART_ITEM_TYPES")
		val cartKey = jsonDecoder.json.decodeFromJsonElement(CartKey.serializer(), obj[typeKey]!!)
		val quantity = obj["Quantity"]!!.jsonPrimitive.int
		return CartItem(cartKey, quantity)
	}
}

@Serializable(with = CartItemSerializer::class)
data class CartItem(
	val item: CartKey,
	val quantity: Int
)

@Serializable
data class VoucherResponse(
	val id: Int,
	val code: String,
	val description: String? = null,
	val startDate: String? = null,
	val expirationDate: String? = null,
	val maxUses: Int? = null,
	val currentUses: Int? = null,
	val discountPercent: Double = 0.0,
	val scope: String? = null,
	val scopeCategory: String? = null,
	val scopeItemId: Int? = null,
	val isActive: Boolean = false
)

@Serializable
data class ValidationErrorResponse(
	val error: String,
	val minAllowedPrice: Double,
	val cartTotal: Double,
	val discountPercent: Double
)

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
	val statistics: PlayerStatistics? = null,
	val error: String? = null
)

@Serializable
data class PlayerStatistics(
    val playTimeSeconds: Long? = null,
    val playTimeTicks: Long? = null,
    val deaths: Long? = null,
    val playerKills: Long? = null,
    val mobsKilled: Long? = null,
	val blocksTravelled: Long? = null
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
