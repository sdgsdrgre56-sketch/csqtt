# Бухта — сигналинг-джойнер Saby Meet (эксперимент)

Headless-клиент XMPP-over-WebSocket, повторяющий гостевой вход в Saby Meet (meet.saby.ru),
снятый с живого HAR 2026-11-04: ANONYMOUS SASL -> bind/session -> stream management ->
extdisco (TURN) -> focus conference -> MUC presence -> ждёт Jingle session-initiate от
видеомоста и печатает его целиком (кандидаты, colibri websocket).

Это только сигналинг-проба, НЕ медиа-туннель: session-accept, ICE/DTLS/SRTP не реализованы.
Следующий шаг — перенос схемы DCTunnel/MultiTrackTunnel из whitelist-bypass поверх этого
сигналинга, после проверки вживую.

Запуск:

    go run . -room <UUID из гостевой ссылки> -name "Бухта"

Ничего в main/app/dion не трогает — отдельная ветка, отдельная папка.
