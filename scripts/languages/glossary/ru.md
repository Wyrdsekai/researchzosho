# Russian (ru) glossary for ResearchZosho's interface

## Address and style

- The reader is «вы», lower case. Instructions are the polite imperative: «Выберите файл», «Нажмите «Найти»».
- Buttons, menu items, links that do something: the infinitive — «Сохранить», «Удалить», «Найти», «Добавить файл».
- Tabs, headings, column heads: a noun — «Источники», «Модель», «Входящие».
- Quotation marks « »; inside them „ “. The long dash — with spaces. The ellipsis … kept where the English has it.
- ё only where its absence misleads («всё» = everything, against «все» = everyone/all of them); otherwise е («отчет», «еще»).
- Numbers with units: «10 с», «5 мин», «1,5 доллара»; the program fills the numbers.
- Seconds glued after a number (" s"): « с».

## Counts (rule 4)

The program has one text for exactly 1 and one for every other number. Russian has three forms (1 файл / 2 файла / 5 файлов, and 21 файл),
so the "every other number" text is never "{0} файлов". The pattern used:

- **Genitive plural noun, colon, number**: «Утверждений: {0}», «Источников: {0}», «Вопросов: {0}».
- Inside a sentence, the same thing as an inserted count: «… (утверждений: {0})», or a noun that does not decline with the number
  («{0} шт.» is not used), or a reworded sentence where the number stands after a noun in the nominative («число вопросов — {0}»).
- Minutes, days, seconds: «мин», «дн.», «с», «ч» abbreviations, which hold for every number («{0} мин», «каждые {0} дн.»).
- The "exactly 1" text uses the ordinary singular: «1 утверждение», «{0} утверждение».

## Placeholders, case and gender

- A filled-in name stays in the nominative, in « », after a noun that carries the case: «в теме «{0}»», «модель «{0}»», «подкаст «{0}»».
  Ids, addresses, dates and numbers are not put in « ».
- No past-tense verb agrees with a filled-in name: passive or impersonal forms instead («добавлено», «готово», «запущено»).
- A language name fills in lower case and in the nominative («японский»): «на {0}» (accusative = nominative for these),
  «язык: {0}», «в переводе на {0}».

## Recurring terms (one word each)

