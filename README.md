# PPT Flashlight

Xposed-модуль для **Kyocera DuraForce Pro (KYOCERA E6810, Android 7.1.2, API 25)**:
длительное удержание физической кнопки **PPT** (linux-код `KEY_SEND`, `/dev/input/event6`)
переключает фонарик. Короткое нажатие игнорируется.

Классический Xposed API (SDK 25), без LSPosed-зависимостей, без BusyBox.

---

## 1. Как это работает

### Перехват (system_server)

```
com.android.server.input.InputManagerService
    private int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)
```

* Хук ставится из `XposedInit.handleLoadPackage()` **только** для пакета `android`
  и только когда `Process.myUid() == 1000`, т.е. внутри `system_server`.
  В остальные процессы (`android` есть у каждого приложения) модуль не вмешивается.
* Метод вызывается нативно из `InputDispatcher::notifyKey()` **до** проверок
  интерактивности, поэтому события доходят и при выключенном экране.
* Используется `XposedBridge.hookAllMethods(...)` — на 7.1.2 сигнатура одна.
* Возвращаемое значение оригинального метода не меняется, если не включено
  подавление (`ppt_flashlight_suppress`, по умолчанию **выключено**).
  При включённом подавлении для KEY_SEND возвращается `0` (не передавать приложению).

### Логика удержания

`HoldDetector` (чистая Java, покрыта unit-тестами):

| Событие | Действие |
|---|---|
| `ACTION_DOWN`, `repeatCount == 0` | запомнить `event.getEventTime()` (CLOCK_MONOTONIC) |
| повторный `DOWN` без `UP` | игнорировать, метка времени остаётся от первого |
| `ACTION_DOWN`, `repeatCount > 0` (автоповтор) | игнорировать |
| `ACTION_UP` | длительность = `up - down` |
| длительность < порога (500 мс) | `SHORT_PRESS` — ничего |
| длительность ≥ порога | `LONG_PRESS_TOGGLE` — одно переключение |
| длительность > 10 с | `STALE_RESET` — сброс без переключения (потерянный UP) |
| `UP` без `DOWN` | игнорировать |

После `UP` состояние сбрасывается, поэтому одно нажатие = максимум одно переключение;
повторный `UP` того же нажатия и «чужие» `UP` других клавиш ничего не делают.
Если `getEventTime()` недоступен (0), используется `SystemClock.uptimeMillis()`.

### Распознавание кнопки

Устройство отдаёт PPT как linux key 231 (`KEY_SEND`). AOSP-раскладка `Generic.kl`
не содержит ключ 231, поэтому производитель мог отдать его разными путями. Поэтому
событие считается «нашим», если совпал **keycode** (`event.getKeyCode()`) **или**
**scanCode** (`event.getScanCode()`); оба значения настраиваются. По умолчанию 231/231.

Каждая новая «чужая» клавиша один раз пишется в лог строкой `seen key keycode=… scanCode=…`,
чтобы можно было откалибровать модуль, если на устройстве другое соответствие.

### Фонарик

`system_server` не трогает железо напрямую: он отправляет явный broadcast
`com.pptflashlight.action.TOGGLE_TORCH` в компонент
`com.pptflashlight/.TorchReceiver`. В своём процессе модуль вызывает

```java
CameraManager.setTorchMode(cameraId, true/false)   // API 23+, наш случай — API 25
```

(тот же путь, который использует PBMC: `LightService` + `setTorchMode`).
`CameraService` требует для `setTorchMode` разрешение `android.permission.CAMERA`,
поэтому оно объявлено и запрашивается при первом запуске.
Пока фонарик горит, работает foreground-сервис — binder-клиент торча должен жить.
Если процесс модуля убит, фонарик гаснет (так работает torch API), а следующее
удержание снова его включает.

Fallback `sysfs` (`/sys/devices/virtual/msm_camera_flash/camera_flash_set/camera_flash`
через `su -c`) выключен по умолчанию и помечен как экспериментальный: на E6810
подтверждено только кратковременное срабатывание.

### Загрузка после перезагрузки

Модуль — обычный Xposed-модуль: `assets/xposed_init` → `com.pptflashlight.XposedInit`.
После активации в Xposed Installer и перезагрузки хук ставится заново самим фреймворком;
дополнительный Magisk-модуль не нужен.

---

## 2. Состав проекта

