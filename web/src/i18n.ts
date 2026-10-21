import i18n from "i18next";
import { initReactI18next } from "react-i18next";

const en = {
  appName: "BookSound",
  nav: { library: "Library", imports: "Add books", admin: "Server", account: "Account" },
  common: {
    save: "Save", cancel: "Cancel", delete: "Delete", close: "Close", retry: "Try again", loading: "Loading…",
    error: "Something went wrong", more: "More", add: "Add", yes: "Yes", no: "No", edit: "Edit", download: "Download",
  },
  login: {
    tagline: "Your audiobooks on every device",
    title: "Sign in", username: "User name", password: "Password", submit: "Sign in",
    invalid: "Wrong user name or password", locked: "Too many attempts. Try again later.",
    setupTitle: "Create the admin account", setupHint: "The setup token is printed in the server's log on its first start.",
    setupToken: "Setup token", setupSubmit: "Create account", passwordHint: "At least 8 characters",
  },
  library: {
    search: "Search title, author, narrator, series", empty: "No books yet", emptyHint: "Add books from torrents or files, or put M4B files into the server's library folder.",
    nothingFound: "Nothing found", sort: "Sort", filter: "Show", series: "Series", booksInSeries_one: "{{count}} book", booksInSeries_few: "{{count}} books", booksInSeries_many: "{{count}} books", booksInSeries_other: "{{count}} books",
    sortBy: { RECENT: "Recently played", ADDED: "Recently added", TITLE: "Title", AUTHOR: "Author", SERIES: "Series", DURATION: "Length", PROGRESS: "Progress" },
    filters: { ALL: "All books", NOT_STARTED: "Not started", IN_PROGRESS: "In progress", FINISHED: "Finished" },
    finishedOf: "{{finished}}/{{total}} done", openSeries: "Open series", upNext: "Up next: {{title}}", seriesEmpty: "No books in this series",
    continue: "Continue listening", all: "All books", count_one: "{{count}} book", count_few: "{{count}} books", count_many: "{{count}} books", count_other: "{{count}} books", hours: "{{h}} h of listening",
  },
  book: {
    chapterCount_one: "{{count}} chapter", chapterCount_few: "{{count}} chapters", chapterCount_many: "{{count}} chapters", chapterCount_other: "{{count}} chapters",
    play: "Play", resume: "Continue", restart: "From the start", chapters: "Chapters", narrator: "Read by {{name}}",
    downloadFile: "Download M4B", watchCopy: "Watch copy", watchPreparing: "Preparing the watch copy…", watchReady: "Download watch copy",
    edit: "Edit details", cover: "Change cover", deleteTitle: "Delete this book for everyone?", deleteText: "The file is removed from the server.",
    finished: "Finished", markFinished: "Mark as finished", markUnfinished: "Mark as not finished", left: "{{time}} left",
    title: "Title", author: "Author", narratorField: "Narrator", series: "Series", seriesIndex: "Number", year: "Year", genre: "Genre", description: "Description", language: "Language",
  },
  player: {
    nothing: "Nothing playing", speed: "Speed", sleep: "Sleep timer", sleepOff: "Off", sleepEndOfChapter: "End of chapter", sleepMinutes: "{{count}} min",
    sleepLeft: "Stops in {{time}}", voice: "Narrator's voice", back: "Back {{s}} s", forward: "Forward {{s}} s", chapter: "Chapter {{n}} of {{total}}",
    otherDevice: "Another device stopped at {{time}}. Continue from there?", jump: "Jump",
    presets: { OFF: "Off", DEEP_MALE: "Deep voice", BRIGHT_FEMALE: "Bright voice", NO_BASS: "No bass", OLD_RECORDING: "Old recording", CLARITY: "Clarity" },
  },
  imports: {
    subtitle: "Files and torrents become M4B books in the library. Uploads from phones show up here too.",
    upload: "Upload files", uploadFolder: "Upload a folder", uploadHint: "MP3, M4A or M4B. Files of one book are joined into one M4B with a chapter per file.",
    torrentLink: "Magnet link, .torrent link or info-hash", addTorrent: "Add torrent", torrentFile: "Add .torrent file", autoImport: "Convert without review",
    jobs: "Conversions", torrents: "Torrents", noJobs: "Nothing is converting", noTorrents: "No torrents", cancel: "Cancel", remove: "Remove",
    review: "Review", reviewTitle: "Book details", files: "Files", convert: "Save and convert", uploading: "Uploading {{name}}…", uploadStarted: "Upload finished, converting",
    sourceName: "Book name", peers_one: "{{count}} peer", peers_other: "{{count}} peers",
    stage: { QUEUED: "Waiting", PREPARING: "Reading files", CONVERTING: "Converting", WRITING: "Writing", VERIFYING: "Checking", FINISHING: "Finishing", DONE: "Done", FAILED: "Failed", CANCELLED: "Cancelled" },
    phase: { FETCHING_METADATA: "Getting file list", AWAITING_REVIEW: "Waiting for review", DOWNLOADING: "Downloading", DOWNLOADED: "Downloaded", VERIFYING: "Checking", CONVERTING: "Converting", COMPLETED: "In the library", FAILED: "Failed", PAUSED: "Paused" },
    torrentsUnavailable: "Torrents are not available on this server.",
    incoming: "Arriving from {{from}}",
  },
  account: {
    devices: "Signed-in devices", thisDevice: "this device", signOut: "Sign out", revoke: "Sign out device", password: "Change password",
    currentPassword: "Current password", newPassword: "New password", changed: "Password changed", lastSeen: "Last seen {{when}}",
    kind: { PHONE: "Phone", WATCH: "Watch", WEB: "Browser" },
    admin: "Administrator", user: "Listener", profile: "Profile",
  },
  admin: {
    subtitle: "Users, conversion, torrents and watch copies.",
    users: "Users", newUser: "New user", isAdmin: "Admin", resetPassword: "Set password", settings: "Conversion and torrents",
    bitrate: "AAC bitrate, kbps", mono: "Convert to mono", parallel: "Conversions at once", uploadLimit: "Torrent upload limit, KiB/s (0 = none)",
    port: "Torrent port (after restart)", cacheLimit: "Watch copies cache, MiB", rescan: "Rescan library folder",
    rescanned: "Added {{added}}, moved {{relinked}}, missing {{missing}}", saved: "Saved",
    watchProfiles: "Watch profiles", profileName: "Name", profileBitrate: "Bitrate, kbps", profileMono: "Mono", profileRate: "Sample rate",
    profileRateKeep: "As the source", profileCover: "Cover size, px (0 = none)", profileMode: "Make copies",
    modes: { ON_DEMAND: "When a watch asks", ALL_BOOKS: "For all books", IN_PROGRESS: "For books in progress" },
    newProfile: "New profile",
  },
};

