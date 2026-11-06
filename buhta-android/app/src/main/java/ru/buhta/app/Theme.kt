package ru.buhta.app

import android.graphics.Color

/** Скины: набор палитр, у каждой есть светлый и тёмный вариант. */
class Skin(
    val bg: Int, val surface: Int, val primary: Int, val onPrimary: Int,
    val text: Int, val sub: Int, val outline: Int,
)

object Themes {
    val names = linkedMapOf(
        "indigo" to "Индиго",
        "coffee" to "Кофе",
        "emerald" to "Изумруд",
        "graphite" to "Графит",
    )

    private fun c(s: String) = Color.parseColor(s)

    fun get(key: String, dark: Boolean): Skin = when (key) {
        "coffee" -> if (dark)
            Skin(c("#1A1614"), c("#2C2624"), c("#D7CCC8"), c("#3E2723"), c("#EDE0D4"), c("#BCAAA4"), c("#4E342E"))
        else
            Skin(c("#F2F0EC"), c("#FAF8F4"), c("#6D4C41"), c("#FFFFFF"), c("#1C1B1A"), c("#5D4037"), c("#D7CCC8"))
        "emerald" -> if (dark)
            Skin(c("#0F1A17"), c("#182823"), c("#7FD6B5"), c("#00382A"), c("#DCEBE5"), c("#9DBFB2"), c("#2B4A40"))
        else
            Skin(c("#F1F7F4"), c("#FBFEFC"), c("#1F7A5C"), c("#FFFFFF"), c("#13201B"), c("#44635A"), c("#C5DDD3"))
        "graphite" -> if (dark)
            Skin(c("#121212"), c("#1E1E1E"), c("#E0E0E0"), c("#1A1A1A"), c("#EEEEEE"), c("#A0A0A0"), c("#383838"))
        else
            Skin(c("#EEEEEE"), c("#FAFAFA"), c("#37474F"), c("#FFFFFF"), c("#1B1B1B"), c("#616161"), c("#CFCFCF"))
        else -> if (dark)
            Skin(c("#141318"), c("#201F26"), c("#C4C0FF"), c("#2D2A5B"), c("#E5E1E9"), c("#C8C5D0"), c("#47464F"))
        else
            Skin(c("#FBF8FF"), c("#F0EDF5"), c("#5B588D"), c("#FFFFFF"), c("#1B1B1F"), c("#47464F"), c("#C8C5D0"))
    }
}