```
ppt-flashlight/
├── app/src/main/assets/xposed_init          точка входа классического Xposed
├── app/src/main/java/com/pptflashlight/
│   ├── XposedInit.java                      IXposedHookLoadPackage, фильтр system_server
│   ├── SystemHook.java                      хук interceptKeyBeforeQueueing
│   ├── HoldDetector.java                    состояние удержания (чистая Java)
│   ├── ModuleConfig.java                    настройки из Settings.Global + кэш
│   ├── TorchBridge.java                     broadcast system_server → приложение
│   ├── TorchReceiver.java                   приём запроса
│   ├── TorchService.java                    foreground-сервис, worker-поток
│   ├── TorchController.java                 CameraManager.setTorchMode / sysfs fallback
│   ├── MainActivity.java, ConfigWriter.java статус и калибровка
│   ├── Const.java, L.java                   имена и лог с TAG PPTFlashlight
├── app/src/test/java/com/pptflashlight/HoldDetectorTest.java
├── app/libs/xposed-api-82.jar               классический Xposed API (compileOnly)
├── keystore/ppt-flashlight.jks              личный ключ подписи (в git не входит, см. §3)
└── build.gradle, settings.gradle, gradle.properties
```

`xposedminversion = 82`; на устройстве стоит framework API 89.3 — совместимо.

---

## 3. Сборка

```bash
export ANDROID_HOME=/path/to/android-sdk
cd ppt-flashlight
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk (подписан ключом из keystore/)
```

Ключ подписи (`keystore/ppt-flashlight.jks`) и его пароль в репозиторий не входят — это
секрет. Если ключа нет, сборка проходит, но APK остаётся неподписанным. Чтобы подписать,
положите ключ в `keystore/` и передайте пароль одним из способов:

```bash
./gradlew :app:assembleRelease -PPPT_KEYSTORE_PASSWORD=ваш_пароль
# либо
export PPT_KEYSTORE_PASSWORD=ваш_пароль
```

Alias ключа — `pptflashlight`; сам пароль в репозитории не публикуется, он есть только
в вашем локальном ZIP-архиве с исходниками.
Менять ключ не нужно: иначе обновление не встанет поверх установленного модуля
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), придётся удалять приложение и активировать модуль заново.

Требуется JDK 17 и Android SDK (compileSdk 34, build-tools 34+; `targetSdk 25`,
`minSdk 23`). Android Studio: открыть папку `ppt-flashlight` как проект.

---

## 4. Установка

```bash
adb install -r app-release.apk
```

1. Открыть приложение **PPT Flashlight** один раз, выдать разрешение на камеру.
2. Xposed Installer → Модули → включить **PPT Flashlight**.
3. Перезагрузить устройство.
4. Проверить, что хук встал:

```bash
adb logcat -s PPTFlashlight
# PPTFlashlight: module loaded into system_server (pid ...)
# PPTFlashlight: hooked com.android.server.input.InputManagerService.interceptKeyBeforeQueueing (1 overload(s))
# PPTFlashlight: configuration: enabled=true keycode=231 scancode=231 holdMs=500 suppress=false ...
```

---

## 5. Настройка (необязательно)

Все параметры лежат в `Settings.Global` с префиксом `ppt_flashlight_`, значения по умолчанию
уже соответствуют E6810, так что настраивать ничего не обязательно.

| Ключ | По умолчанию | Смысл |
|---|---|---|
| `ppt_flashlight_enabled` | 1 | 0 — модуль не реагирует ни на что |
| `ppt_flashlight_keycode` | 231 | keycode PPT (`KEY_SEND`) |
| `ppt_flashlight_scancode` | 231 | scanCode PPT |
| `ppt_flashlight_hold_ms` | 500 | порог удержания, мс (≥ порога → переключение) |
| `ppt_flashlight_suppress` | 0 | 1 — «проглатывать» KEY_SEND (не отдавать приложению) |
| `ppt_flashlight_sysfs_torch` | 0 | 1 — fallback через sysfs/`su` вместо camera2 |
| `ppt_flashlight_sysfs_path` | `/sys/devices/virtual/msm_camera_flash/camera_flash_set/camera_flash` | узел для fallback |

Через adb:

```bash
adb shell settings put global ppt_flashlight_hold_ms 500
adb shell settings put global ppt_flashlight_suppress 1
adb shell settings get global ppt_flashlight_keycode
```

Изменения подхватываются в течение ~2 с: system_server читает настройки в фоновом потоке
(в хуке, который работает в потоке диспетчера ввода, никаких обращений к Settings нет), перезагрузка не нужна.
Те же значения можно править в самом приложении (кнопка **Save settings**): оно пишет
`Settings.Global` напрямую, если выдано `WRITE_SECURE_SETTINGS`, иначе через `su -c settings put global`.
Если не сработало ни то, ни другое — приложение покажет, какие ключи не записались, и это
делается командами adb выше.