| English | Russian |
|---|---|
| library | библиотека |
| the Librarian | Библиотекарь |
| subject | тема |
| shelf / the shelves | полка / полки («на полках», «попадает на полки») |
| report | отчет |
| question / open question | вопрос / открытый вопрос |
| source | источник |
| claim | утверждение |
| research run, run | исследование (the Runs page: «Исследования») |
| research (verb) | исследовать |
| job (a task the library runs) | задание |
| search | поиск |
| saved search (serial) | сохраненный поиск |
| seek (one of several runs of one question) | заход |
| field | область |
| goal | цель |
| dossier | досье |
| bridge | мост |
| frontier model | передовая модель |
| bookmark | закладка |
| inbox (the page) | «Входящие» («во «Входящих»») |
| judge seat, judge | модель-судья («проверяет модель-судья библиотеки») |
| drive (the model server a run uses) | сервер модели |
| entry | запись |
| tier (translation) | уровень |
| glossary | глоссарий |
| note beside the translation | заметка к переводу |
| notes at the end of the document | примечания |
| check / the checks | проверка / проверки |
| unit (of a translation) | фрагмент |
| pass (of a translation) | проход |
| accept / dispute / retire / drop | принять / оспорить / отозвать / убрать |
| file (a report, a claim) | сохранить («Сохранено как черновик») |
| draft | черновик |
| housekeeping, nightly maintenance | ночное обслуживание |
| tally | учет расходов |
| Keep (the keeper's page) | «Управление» |
| the person who keeps the library, keeper | администратор библиотеки |
| reader / writer (access) | читатель / тот, у кого есть право записи |
| access levels deny / read / write | нет доступа / только чтение / чтение и запись |
| key / passkey / token | ключ / ключ доступа / токен |
| model / model server | модель / сервер модели |
| host / hosted | хост / облачный |
| cap (a month) | лимит |
| allowance | бесплатная квота |
| conversation | разговор |
| session | сеанс |
| chat | чат |
| terminal / command line | терминал / командная строка |
| listing (home) | объявление |
| opening (job) | вакансия |
| employer | работодатель |
| patent family | патентное семейство |
| pool (of patents) | выборка |
| candidate | кандидат |
| frame / storyboard sheet / thumbnail | кадр / лист раскадровки / миниатюра |
| transcript | расшифровка |
| show (podcast) / episode / feed | подкаст / выпуск / лента |
| follow | подписаться, подписка |
| forecast | прогноз |
| story | история |
| perspectives | точки зрения |
| established | установлено |
| reaction | реакция |
| preferences file | файл предпочтений |
| DNA | ДНК |
| collection | подборка |
| index (search index) | поисковый индекс |
| Settings / Help / Home (the start page) | Настройки / Справка / Главная |
| Home search / Career | Поиск жилья / Поиск работы |
| Past (the page) | Архив |
| Owned (the page) / what you own | Мои вещи / ваши вещи |
| Changes / change log | Изменения / журнал изменений |
| Research (nav item, button) / Runs (page) | Исследовать / Исследования |
| Connect a model (the connect page) | Подключить модель |
| Ways in / Work / Yours (nav groups) | Другие пути / Дела / Ваше |
| Search (button and page) / Search now | Поиск / Искать сейчас |
| Read in (one key: the language line on a report and the read-in page) | Чтение |
| reading (the library's reading of a person's working life) | разбор |
| topic (a row of "what you want") | пункт |
| decided / a guess / open | решено / догадка / открыто |
| area (bridges) | раздел |
| suggestion | совет |
| refine a question | уточнить |
| relay (Tailscale, Cloudflare) | ретранслятор |
| owner | владелец |
| patent claims / abstract | формула изобретения / реферат |
| put away (a recall notice) | скрыть |
| park / unpark, resume | отложить / вернуть, возобновить |
| revoke (a key) | аннулировать |
| retracted (a paper) / recall notice | отозвана / уведомление об отзыве |
| ledger (purchase records) | журнал покупок |
| summary / "article" kind | сводка |
| raw (a saved page) | сохраненная страница |
| snippet | отрывок |
| reading level: Simple / Familiar / Original | уровень сложности: Простыми словами / Для знающих / Оригинал |
| report states kept / waiting / disputed / retired | принято / во «Входящих» / оспорено / отозвано |
| source states active / dry / doubtful / off | активен / пересох / сомнителен / отключен |
| forecast outcomes happened / did not / void | случилось / не случилось / аннулировано |
| confidence high / medium / low | высокая / средняя / низкая |
| look: system / light / dark | оформление: системное / светлое / темное |
| id | ID (kept as id inside typed syntax such as <id>) |

## Counts as written (rule 4)

One text / every other number:
«{0} утверждение» / «утверждений: {0}»; «{0} отчет» / «отчетов: {0}»; «{0} источник» / «источников: {0}»;
«{0} открытый вопрос» / «открытых вопросов: {0}»; «{0} работодатель» / «работодателей: {0}»; «{0} ключ» / «ключей: {0}»;
«{0} объявление» / «объявлений: {0}»; «{0} карточка» / «карточек: {0}»; «{0} факт» / «фактов: {0}»; «{0} сервер» / «серверов: {0}»;
«{0} инструмент» / «инструментов: {0}»; «{0} изображение» / «изображений: {0}»; «{0} похожий вопрос» / «похожих вопросов: {0}»;
«{0} новое событие» / «новых событий: {0}»; «в списке {0} человек» / «в списке людей: {0}»; «{0} час» / «{0} ч»;
«прочитано {0} видео» for both (видео does not decline).
Where the program puts a count inside another sentence, the outer sentence uses a dash, so the colon is not doubled:
«С тех пор у вас на проверке — {0}.», «С вашего последнего просмотра — {0}.», «Просмотрено — {0}; {1}; прочитано — {2}.»
For "{0} time(s)" and "{0} wait(s)" the noun sits in the outer sentence, so those texts are the bare «{0}».
Bare numbers in a sentence: «(всего {0})», «принято — {0}», «{0} мин», «раз в {0} дн.», «{0} симв.».

## What a person types in the chat (rule 6)

| English | Russian, as typed |
|---|---|
| translate this file into Japanese | переведи этот файл на японский |
| this file | этот файл |
| quick | быстро |
| checked | с проверкой |
| reviewed | с вычиткой |
| thorough | тщательно |
| with the notes at the end | с примечаниями в конце |
| no notes | без примечаний |
| yes / no | да / нет |
| find out … | узнай … |

The phrases that name a model ("using deepseek", "translated by kimi", "checked by kimi", "have kimi k3 do a thorough check",
"checked by the strongest other model") are read in English only; they stay in English, in "straight quotes", and the text says so
(«по-английски»).

## Second round

- Whole sentences replace the glued pieces. The bare «{0}» texts for "{0} time(s)" and "{0} wait(s)" are gone: each sentence now
  carries its own count. One text: «обратились к нему {0} раз», «{0} утверждение ждет…»; every other number: «Обращений … : {0}»,
  «У вас на проверке утверждений: {0}», «(всего {0})», «… — на {0} из {1}». Durations: «около {0} минуты» / «около {0} мин»,
  «около {0} часа» / «около {0} ч», «около {0} ч {1} мин» for the rest; «меньше минуты».
- Field names inside «режим «…»» (nominative, lower case): поиск работы, подкасты, организация, наука, человек, генеалогия, видео,
  книги, программы, покупки, патенты, право, поиск жилья, ремесла и увлечения.
- The split words: Keep (save button) «Сохранить»; Reading (career column) «Разбор»; Read (career column) «Прочитано», Read (page
  of one saved page) «Чтение»; Read in (the language line on a report) «Язык чтения:», Read in (the page that reads material in)
  «Импорт материалов»; Ask (request button) «Попросить доступ»; deny / set (reader row buttons) «закрыть доступ» / «задать».
- Language names standing alone (list, switch): capitalized, «Японский». Inside a sentence the words file's first entry, «японский».
- Translation estimate: «{0} на уровне «с проверкой»»; the reader / translator of a translation: «проверяющий» / «переводчик».
- The run's stage inside a sentence: «чтение, готово частей — {0} из {1}», «перевод, фрагмент {0} из {1}» (dashes and commas, so the
  sentence it lands in has no second colon).