const ru: typeof en = {
  appName: "BookSound",
  nav: { library: "Библиотека", imports: "Добавить", admin: "Сервер", account: "Аккаунт" },
  common: {
    save: "Сохранить", cancel: "Отмена", delete: "Удалить", close: "Закрыть", retry: "Повторить", loading: "Загрузка…",
    error: "Что-то пошло не так", more: "Ещё", add: "Добавить", yes: "Да", no: "Нет", edit: "Изменить", download: "Скачать",
  },
  login: {
    tagline: "Ваши аудиокниги на всех устройствах",
    title: "Вход", username: "Имя пользователя", password: "Пароль", submit: "Войти",
    invalid: "Неверное имя или пароль", locked: "Слишком много попыток. Попробуйте позже.",
    setupTitle: "Создание администратора", setupHint: "Токен настройки печатается в лог сервера при первом запуске.",
    setupToken: "Токен настройки", setupSubmit: "Создать аккаунт", passwordHint: "Не меньше 8 символов",
  },
  library: {
    search: "Поиск по названию, автору, чтецу, серии", empty: "Книг пока нет", emptyHint: "Добавьте книги из торрентов или файлов, или положите M4B в папку библиотеки сервера.",
    nothingFound: "Ничего не найдено", sort: "Сортировка", filter: "Показать", series: "Серия", booksInSeries_one: "{{count}} книга", booksInSeries_few: "{{count}} книги", booksInSeries_many: "{{count}} книг", booksInSeries_other: "{{count}} книги",
    sortBy: { RECENT: "Недавно слушали", ADDED: "Недавно добавленные", TITLE: "Название", AUTHOR: "Автор", SERIES: "Серия", DURATION: "Длительность", PROGRESS: "Прогресс" },
    filters: { ALL: "Все книги", NOT_STARTED: "Не начатые", IN_PROGRESS: "Слушаю", FINISHED: "Прослушанные" },
    finishedOf: "{{finished}}/{{total}} прослушано", openSeries: "Открыть серию", upNext: "Далее: {{title}}", seriesEmpty: "В серии нет книг",
    continue: "Продолжить", all: "Все книги", count_one: "{{count}} книга", count_few: "{{count}} книги", count_many: "{{count}} книг", count_other: "{{count}} книги", hours: "{{h}} ч прослушивания",
  },
  book: {
    chapterCount_one: "{{count}} глава", chapterCount_few: "{{count}} главы", chapterCount_many: "{{count}} глав", chapterCount_other: "{{count}} главы",
    play: "Слушать", resume: "Продолжить", restart: "С начала", chapters: "Главы", narrator: "Читает {{name}}",
    downloadFile: "Скачать M4B", watchCopy: "Копия для часов", watchPreparing: "Готовлю копию для часов…", watchReady: "Скачать копию для часов",
    edit: "Изменить данные", cover: "Сменить обложку", deleteTitle: "Удалить книгу для всех?", deleteText: "Файл будет удалён с сервера.",
    finished: "Прослушана", markFinished: "Отметить прослушанной", markUnfinished: "Снять отметку", left: "Осталось {{time}}",
    title: "Название", author: "Автор", narratorField: "Чтец", series: "Серия", seriesIndex: "Номер", year: "Год", genre: "Жанр", description: "Описание", language: "Язык",
  },
  player: {
    nothing: "Ничего не играет", speed: "Скорость", sleep: "Таймер сна", sleepOff: "Выкл.", sleepEndOfChapter: "Конец главы", sleepMinutes: "{{count}} мин",
    sleepLeft: "Остановка через {{time}}", voice: "Голос чтеца", back: "Назад на {{s}} с", forward: "Вперёд на {{s}} с", chapter: "Глава {{n}} из {{total}}",
    otherDevice: "На другом устройстве остановились на {{time}}. Продолжить оттуда?", jump: "Перейти",
    presets: { OFF: "Выкл.", DEEP_MALE: "Низкий голос", BRIGHT_FEMALE: "Высокий голос", NO_BASS: "Без басов", OLD_RECORDING: "Старая запись", CLARITY: "Разборчивость" },
  },
  imports: {
    subtitle: "Файлы и торренты превращаются в M4B-книги библиотеки. Здесь же видно, что заливают телефоны.",
    upload: "Загрузить файлы", uploadFolder: "Загрузить папку", uploadHint: "MP3, M4A или M4B. Файлы одной книги собираются в один M4B, по главе на файл.",
    torrentLink: "Magnet-ссылка, ссылка на .torrent или хеш", addTorrent: "Добавить торрент", torrentFile: "Добавить .torrent-файл", autoImport: "Конвертировать без проверки",
    jobs: "Конвертация", torrents: "Торренты", noJobs: "Ничего не конвертируется", noTorrents: "Торрентов нет", cancel: "Отменить", remove: "Убрать",
    review: "Проверить", reviewTitle: "Данные книги", files: "Файлы", convert: "Сохранить и конвертировать", uploading: "Загрузка {{name}}…", uploadStarted: "Загружено, идёт конвертация",
    sourceName: "Название книги", peers_one: "{{count}} пир", peers_other: "{{count}} пиров",
    stage: { QUEUED: "В очереди", PREPARING: "Чтение файлов", CONVERTING: "Конвертация", WRITING: "Запись", VERIFYING: "Проверка", FINISHING: "Завершение", DONE: "Готово", FAILED: "Ошибка", CANCELLED: "Отменено" },
    phase: { FETCHING_METADATA: "Получение списка файлов", AWAITING_REVIEW: "Ждёт проверки", DOWNLOADING: "Скачивается", DOWNLOADED: "Скачан", VERIFYING: "Проверка", CONVERTING: "Конвертация", COMPLETED: "В библиотеке", FAILED: "Ошибка", PAUSED: "Пауза" },
    torrentsUnavailable: "Торренты на этом сервере недоступны.",
    incoming: "Загружается с {{from}}",
  },
  account: {
    devices: "Устройства", thisDevice: "это устройство", signOut: "Выйти", revoke: "Отключить устройство", password: "Сменить пароль",
    currentPassword: "Текущий пароль", newPassword: "Новый пароль", changed: "Пароль изменён", lastSeen: "Был(о) в сети {{when}}",
    kind: { PHONE: "Телефон", WATCH: "Часы", WEB: "Браузер" },
    admin: "Администратор", user: "Слушатель", profile: "Профиль",
  },
  admin: {
    subtitle: "Пользователи, конвертация, торренты и копии для часов.",
    users: "Пользователи", newUser: "Новый пользователь", isAdmin: "Админ", resetPassword: "Задать пароль", settings: "Конвертация и торренты",
    bitrate: "Битрейт AAC, кбит/с", mono: "Конвертировать в моно", parallel: "Конвертаций одновременно", uploadLimit: "Лимит отдачи торрентов, КиБ/с (0 — без лимита)",
    port: "Порт торрентов (после перезапуска)", cacheLimit: "Кэш копий для часов, МиБ", rescan: "Пересканировать папку библиотеки",
    rescanned: "Добавлено {{added}}, перемещено {{relinked}}, пропало {{missing}}", saved: "Сохранено",
    watchProfiles: "Профили для часов", profileName: "Название", profileBitrate: "Битрейт, кбит/с", profileMono: "Моно", profileRate: "Частота",
    profileRateKeep: "Как в источнике", profileCover: "Обложка, px (0 — без неё)", profileMode: "Делать копии",
    modes: { ON_DEMAND: "Когда часы попросят", ALL_BOOKS: "Для всех книг", IN_PROGRESS: "Для начатых книг" },
    newProfile: "Новый профиль",
  },
};

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ru: { translation: ru } },
  lng: navigator.language.toLowerCase().startsWith("ru") ? "ru" : "en",
  fallbackLng: "en",
  interpolation: { escapeValue: false },
});

export default i18n;