---

## 6. Диагностика

```bash
adb logcat -c && adb logcat -s PPTFlashlight
```

Ожидаемая последовательность при удержании PPT ~1 с:

```
I PPTFlashlight: PPT down (keycode 231, scanCode 231), waiting for release
I PPTFlashlight: PPT held long enough, toggling flashlight
I PPTFlashlight: toggle broadcast sent to com.pptflashlight.TorchReceiver
I PPTFlashlight: toggle request received from system_server
I PPTFlashlight: torch ON: torch on via camera2 (camera id 0)
```

Короткое нажатие:

```
I PPTFlashlight: PPT down (keycode 231, scanCode 231), waiting for release
I PPTFlashlight: PPT short press ignored (threshold 500 ms)
```

Если PPT вообще не распознаётся, в логе будет строка с настоящими кодами кнопки:

```
I PPTFlashlight: seen key keycode=0 scanCode=231 (not the configured PPT key 231 - ...)
```

Тогда пропишите эти значения в `ppt_flashlight_keycode` / `ppt_flashlight_scancode`
(типичный случай для незамапленного ключа — `keycode=0`, `scanCode=231`).

---

## 7. План проверки на устройстве

| Тест | Ожидаемо |
|---|---|
| PPT 100–200 мс | в логе `short press ignored`, фонарик не меняется |
| PPT 400 мс | то же |
| PPT 500 мс | одно переключение |
| PPT 1 с | одно переключение |
| PPT 3 с | одно переключение |
| повторное удержание | ещё одно переключение |
| перезагрузка | в логе `module loaded into system_server` + `boot completed - PPT Flashlight is active` |
| громкость ±, камера, питание | работают штатно |
| экран выключен | удержание переключает фонарик |

---

## 8. Что проверено, а что нет

**Проверено здесь (в облачной сборке):**

* проект компилируется, unit-тесты (17 тестов: `HoldDetectorTest` — 139/400/499/500/1000/3210 мс,
  автоповтор, двойной DOWN, потерянный UP, «чужие» UP, fallback на uptime) проходят;
* APK собирается и подписывается; в нём есть `assets/xposed_init` с
  `com.pptflashlight.XposedInit`, манифест с `xposedmodule`, `xposedminversion=82`,
  dex содержит `com.pptflashlight.*`;
* сигнатура метода и его вызов из `InputDispatcher::notifyKey()` сверены с исходниками
  AOSP `android-7.1.2_r39` (`InputManagerService.java`, `InputDispatcher.cpp`);
* требование `android.permission.CAMERA` для `setTorchMode` сверено с `CameraService.cpp`.

**Не проверено (нет устройства Kyocera E6810 в этой среде):**

* фактическое переключение фонарика и точное значение keycode/scanCode, которое отдаёт
  прошивка 5.572VZ для PPT-кнопки;
* поведение штатной обработки KEY_SEND (нужно ли включать подавление);
* работа sysfs-fallback через `su` (он выключен по умолчанию и остаётся экспериментальным).

Известный компромисс: `TorchReceiver` объявлен `exported="true"`, иначе system_server (uid 1000)
не смог бы доставить ему broadcast. Любое стороннее приложение тоже может послать
`com.pptflashlight.action.TOGGLE_TORCH` и переключить фонарик — и ничего больше; никаких
привилегий это не даёт.

То есть APK собран и статически проверен, но «рабочим на телефоне» его можно называть
только после прогона таблицы из раздела 7.

---

## 9. Если не работает

| Симптом | Что делать |
|---|---|
| в логе нет `module loaded into system_server` | модуль не активирован в Xposed Installer или не было перезагрузки |
| есть `module loaded`, нет `hooked ...` | в логе рядом будет ошибка `no interceptKeyBeforeQueueing method found` — пришлите её |
| нет строк `PPT down` при нажатии | смотрите строки `seen key ...` и пропишите keycode/scanCode |
| `PPT held long enough`, но фонарика нет | смотрите строку `torch ON/OFF: ...` — там причина (нет разрешения камеры, камера занята); проверьте кнопкой **Test torch** |
| фонарик включается, но сразу гаснет | процесс модуля убивается ROM'ом; выключите оптимизацию батареи для PPT Flashlight |
| штатное действие PPT мешает | `settings put global ppt_flashlight_suppress 1` |

---

## 10. Отличия от PBMC