- Restarts: «после перезапуска», «после обновления (до {0})», «после аварийной остановки службы (сбой или выключение компьютера)»;
  where it picked up: «с места: {1}».
- Pictures: the model that sees = «видящая модель»; likeness helper = «помощник сходства»; reference picture = «образец»; unsure =
  «под вопросом — {0}»; search place (Exa, Tavily) = «поисковый сервис «{0}»»; model tier = «класс {0}»; reasoning effort =
  «глубина рассуждений»; passkeys in a list = «ключи доступа ({0})».
- Typed in the chat, added: «сделай {0} моделью по умолчанию», «сохрани все», «сохрани 1, 3», «На каких из них …?»,
  «найди еще фото этого человека», «найди похожие места», «что это?», «переводи Acme Corp на японский как アクメ社», yes «д»;
  "in genealogy mode" stays in English. The words file lists е and ё spellings both, since the chat compares them as different letters.

## Third round (the pages that were still English)

- The class of a claim's source: тип источника. Its badges: официальный источник / собственная публикация / рассказано / предоставлено / третьи лица / канон.
- Dossier sections: Reading = Разбор, Told = Рассказанное, Pattern of life = Картина жизни.
- statements = высказывания; conjectures = предположения; superseded = заменено; watch = следить / «Под наблюдением»; search by meaning = поиск по смыслу; tilt = перевес; Families (the page of family names) = Фамилии.
- Wikipedia stays in Latin letters, like the other service names.

