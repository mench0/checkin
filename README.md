# Checkin на Java (Spring Boot)

Тот же сценарий, что и в Python-версии:
1. Администратор в `/admin.html` создаёт приглашения и рассылает гостям ссылки.
2. Гость по ссылке (`/register.html?invite=...`) заполняет данные и делает селфи.
3. В ресторане гость сканирует общий QR, открывается `/checkin.html?event=ID`, включается камера.
4. Face ID: система ищет гостя по лицу среди зарегистрированных.
5. На экране имя и номер столика.

Стек: Java 17+, Spring Boot 3, SQLite, ONNX Runtime (модели insightface buffalo_l), ZXing для QR.
API и HTML-страницы совпадают с Python-версией.

## Что нужно

- **JDK 17 или новее.** На Mac: `brew install openjdk@21` или через IntelliJ (File → Project Structure → SDK → Download JDK).
- **IntelliJ IDEA** (Community бесплатная) — в ней уже есть Maven.
- **Модели распознавания лиц** (~300 МБ):
  1. Скачайте `buffalo_l.zip`: https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip
  2. Распакуйте и положите в папку `models/` проекта два файла: `det_10g.onnx` и `w600k_r50.onnx`.

```
checkin-java/
  models/
    det_10g.onnx
    w600k_r50.onnx
  pom.xml
  src/...
```

Без моделей можно проверить работу с `FACE_MOCK=1`. В этом режиме лица не сравниваются по-настоящему, совпадает только то же самое фото.

## Запуск в IntelliJ IDEA (Mac)

1. **File → Open** → выберите папку `checkin-java` (где `pom.xml`) → **Trust Project**. IntelliJ сама скачает зависимости Maven.
2. Откройте `src/main/java/com/example/checkin/CheckinApplication.java` и нажмите зелёный ▶ рядом с `main` → **Run**.
3. Остановите (■) и настройте переменные: сверху **CheckinApplication → Edit Configurations… → Environment variables**:
   ```
   ADMIN_KEY=свой-пароль
   ```
   Для проверки без моделей добавьте `;FACE_MOCK=1`.
4. Снова ▶. В консоли должно появиться `Tomcat started on port 8000`.
5. Откройте `http://localhost:8000/admin.html`.

## Запуск из терминала

```
cd checkin-java
./mvnw spring-boot:run      # если есть mvnw, иначе: brew install maven && mvn spring-boot:run
```
С переменными: `ADMIN_KEY=свой-пароль mvn spring-boot:run`.

Собрать один jar для сервера: `mvn package`, затем `java -jar target/checkin-0.1.0.jar` (папка `models/` рядом).

## Доступ с телефонов гостей

Камера в браузере работает только по HTTPS. Быстрый вариант:
```
brew install cloudflared
cloudflared tunnel --url http://localhost:8000
```
Скопируйте выданный адрес и добавьте в переменные окружения `PUBLIC_URL=https://xxx.trycloudflare.com`, перезапустите.

## Настройки (переменные окружения)

| Переменная | По умолчанию | Что делает |
|---|---|---|
| `ADMIN_KEY` | `admin123` | Пароль админки, обязательно смените |
| `PUBLIC_URL` | адрес запроса | Внешний адрес для ссылок и QR |
| `PORT` | `8000` | Порт сервера |
| `DB_PATH` | `checkin.db` | Файл базы SQLite |
| `MODELS_DIR` | `models` | Папка с моделями |
| `FACE_THRESHOLD` | `0.40` | Порог совпадения лица (выше = строже) |
| `FACE_MARGIN` | `0.05` | Отрыв лучшего совпадения от второго |
| `FACE_MOCK` | `0` | `1` = заглушка без моделей |

## Структура кода

- `CheckinApplication.java` — запуск приложения.
- `ApiController.java` — все API: события, гости, столики, регистрация, вход, статистика.
- `FaceEngine.java` — детектор лиц SCRFD и ArcFace через ONNX Runtime.
- `FaceMath.java` — выравнивание лица по 5 точкам, NMS, сравнение векторов.
- `src/main/resources/static/` — страницы `admin.html`, `register.html`, `checkin.html`.

## Ограничения MVP

- **Лицензия моделей:** модели insightface buffalo_l разрешены только для некоммерческого исследовательского использования. Для работающего ресторана нужна коммерческая лицензия insightface или другая модель. Это касается и Python-версии.
- Нет liveness-проверки: чужое фото на экране может пройти Face ID.
- Биометрия: нужны согласие (галочка в форме есть), срок хранения и удаление. Кнопка «Удалить отпечатки лиц» в админке.
- SQLite и поиск перебором рассчитаны на сотни или несколько тысяч гостей.
