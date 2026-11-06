package main

import (
	"fmt"
	"strings"
)

// XMPP Stream Management (XEP-0198): сервер периодически шлёт <r/> и ждёт
// <a h="N"/> с числом обработанных станз. Без ответа Prosody через ~минуту
// рвёт веб-сокет (close 1006), Jicofo выкидывает участника, и мост закрывает
// наш медиа-канал. smTrack вызывается ТОЛЬКО из читающей горутины, поэтому
// h считается точно на момент прихода запроса.
var (
	smOn    bool
	smCount uint32
)

func smTrack(s string) string {
	if !smOn {
		if strings.Contains(s, "<enabled") {
			smOn = true
		}
		return s
	}
	switch {
	case strings.HasPrefix(s, "<r ") && strings.Contains(s, "urn:xmpp:sm:3"):
		return fmt.Sprintf(`<r xmlns="urn:xmpp:sm:3" buhta-h="%d"/>`, smCount)
	case strings.HasPrefix(s, "<iq"), strings.HasPrefix(s, "<message"), strings.HasPrefix(s, "<presence"):
		smCount++
	}
	return s
}

// smAnswer возвращает ответ <a/> для запроса <r/> или "".
func smAnswer(frame string) string {
	if !strings.HasPrefix(frame, "<r ") {
		return ""
	}
	i := strings.Index(frame, `buhta-h="`)
	if i < 0 {
		return ""
	}
	rest := frame[i+len(`buhta-h="`):]
	j := strings.Index(rest, `"`)
	if j < 0 {
		return ""
	}
	return fmt.Sprintf(`<a xmlns="urn:xmpp:sm:3" h="%s"/>`, rest[:j])
}
