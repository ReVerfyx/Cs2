# Соль: установка CS2-сервера с нуля (Linux)

Ниже — базовая схема для Ubuntu/Debian. Репозиторий **не хранит файлы самой CS2**: SteamCMD скачивает официальный dedicated server, а этот репозиторий добавляет режим **Соль**.

## 1. Скачать CS2 Dedicated Server

CS2 Dedicated сейчас ставится через SteamCMD как AppID `730`.

```bash
sudo apt update
sudo apt install -y steamcmd lib32gcc-s1

sudo useradd -m -s /bin/bash cs2 || true
sudo -iu cs2

mkdir -p ~/server
steamcmd +force_install_dir /home/cs2/server +login anonymous +app_update 730 validate +quit
```

Файлы сервера окажутся примерно здесь:

```
/home/cs2/server/
└── game/
    ├── bin/linuxsteamrt64/cs2
    └── csgo/
```

## 2. Установить Metamod:Source

Нужна ветка **Metamod:Source 2.x** для Source 2. Для актуального CounterStrikeSharp используй сборку с KHook (1467+).

Распакуй `addons` Metamod в:

```
/home/cs2/server/game/csgo/
```

В `/home/cs2/server/game/csgo/gameinfo.gi` добавь первой строкой внутри `SearchPaths`:

```
Game    csgo/addons/metamod
```

После обновления CS2 Valve может перезаписать `gameinfo.gi`, поэтому эту строку иногда нужно вернуть.

## 3. Установить CounterStrikeSharp

Для первого запуска бери сборку **with-runtime** и распакуй её `addons` в:

```
/home/cs2/server/game/csgo/
```

После запуска в консоли должны работать:

```
meta list
css_plugins list
```

## 4. Установить «Соль»

На сервере:

```bash
git clone https://github.com/ReVerfyx/Cs2.git ~/Sol
cd ~/Sol
bash scripts/install-plugin.sh /home/cs2/server
```

Если хочешь собирать вручную:

```bash
dotnet publish src/Sol/Sol.csproj -c Release -o build/Sol
mkdir -p /home/cs2/server/game/csgo/addons/counterstrikesharp/plugins/Sol
cp -a build/Sol/. /home/cs2/server/game/csgo/addons/counterstrikesharp/plugins/Sol/
cp cfg/sol_server.cfg /home/cs2/server/game/csgo/cfg/
```

## 5. Запуск

Создай GSLT для AppID 730 и не публикуй токен в GitHub.

Пример запуска:

```bash
cd /home/cs2/server/game/bin/linuxsteamrt64

./cs2 -dedicated -console -usercon -port 27015 \
  +map de_nuke \
  +game_type 0 \
  +game_mode 1 \
  +sv_setsteamaccount "YOUR_GSLT_TOKEN" \
  +exec sol_server.cfg
```

## 6. Проверка

После старта:

```
meta list
css_plugins list
css_salt_status
```

Для ручного теста хаоса:

```
css_salt
```

Сразу сменить карту на случайную из `MapPool`:

```
css_salt_map
```

## 7. Конфиг плагина

После первого запуска появится файл:

```
game/csgo/addons/counterstrikesharp/configs/plugins/Sol/Sol.json
```

Там меняются:

- число аномалий на раунд;
- высота и радиус воздушного плэнта;
- частота ПВО и дронов;
- частота смены карт;
- список карт `MapPool`.

Для workshop-карт сначала установи их на CS2-сервер, а затем добавь фактическое имя карты в `MapPool`.
