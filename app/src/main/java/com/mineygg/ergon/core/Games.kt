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
    val keepAliveCode: String
)

data class Account(val uid: String, val region: String, val nickname: String)

enum class Game(
    val id: String,
    val title: String,
    val color: Long,
    val checkin: CheckinCfg,
    val redeem: RedeemCfg?
) {
    GENSHIN(
        "genshin",
        "Genshin Impact",
        0xFFF2C46D,
        CheckinCfg("https://sg-hk4e-api.hoyolab.com/event/sol/sign", "e202102251931481"),
        RedeemCfg(
            "https://game8.co/games/Genshin-Impact/archives/304759",
            "https://public-operation-hk4e.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "hk4e_global",
            "GENSHINGIFT"
        )
    ),
    HSR(
        "hsr",
        "Honkai: Star Rail",
        0xFFB49CFF,
        CheckinCfg("https://sg-public-api.hoyolab.com/event/luna/os/sign", "e202303301540311"),
        RedeemCfg(
            "https://game8.co/games/Honkai-Star-Rail/archives/410296",
            "https://public-operation-hkrpg.hoyoverse.com/common/apicdkey/api/webExchangeCdkey",
            "hkrpg_global",
            "STARRAILGIFT"
        )
    ),
    ZZZ(
        "zzz",
        "Zenless Zone Zero",
        0xFFFFD84D,
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
            "ZENLESSGIFT"
        )
    ),
    HI3(
        "hi3",
        "Honkai Impact 3rd",
        0xFF6EC1FF,
        CheckinCfg("https://sg-public-api.hoyolab.com/event/mani/sign", "e202110291205111"),
        null
    ),
    TOT(
        "tot",
        "Tears of Themis",
        0xFFFF8FB1,
        CheckinCfg(
            "https://sg-public-api.hoyolab.com/event/luna/nxx/os/sign",
            "e202202281857121",
            signGame = "nxx"
        ),
        null
    )
}
