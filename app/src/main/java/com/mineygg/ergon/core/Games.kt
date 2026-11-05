package com.mineygg.ergon.core

data class CheckinCfg(
    val url: String,
    val actId: String,
    val useCookieToken: Boolean = false,
    val signGame: String? = null
)

data class RedeemCfg(
    val scrapeUrl: String,
    val redeemUrl: String,
    val gameBiz: String,
    val keepAliveCode: String,
    /** The game's name in the hoyo-codes API. */
    val seriaGame: String
)

data class Account(val uid: String, val region: String, val nickname: String)

/** Server names as the games show them, by the region code the HoYoLAB profile returns. */
private val REGION_NAMES = mapOf(
    "os_usa" to "America",
    "os_euro" to "Europe",
    "os_asia" to "Asia",
    "os_cht" to "TW, HK, MO",
    "prod_official_usa" to "America",
    "prod_official_eur" to "Europe",
    "prod_official_asia" to "Asia",
    "prod_official_cht" to "TW, HK, MO",
    "prod_gf_us" to "America",
    "prod_gf_eu" to "Europe",
    "prod_gf_jp" to "Asia",
    "prod_gf_sg" to "TW, HK, MO"
)

/** Readable name of a region code. A code that isn't known is shown as it is. */
fun regionName(region: String): String = REGION_NAMES[region] ?: region

enum class Game(
    val id: String,
    val title: String,
    val color: Long,
    /** game_biz of the HoYoLAB profile (getUserGameRolesByLtoken), which lists the accounts the user has. */
    val biz: String,
    val checkin: CheckinCfg,
    val redeem: RedeemCfg?
) {
    GENSHIN(
        "genshin",
        "Genshin Impact",
        0xFFF2C46D,
        "hk4e_global",
        CheckinCfg("https://sg-hk4e-api.hoyolab.com/event/sol/sign", "e202102251931481"),
        RedeemCfg(
            "https://game8.co/games/Genshin-Impact/archives/304759",
            "https://public-operation-hk4e.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "hk4e_global",
            "GENSHINGIFT",
            "genshin"
        )
    ),
    HSR(
        "hsr",
        "Honkai: Star Rail",
        0xFFB49CFF,
        "hkrpg_global",
        CheckinCfg("https://sg-public-api.hoyolab.com/event/luna/os/sign", "e202303301540311"),
        RedeemCfg(
            "https://game8.co/games/Honkai-Star-Rail/archives/410296",
            "https://public-operation-hkrpg.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "hkrpg_global",
            "STARRAILGIFT",
            "hkrpg"
        )
    ),
    ZZZ(
        "zzz",
        "Zenless Zone Zero",
        0xFFFFD84D,
        "nap_global",
        CheckinCfg(
            "https://sg-public-api.hoyolab.com/event/luna/zzz/os/sign",
            "e202406031448091",
            useCookieToken = true,
            signGame = "zzz"
        ),
        RedeemCfg(
            "https://game8.co/games/Zenless-Zone-Zero/archives/435683",
            "https://public-operation-nap.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "nap_global",
            "ZENLESSGIFT",
            "nap"
        )
    ),
    HI3(
        "hi3",
        "Honkai Impact 3rd",
        0xFF6EC1FF,
        "bh3_global",
        CheckinCfg("https://sg-public-api.hoyolab.com/event/mani/sign", "e202110291205111"),
        null
    ),
    TOT(
        "tot",
        "Tears of Themis",
        0xFFFF8FB1,
        "nxx_global",
        CheckinCfg(
            "https://sg-public-api.hoyolab.com/event/luna/nxx/os/sign",
            "e202202281857121",
            signGame = "nxx"
        ),
        null
    )
}
