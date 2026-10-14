// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: apps that go straight to the internet, not through the tunnel.

package com.csqtt.client

/**
 * Banks and government apps often refuse to work when the phone is on a VPN, so with the
 * "банки напрямую" switch on they bypass Mayak. Sber (ru.sberbankmobile) is deliberately not
 * here: it goes through Mayak. Names of apps that are not installed are simply skipped.
 */
internal object MayakDirectApps {
    val BANKS_AND_GOV: Set<String> = setOf(
        // Banks
        "com.idamob.tinkoff.android",          // Т-Банк
        "ru.vtb24.mobilebanking.android",      // ВТБ
        "ru.alfabank.mobile.android",          // Альфа-Банк
        "ru.gazprombank.android.mobilebank.app", // Газпромбанк
        "ru.raiffeisennews",                   // Райффайзен
        "com.openbank",                        // Открытие
        "ru.rshb.dbo",                         // Россельхозбанк
        "ru.mts.money",                        // МТС Банк
        "ru.sovcomcard.halva.v1",              // Совкомбанк Халва
        "ru.letobank.Prometheus",              // Почта Банк
        "ru.yoo.money",                        // ЮMoney
        "ru.nspk.mirpay",                      // Mir Pay
        "ru.nspk.sbpay",                       // СБПэй
        // Government
        "ru.rostel",                           // Госуслуги
        "ru.gosuslugi.culture",                // Госуслуги Культура
        "ru.fns.lkfl",                         // Налоги ФЛ
        // Marketplaces that check the network the same way
        "ru.ozon.app.android",                 // Ozon
        "com.wildberries.ru",                  // Wildberries
    )
}