PBMC — универсальный комбайн (цепочки действий, медиа-клавиши, покупки, своя БД).
Здесь оставлено только нужное: один хук, одна кнопка, одно действие.
Механизм тот же: `interceptKeyBeforeQueueing` в `system_server` → broadcast в свой процесс →
`CameraManager.setTorchMode`. Подавление события вынесено в настройку и по умолчанию выключено.

---

## 11. Что в поставке

| Файл | Что это |
|---|---|
| `dist/PPTFlashlight-1.0.apk` | готовый подписанный модуль (v1+v2 подписи), ставить через `adb install -r` |
| `dist/ppt-flashlight-source.zip` | полный проект Android Studio: исходники, тесты, `gradlew`, `assets/xposed_init`, `libs/xposed-api-82.jar` |
| `keystore/ppt-flashlight.jks` | ключ подписи (alias и пароль `pptflashlight`). В репозиторий **не** попадает (`.gitignore`): это ключ, а не исходник. Нужен, чтобы обновления ставились поверх без переустановки |

Пересборка:

```bash
unzip ppt-flashlight-source.zip -d ppt-flashlight && cd ppt-flashlight
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew testReleaseUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 12. Самопроизвольные перезагрузки: как найти причину

В версии 1.1 убрана единственная реально опасная вещь в модуле: `sendBroadcast()` из
`interceptKeyBeforeQueueing` выполнялся в потоке диспетчера ввода, т.е. с захваченным
множеством системных блокировок и с синхронным вызовом в ActivityManager. Теперь broadcast
отправляется из отдельного рабочего потока (`ppt-flashlight-toggle`), а в хуке остаются только
арифметика по времени и запись в лог. Установка хука сделана идемпотентной.

Но перезагрузки могли быть и не из-за модуля (Magisk 23 + Xposed 89.3 на 7.1.2 после включения
нового модуля часто пересобирают dex и ребутятся), поэтому нужны факты, а не догадки.

Снять их можно только с самого телефона — сразу после очередной перезагрузки:

```bash
# 1. Кто упал: system_server (watchdog/ANR/crash) или ядро (panic)
adb shell su -c 'dumpsys dropbox --print | grep -E "^(====|Subject|Package|Cmdline|Exception|  at )" | tail -80'

# 2. Стеки нативных падений (msm_flash / camera / kernel)
adb shell su -c 'ls -lt /data/tombstones/ | head'

# 3. Последний хвост консоли ядра (если был panic — он здесь)
adb shell su -c 'cat /sys/fs/pstore/console-ramoops-0 2>/dev/null | tail -60'

# 4. Крэши приложений и системных процессов
adb logcat -b crash -d | tail -80

# 5. Был ли наш хук вообще причастен: строки перед перезагрузкой
adb logcat -b all -d | grep -i "pptflashlight" | tail -40
```

Что искать: если в `dropbox` есть `system_server_watchdog` с трейсом, где фигурирует
`InputDispatcher`/`InputManagerService` — виноват путь ввода (тогда важен апдейт 1.1);
если `console-ramoops` заканчивается `msm_flash`/`camera` — это драйвер вспышки;
если ничего нет, а в логах видно `dex2oat` — это обычная пересборка после включения модуля.

### Если телефон ушёл в цикл перезагрузок и до интерфейса не добраться

1. **TWRP** (или adb в короткое окно загрузки): отключить модуль, убрав его из списка
   включённых у классического Xposed Installer:
   ```bash
   adb shell su -c 'sed -i "/com.pptflashlight/d" /data/data/de.robv.android.xposed.installer/conf/modules.list'
   ```
   (путь относится к классическому Xposed Installer; если у вас другой установщик — снимите
   галочку с модуля в нём).
2. Либо в TWRP → Advanced → File Manager удалить `com.pptflashlight` из этого файла,
   либо переименовать `modules.list` — тогда не загрузится ни один модуль.
3. После загрузки модуль можно включать снова: он пассивен до первого события `KEY_SEND`.

### Что в логе подтверждает, что модуль загрузился штатно

```bash
adb shell su -c 'logcat -d -s PPTFlashlight' | head
```
```
I PPTFlashlight: module loaded into system_server (pid 1234)
I PPTFlashlight: hooked com.android.server.input.InputManagerService.interceptKeyBeforeQueueing (1 overload(s))
I PPTFlashlight: configuration: enabled=true keycode=231 scancode=231 holdMs=500 suppress=false …
```
Если после перезагрузки этих строк нет — модуль не активирован (или его выключил установщик).