## Fourth round (the account service's pages, 0.8.0)

| English | Russian |
|---|---|
| account service (several accounts, each with a library, on one ResearchZosho) | сервис учетных записей («сервис», not «служба», which is the background service); "runs under an account service" = работает под управлением сервиса учетных записей |
| whoever runs this service (the operator, not a library's keeper) | администратор сервиса, always with «сервиса»; the keeper stays «администратор библиотеки» |
| join link | ссылка-приглашение |
| let someone in / is let in (the keeper's act on the account page) | дать доступ («Дать доступ», «получает доступ»); "{0} is let into {1}" = «{0} теперь может открывать библиотеку «{1}»» (the name stays in the nominative) |
| the people of a library | участники («Участники библиотеки «{0}»») |
| You keep it / You read it and write to it / You read it | Вы ее администратор. / Вам доступны чтение и запись. / Вам доступно только чтение. |
| What they may do + read / read and write (label and its choices) | «Доступ этого человека» + только чтение / чтение и запись (a noun label, since the choices are nouns) |
| a name the sentence would decline (An account is made for {0}; Send {0} this link) | the name first or after a colon: «Создана учетная запись: {0}.», «{0}: отправьте этому человеку ссылку.» |
| counts | «{0} мин», «{0} дн.»; browsers: «в одном браузере» / «Браузеров, в которых выполнен вход …: {0}» |
| closed (an account) | закрыта |
| the service it runs under / the model does not answer | сервис, под управлением которого она работает / Модель не отвечает |
| has to look into it (the operator, when the service's model does not answer) / Research needs it. / This needs it. | Разобраться должен администратор сервиса. / Для исследования нужна модель. / Для этого нужна модель. |
| a library's copy / brought back from its copy / being moved / no longer served here / another process holds its folder | копия / восстановлена из своей копии / переносится / здесь больше не доступна / ее папку занимает другой процесс; a moment ({0}) in brackets after «время копии:» or «время:» |
| a machine of the service / the machine a library runs on / is kept on | компьютер / компьютер, на котором работает / хранится библиотека «{0}»; minutes: «{1} мин» |
| an account's caps: the month's dollars / fetched pages / jobs at once; spending; a container | месячный лимит ({0}) / загруженные страницы / задания одновременно; расходы; контейнер. Money («$5.00») and the date («2026-12-01») stay as written, in brackets: «в начале следующего месяца ({1})» |
| work the service let start (a run, a chat turn, a translation) / searches this month / the model is busy | задание / поисковые запросы / Модель сервиса сейчас занята; the day ({1} = «1 дек.») in brackets: «в начале следующего месяца ({1})» |
| storage cap ({0} = «2.0 GB», as written) / removing something makes room / reading goes on | всё место, которое положено ({0}) / Если что-то удалить, место освободится / читать можно по-прежнему |
| own key / the operator's page (Every account) / cap / used / left / no cap / yours (own money) | собственный ключ / Все учетные записи / лимит / использовано / осталось / без лимита / за ваш счет |
| spending plug-in / the heavy helpers' time / pages fetched / a cap's names | плагин расходов / Время вспомогательных моделей / загруженные страницы / долларов в месяц, поисков в месяц, страниц в месяц, заданий одновременно, минут на задание, долларов на задание, мегабайт хранения |
| typed in the caps form | off, 5, 5.00, 5,00 and default stay as written: the program reads both the dot and the comma |
| set today / set yesterday / the fold of own keys / a run stopped at its cap | задан сегодня / задан вчера / Использовать собственные ключи (необязательно) / Остановлено: достигнут лимит |
| set on {0} (a day, never today or yesterday, which have keys of their own) | задан {0} (as задан сегодня / задан вчера) |
| suspend / delete (an account) / removal day / hand over (a library) / recent activity | приостановить / удалить / «дата удаления: {0}» / передать / Последние действия; the acts of the log: вход, пароль изменен, библиотека передана… |
| rate limits: "Try again in {0}." / a wait / Wait a moment | Попробуйте снова через {0}. / the wait is read only after «через»: one «{0} секунду», «{0} минуту» (accusative), every other number «{0} с», «{0} мин» / Подождите немного |
| take a library home (the link) / pack (for download) / the archive | Забрать к себе / упаковать / архив |
| a person lets a library go / the operator lets a library go (release to a folder) | отказаться / отпустить |
| kept aside / out of service / take back / take in / hand over / move / days of grace / freeze / suspend, let back | отложена / вне работы, выведена из работы / вернуть / принять / передать / перенести / льготный срок / заморозить / приостановить, вернуть доступ |
| the audit log / machine, node / awake, asleep / last word / … ago | журнал аудита / компьютер, узел / работает, спит / последний отклик / one «{0} минуту назад», many «{0} мин назад» (с, ч, дн.) |
| Release (the operator's act, was "let go"; a person's own "Let it go" stays отказаться) / Keep it ⟦…⟧ / For ⟦…⟧ / kept before by {0} | отпустить / Оставить / Для учетной записи / прежний администратор: {0} |
| invitation / Join ⟦…⟧ / Withdraw ⟦…⟧ / a link not taken yet | приглашение / Присоединиться / Отозвать / еще не использованная ссылка |

## Fifth round (0.8.1: the places a library avoids, the model server isolated from the network)

| English | Russian |
|---|---|
| place (a country code the setting names: CN, HK…) | страна или регион (pl. страны и регионы) |
| keep away from / avoid; the places this library avoids | избегать; страны и регионы, которых избегает эта библиотека; "in a place it avoids": в одной из стран или регионов, которых избегает эта библиотека (the relative refers to the plural) |
| a list of places the program fills in ({0} = «China, Hong Kong and Macao») | after a colon («в одной из этих стран или регионов: {0}»), never after в / из, which would want a case |
| based or runs in (a provider) | базироваться или работать в; short: страна или регион размещения |
| owned by a company based in a place; its owner | принадлежать компании из …; компания-владелец |
| states no place / left out / allow / serve (a model) | не указывает страну или регион / исключен, исключить / разрешить / предоставлять (обслуживать, when it serves the library) |
| providers counted ("{0} are left out…") | the dash form: «из-за страны или региона размещения исключено — {0}», «Поставщиков: {0}» |
| provider / router / request | поставщик / маршрутизатор / запрос |
| no way out to the network / can reach the network | изолирован от сети / есть выход в сеть |
| set up with no way out / able to reach the network / the earlier way | настроить изолированным от сети / с выходом в сеть / при прежней настройке |
| a reason the program fills in | after «Причина:», as a clause in small letters |
| embeddings server / embeddings model | сервер эмбеддингов / модель эмбеддингов |
| Docker network / container / proxy / route / address range / start script / log | сеть Docker / контейнер / прокси / маршрут / диапазон адресов / скрипт запуска / журнал |
| the library's own service (systemd, launchd) | служба (сервис is the account service) |
| open / closed (values of model_network) | stay as written: «настройка {0} имеет значение open» |
| an MCP server's answers give addresses | «Исследование может открывать адреса, которые он называет в своих ответах.» Buttons: Разрешить исследованию открывать найденные адреса / Не открывать адреса из ответов |
| the eight places of the unset list (CN HK MO CU IR KP RU VE), as the language ordinarily names them | Китай, Гонконг, Макао, Куба, Иран, Северная Корея, Россия, Венесуэла; a list of them is joined as the program joins every list (Chrome.and: the language's own "and"); any other place by the locale's name for its code |
