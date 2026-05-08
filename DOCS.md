# SentryAPI — Kompletní dokumentace

> Verze pluginu: **1.2**  
> Minecraft API: **Paper 1.21.1**  
> Jazyk: Kotlin 2.x · JVM 21  

---

## Obsah

1. [Přehled projektu](#1-přehled-projektu)
2. [Architektura](#2-architektura)
3. [Struktura souborů](#3-struktura-souborů)
4. [Instalace a nasazení](#4-instalace-a-nasazení)
5. [Konfigurace](#5-konfigurace)
6. [HTTP API](#6-http-api)
   - [Autentizace](#autentizace)
   - [GET /](#get-)
   - [POST /command](#post-command)
   - [GET /players](#get-players)
   - [GET /banlist](#get-banlist)
   - [GET /player/{name}](#get-playername)
   - [Swagger UI](#swagger-ui)
7. [In-game příkazy](#7-in-game-příkazy)
8. [Hologramový systém](#8-hologramový-systém)
9. [Scoreboard klient](#9-scoreboard-klient)
10. [Datové modely](#10-datové-modely)
11. [Soft-závislosti](#11-soft-závislosti)
12. [Sestavení projektu](#12-sestavení-projektu)
13. [Testy](#13-testy)
14. [Bezpečnost](#14-bezpečnost)
15. [Životní cyklus pluginu](#15-životní-cyklus-pluginu)
16. [Řešení problémů](#16-řešení-problémů)

---

## 1. Přehled projektu

SentryAPI je Minecraft Paper plugin určený pro server **SentrySMP**. Kombinuje dvě hlavní funkce:

1. **REST HTTP API** — umožňuje externím službám (např. webovému rozhraní serveru nebo Discord botovi) komunikovat se serverem: spouštět příkazy, zjišťovat seznam hráčů, banlist a statistiky hráčů.
2. **Hologramový store leaderboard** — zobrazuje žebříček hráčů podle jejich plateb v obchodě přímo ve světě jako hologram složený z neviditelných ArmorStand entit; data jsou načítána z veřejného API `sentrysmp.eu`.

Plugin je napsán v jazyce **Kotlin** a využívá framework **Ktor** s enginem **Netty** jako embedded HTTP server.

---

## 2. Architektura

```
┌─────────────────────────────────────────────────────────┐
│  Paper Server (hlavní vlákno JVM)                       │
│                                                         │
│  CommandApiPlugin (JavaPlugin)                          │
│  ├── onEnable()                                         │
│  │   ├── Načte config.yml                               │
│  │   ├── Vytvoří ScoreboardClient (HTTP klient + cache) │
│  │   ├── Zaregistruje HoloCommand executor              │
│  │   ├── Nastartuje embedded Ktor/Netty server          │
│  │   └── Obnoví přetrvávající hologramy                 │
│  └── onDisable()                                        │
│      ├── Odstraní hologramy                             │
│      └── Zastaví HTTP server                            │
│                                                         │
│  HoloCommand (CommandExecutor)                          │
│  ├── Zpracovává /sentrysmp holo add|remove              │
│  ├── Načítá data přes ScoreboardClient (async vlákno)   │
│  └── Spawnuje/obnovuje hologramy přes HologramRenderer  │
│                                                         │
│  HologramRenderer                                       │
│  └── Vytváří/aktualizuje/odstraňuje ArmorStand entity   │
│                                                         │
│  ScoreboardClient                                       │
│  └── HTTP volání na sentrysmp.eu/api s in-memory cache  │
└───────────────┬─────────────────────────────────────────┘
                │ HTTP (127.0.0.1:<port>)
┌───────────────▼─────────────────────────────────────────┐
│  Ktor / Netty embedded server                           │
│  Routes:                                                │
│  ├── GET  /            (health check, bez auth)         │
│  ├── GET  /swagger     (Swagger UI, bez auth)           │
│  ├── POST /command     (vykoná příkaz konzole)          │
│  ├── GET  /players     (seznam online hráčů)            │
│  ├── GET  /banlist     (seznam zabanovaných)            │
│  └── GET  /player/{name} (statistiky hráče)            │
└─────────────────────────────────────────────────────────┘
```

### Klíčové návrhové principy

- **Thread safety:** Veškerá volání Bukkit API (`dispatchCommand`, `getOnlinePlayers`, `getBannedPlayers`, spawning entit) jsou vždy prováděna v **hlavním vlákně serveru** prostřednictvím `Bukkit.getScheduler().runTask(plugin, ...)`. Ktor handlery jsou suspending funkce, které přes `suspendCancellableCoroutine` čekají na výsledek z hlavního vlákna.
- **Lokální dostupnost:** Server naslouchá výhradně na `127.0.0.1`, nikoli na `0.0.0.0`. API tak není přístupné přímo z internetu.
- **Persistence hologramů:** UUID všech ArmorStand entit patřících hologramům jsou ukládána do `holos.txt`. Při startu pluginu jsou načteny a případné osiřelé entity (po pádu serveru) jsou odstraněny.

---

## 3. Struktura souborů

```
SentryAPI/
├── build.gradle.kts                     # Gradle build skript (závislosti, fatJar task)
├── settings.gradle.kts                  # Název projektu
├── gradle.properties                    # Verze Gradle
├── gradlew / gradlew.bat                # Gradle Wrapper
├── README.md                            # Stručný přehled
├── DOCS.md                              # Tato dokumentace
└── src/
    ├── main/
    │   ├── kotlin/com/sentrysmp/
    │   │   ├── CommandApiPlugin.kt      # Hlavní třída pluginu (JavaPlugin)
    │   │   ├── Routes.kt               # Definice HTTP endpointů a business logika
    │   │   ├── Models.kt               # Datové třídy (request/response modely)
    │   │   ├── AppModule.kt            # Test-friendly konfigurace Ktor aplikace
    │   │   ├── HoloCommand.kt          # Executor příkazu /sentrysmp holo
    │   │   ├── HologramRenderer.kt     # Spawn/update/remove ArmorStand hologramů
    │   │   └── ScoreboardClient.kt     # HTTP klient pro sentrysmp.eu API s cache
    │   └── resources/
    │       ├── plugin.yml              # Paper plugin descriptor
    │       ├── config.yml              # Výchozí konfigurace
    │       ├── logback.xml             # Logování (Logback)
    │       ├── documentation.yaml      # OpenAPI spec (záloha)
    │       └── openapi/
    │           └── documentation.yaml  # OpenAPI 3.0 spec (servírováno Swagger UI)
    └── test/
        └── kotlin/
            └── ServerTest.kt           # Základní integrační test
```

---

## 4. Instalace a nasazení

### Požadavky

| Požadavek | Verze |
|-----------|-------|
| Java (JDK) | 21+ |
| Paper Minecraft server | 1.21.1+ |
| LuckPerms (volitelné) | 5.4+ |
| PlaceholderAPI (volitelné) | libovolná kompatibilní |

### Postup

1. **Sestavte plugin** (viz [Sestavení projektu](#12-sestavení-projektu)):
   ```bash
   ./gradlew fatJar
   ```

2. **Zkopírujte JAR** ze složky `build/libs/` do složky `plugins/` Paper serveru:
   ```
   plugins/SentryAPI-1.2-all.jar
   ```

3. **Spusťte server.** Plugin se načte, vytvoří `plugins/SentryAPI/config.yml` a nastartuje HTTP server.

4. **Upravte konfiguraci** v `plugins/SentryAPI/config.yml` (viz [Konfigurace](#5-konfigurace)) a proveďte `/reload` nebo restartujte server.

---

## 5. Konfigurace

Konfigurační soubor se nachází v `plugins/SentryAPI/config.yml`. Výchozí hodnoty:

```yaml
# TCP port, na kterém naslouchá HTTP server (pouze 127.0.0.1)
port: 8080

# API klíč — povinně změňte před nasazením!
api-key: "sentry_51Hk9ZQmX7pL3v9aB8cD2eF0gHiJkLmNoPqRsTuVwXyZ1234567890AbCdEfGhIj"

# Hologram settings
# Doba platnosti hologramu v sekundách (0 = neexpiruje)
hologram-ttl-seconds: 0
# Interval obnovování dat hologramu v sekundách
hologram-refresh-seconds: 5

# Scoreboard API settings
# Základní URL API pro data scoreboard
scoreboard-base-url: "https://www.sentrysmp.eu/api"
# TTL cache pro scoreboard klient v sekundách
scoreboard-cache-ttl: 60
```

### Popis položek

| Položka | Typ | Výchozí | Popis |
|---------|-----|---------|-------|
| `port` | int | `8080` | Port HTTP serveru (pouze localhost) |
| `api-key` | string | (dlouhý řetězec) | Tajný klíč pro autentizaci; **povinně změňte** |
| `hologram-ttl-seconds` | long | `0` | Jak dlouho hologram existuje (0 = navždy) |
| `hologram-refresh-seconds` | long | `5` | Jak často se obnovují data hologramu |
| `scoreboard-base-url` | string | `https://www.sentrysmp.eu/api` | Základní URL scoreboard API |
| `scoreboard-cache-ttl` | long | `60` | TTL cache pro odpovědi scoreboard API |

---

## 6. HTTP API

Základní URL: `http://127.0.0.1:<port>`

### Autentizace

Všechny endpointy kromě `GET /` a `GET /swagger*` vyžadují HTTP hlavičku:

```
X-API-Key: <hodnota api-key z config.yml>
```

Při chybějícím nebo nesprávném klíči server vrátí:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{"error": "Unauthorized"}
```

---

### GET /

Health-check endpoint. Nevyžaduje autentizaci.

**Odpověď:**
```json
{"status": "ok"}
```

**HTTP kód:** `200 OK`

---

### POST /command

Vykoná příkaz na serveru jako konzolový sender.

**Požadavek:**
```http
POST /command
X-API-Key: <klíč>
Content-Type: application/json

{"command": "say Hello World"}
```

**Odpověď:**
```json
{
  "output": [],
  "success": true
}
```

| Pole | Typ | Popis |
|------|-----|-------|
| `command` | string | Příkaz bez úvodního lomítka (např. `"op Steve"`) |
| `output` | string[] | Zachycený výstup příkazu (může být prázdný) |
| `success` | boolean | `true` pokud příkaz proběhl bez výjimky |

> **Poznámka:** Pole `output` může být prázdné, protože zachycování výstupu z Bukkit konzole je závislé na implementaci pluginu, který příkaz obsluhuje. Konzolový výstup je zachytáván přes Log4j2 appender i přes delegovaný `ConsoleCommandSender`, ale ne všechny pluginy posílají výstup stejnou cestou.

---

### GET /players

Vrátí seznam hráčů aktuálně přihlášených na serveru.

**Požadavek:**
```http
GET /players
X-API-Key: <klíč>
```

**Odpověď:**
```json
{
  "players": [
    {"name": "Steve", "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5"},
    {"name": "Alex",  "uuid": "8667ba71-b85a-4004-af54-457a9734eed7"}
  ]
}
```

| Pole | Typ | Popis |
|------|-----|-------|
| `players` | PlayerInfo[] | Seznam online hráčů |
| `players[].name` | string | Herní jméno hráče |
| `players[].uuid` | string | UUID hráče (formát `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) |

---

### GET /banlist

Vrátí seznam zabanovaných hráčů (profilový ban, nikoli IP ban).

**Požadavek:**
```http
GET /banlist
X-API-Key: <klíč>
```

**Odpověď:**
```json
{
  "banned": [
    {
      "name": "Griefer99",
      "uuid": "12345678-1234-1234-1234-123456789abc",
      "reason": "Griefing and hacking"
    }
  ]
}
```

| Pole | Typ | Popis |
|------|-----|-------|
| `banned` | BanEntry[] | Seznam zabanovaných hráčů |
| `banned[].name` | string | Herní jméno hráče |
| `banned[].uuid` | string | UUID hráče |
| `banned[].reason` | string \| null | Důvod banu (může být `null`) |

---

### GET /player/{name}

Vrátí statistiky hráče — počet coinů (PlayerPoints) a zůstatek peněz (Essentials Economy).

**Požadavek:**
```http
GET /player/Steve
X-API-Key: <klíč>
```

**Odpověď (úspěch):**
```json
{
  "player": "Steve",
  "coins": 1500,
  "money": 2345.75,
  "error": null
}
```

**Odpověď (chyba):**
```json
{
  "player": "Neexistující",
  "coins": null,
  "money": null,
  "error": "Player not found"
}
```

| Pole | Typ | Popis |
|------|-----|-------|
| `player` | string | Zadané jméno hráče |
| `coins` | long \| null | Počet coinů z PlayerPoints (`null` pokud nelze zjistit) |
| `money` | double \| null | Zůstatek z Essentials Economy (`null` pokud nelze zjistit) |
| `error` | string \| null | Popis chyby (`null` při úspěchu) |

> **Poznámka:** Data jsou získávána spuštěním příkazů `points look <jméno>` a `money <jméno>` a parsováním jejich výstupu. Pokud plugin PlayerPoints nebo Essentials není nainstalován, vrátí `null`.

**HTTP kódy:**
- `200 OK` — vždy (i při chybě — chyba je v těle odpovědi)
- `400 Bad Request` — pokud chybí parametr `{name}` v cestě

---

### Swagger UI

Interaktivní dokumentace API (OpenAPI 3.0) je dostupná bez autentizace:

```
http://127.0.0.1:<port>/swagger
```

OpenAPI specifikace ve formátu YAML se nachází v `src/main/resources/openapi/documentation.yaml`.

---

## 7. In-game příkazy

Plugin registruje příkaz `/sentrysmp` (alias definován v `plugin.yml`).

### Oprávnění

```
sentrysmp.use
```

Hráči bez tohoto oprávnění obdrží: `You do not have permission to use this command.`

### Syntaxe příkazů

| Příkaz | Popis |
|--------|-------|
| `/sentrysmp holo add all` | Zobrazí celkový store leaderboard (všechny časy) |
| `/sentrysmp holo add today` | Zobrazí dnešní store leaderboard |
| `/sentrysmp holo add week` | Zobrazí týdenní store leaderboard |
| `/sentrysmp holo add month` | Zobrazí měsíční store leaderboard |
| `/sentrysmp holo remove` | Odstraní váš hologram |

### Chování

- Hologram se vytvoří na pozici hráče (o 1 blok výše) nebo na spawn lokaci světa (pokud příkaz zadán z konzole).
- Každý hráč může mít aktivní pouze **jeden hologram** (nový příkaz `add` nahradí předchozí).
- Hologram se automaticky obnovuje každých `hologram-refresh-seconds` sekund (výchozí 5 s).
- Pokud je `hologram-ttl-seconds > 0`, hologram po uplynutí doby zmizí sám.
- Po restartu serveru jsou hologramy automaticky obnoveny ze souboru `plugins/SentryAPI/holo-sessions.yml`.

---

## 8. Hologramový systém

### Datový tok

```
/sentrysmp holo add <range>
    │
    ├── HoloCommand.onCommand()
    │       ├── Uloží konfiguraci hologramu do holo-sessions.yml
    │       └── Volá spawnHologramForKey(key, loc, range)
    │
    ├── [async vlákno] ScoreboardClient.get<Range>()
    │       └── HTTP GET na sentrysmp.eu/api/scoreboard/<range>
    │
    ├── [async vlákno] buildLines(entries, player)
    │       ├── Sestaví title + subtitle
    │       ├── Přidá řádky s rank, prefixem (LuckPerms), jménem, skóre
    │       └── Aplikuje PlaceholderAPI (pokud dostupné)
    │
    └── [hlavní vlákno] HologramRenderer.spawnHologram(loc, lines)
            └── Pro každý řádek spawne neviditelný ArmorStand s CustomName
```

### Persistence hologramů

Plugin udržuje dva soubory v `plugins/SentryAPI/`:

| Soubor | Obsah |
|--------|-------|
| `holos.txt` | UUID všech aktivních ArmorStand entit (jeden UUID na řádek) |
| `holo-sessions.yml` | Konfigurace hologramů (klíč, svět, souřadnice, range) pro obnovu po restartu |

Při startu pluginu (`onEnable`):
1. `cleanupOrphanedStands()` — přečte `holos.txt`, projde všechny světy a odstraní nalezené entity.
2. `restoreHolograms()` — přečte `holo-sessions.yml` a znovu spawne hologramy na původních pozicích.

### Formát hologramu

```
§c§lSTORE €§r          ← červený, tučný titulek
§7ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ§r       ← šedý podtitulek (small caps)
§c1.§r [prefix] Steve §c1234€§r
§c2.§r Alex §c987€§r
...                    ← až 10 řádků
```

---

## 9. Scoreboard klient

`ScoreboardClient` je HTTP klient pro načítání dat scoreboard z `https://www.sentrysmp.eu/api`.

### Endpointy

| Metoda | Endpoint | Popis |
|--------|----------|-------|
| `getAll()` | `/scoreboard/all` | Celkový žebříček |
| `getToday()` | `/scoreboard/today` | Dnešní žebříček |
| `getWeek()` | `/scoreboard/week` | Týdenní žebříček |
| `getMonth()` | `/scoreboard/month` | Měsíční žebříček |

### Funkce

- **In-memory cache** s konfigurovatelným TTL (`scoreboard-cache-ttl`, výchozí 60 s).
- **Automatický retry** — při selhání HTTP požadavku opakuje pokus max. 3×, exponenciální backoff (200 ms → 400 ms → 800 ms).
- **Flexibilní parsování** — zvládne odpověď jak ve formátu `ScoreboardResponse` (objekt s polem `entries`), tak jako holé pole `ScoreEntry[]`.
- Při selhání všech pokusů vrací `null` a loguje chybu.

### Datová struktura odpovědi

```json
{
  "entries": [
    {
      "rank": 1,
      "minecraftUsername": "Steve",
      "totalPaid": 1234.5,
      "transactionCount": 42,
      "lastPayment": "2024-12-01"
    }
  ],
  "period": "all"
}
```

---

## 10. Datové modely

Všechny modely jsou v souboru `Models.kt` a anotovány `@Serializable` (kotlinx.serialization).

### CommandRequest

```kotlin
data class CommandRequest(val command: String)
```

### CommandResponse

```kotlin
data class CommandResponse(val output: List<String>, val success: Boolean)
```

### PlayerInfo

```kotlin
data class PlayerInfo(val name: String, val uuid: String)
```

### PlayersResponse

```kotlin
data class PlayersResponse(val players: List<PlayerInfo>)
```

### BanEntry

```kotlin
data class BanEntry(val name: String, val uuid: String, val reason: String?)
```

### BanlistResponse

```kotlin
data class BanlistResponse(val banned: List<BanEntry>)
```

### PlayerStatsResponse

```kotlin
data class PlayerStatsResponse(
  val player: String,
  val coins: Long? = null,
  val money: Double? = null,
  val rank: String? = null,
  val statistics: PlayerStatistics? = null,
  val error: String? = null
)

### PlayerStatistics

```kotlin
data class PlayerStatistics(
  val playTimeSeconds: Long? = null,
  val playTimeTicks: Long? = null,
  val deaths: Long? = null,
  val playerKills: Long? = null,
  val mobsKilled: Long? = null,
  val blocksTravelled: Long? = null
)
```
```

### ScoreEntry

```kotlin
data class ScoreEntry(
    val rank: Int,
    val minecraftUsername: String,
    val totalPaid: Double,
    val transactionCount: Int,
    val lastPayment: String? = null
)
```

### ScoreboardResponse

```kotlin
data class ScoreboardResponse(
    val entries: List<ScoreEntry> = emptyList(),
    val period: String? = null
)
```

---

## 11. Soft-závislosti

Plugin funguje bez těchto závislostí, ale poskytuje rozšířené funkce, pokud jsou přítomny.

### LuckPerms (doporučeno)

- Pokud je LuckPerms aktivní, zobrazí se v hologramovém leaderboardu před jménem hráče jeho **prefix** (barevně formátovaný).
- Prefix je načten přes `LuckPermsProvider.get()` → `UserManager` → `cachedData.getMetaData()`.
- Pokud hráč není online, UUID je vyhledáno asynchronně.

### PlaceholderAPI (volitelné)

- Pokud je PlaceholderAPI přítomno, každý řádek hologramu je zpracován přes `PlaceholderAPI.setPlaceholders(player, line)`.
- Integrace je realizována reflexí, takže plugin se zkompiluje i bez PlaceholderAPI na classpath.

---

## 12. Sestavení projektu

Projekt používá **Gradle Wrapper** — není nutné mít Gradle nainstalovaný.

### Dostupné úlohy

| Úloha | Příkaz | Popis |
|-------|--------|-------|
| Sestavení | `./gradlew build` | Zkompiluje, sestaví a spustí testy |
| Testy | `./gradlew test` | Spustí pouze unit/integrační testy |
| Kompilace | `./gradlew classes` | Pouze kompilace zdrojových kódů |
| Fat JAR | `./gradlew fatJar` | Vytvoří standalone JAR se všemi závislostmi |

### Vytvoření deployovatelného JAR

```bash
./gradlew fatJar
```

Výstup: `build/libs/SentryAPI-1.2-all.jar`

JAR obsahuje všechny runtime závislosti (Ktor, Netty, kotlinx.serialization). Paper API a LuckPerms API jsou `compileOnly` — nesmí být v JAR, protože jsou poskytovány serverem.

### Verze závislostí

| Závislost | Verze | Scope |
|-----------|-------|-------|
| Paper API | 1.21.1-R0.1-SNAPSHOT | `compileOnly` |
| LuckPerms API | 5.4 | `compileOnly` |
| kotlinx-coroutines-core | 1.9.0 | `compileOnly` |
| log4j-core | 2.24.1 | `compileOnly` |
| Ktor server core | 3.4.0 | `implementation` |
| Ktor server netty | 3.4.0 | `implementation` |
| Ktor content negotiation | 3.4.0 | `implementation` |
| Ktor kotlinx-json serialization | 3.4.0 | `implementation` |
| Ktor swagger | 3.4.0 | `implementation` |
| kotlinx-serialization-json | 1.5.1 | `implementation` |

---

## 13. Testy

Základní integrační test se nachází v `src/test/kotlin/ServerTest.kt`.

### Spuštění testů

```bash
./gradlew test
```

### Existující testy

| Test | Popis |
|------|-------|
| `test root endpoint` | Ověří, že `GET /` vrátí `200 OK` (přes `testApplication`) |

Test používá `AppModule.configure()` — odlehčenou konfiguraci Ktor aplikace bez závislosti na Bukkit/Paper.

---

## 14. Bezpečnost

### HTTP server je lokální

Server naslouchá pouze na `127.0.0.1`. Přístup z internetu není možný přímo. Pokud chcete API zpřístupnit externě, použijte reverse proxy (nginx, Caddy) s HTTPS a případnou IP whitelist.

### API klíč

- Přenášen jako HTTP hlavička `X-API-Key`.
- Výchozí klíč v `config.yml` je nutné **před nasazením změnit**.
- Klíč je porovnáván prostým porovnáním stringů — pro produkci doporučujeme dlouhý náhodný řetězec (min. 32 znaků).

### Příkazy přes API

Endpoint `POST /command` vykoná **jakýkoli příkaz jako konzole** (tedy s plnými oprávněními). Přístup k tomuto endpointu musí být přísně omezen. Nikdy nevolávejte tento endpoint s uživatelsky zadaným vstupem bez validace.

---

## 15. Životní cyklus pluginu

### onEnable()

1. `saveDefaultConfig()` — vytvoří `config.yml` pokud neexistuje.
2. Načte `port`, `api-key`, `scoreboard-base-url`, `scoreboard-cache-ttl`, `hologram-ttl-seconds`, `hologram-refresh-seconds`.
3. Vytvoří `ScoreboardClient`.
4. `HoloCommand.cleanupOrphanedStands(plugin)` — odstraní přetrvávající ArmorStand entity z předchozí session.
5. Vytvoří a zaregistruje `HoloCommand` jako executor příkazu `sentrysmp`.
6. Spustí `embeddedServer(Netty, ...)` a zavolá `server.start(wait = false)`.
7. Naplánuje `holo.restoreHolograms()` na příští tick (světy jsou zaručeně načteny).

### onDisable()

1. `holoCommand.removeAll()` — zruší všechny Bukkit tasky, odstraní ArmorStand entity, smaže `holos.txt`.
2. `httpServer.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)` — korektně zastaví Netty server.

---

## 16. Řešení problémů

### HTTP server se nespustí

- Zkontrolujte, zda port (výchozí 8080) není obsazen jiným procesem: `netstat -tlnp | grep 8080`
- Zkontrolujte log serveru na řádky od `SentryAPI`.

### API vrací 401

- Ověřte, že posíláte hlavičku `X-API-Key` se správnou hodnotou z `config.yml`.

### `/players` nebo `/banlist` vrací prázdné pole

- Normální chování — pokud nikdo není online / zabanován.

### `/player/{name}` vrací `null` pro coins/money

- Plugin PlayerPoints nebo Essentials není nainstalován nebo hráč neexistuje.
- Zkontrolujte, zda příkazy `points look <jméno>` a `money <jméno>` fungují v konzoli serveru.

### Hologram se nespawní

- Ujistěte se, že máte oprávnění `sentrysmp.use`.
- Zkontrolujte, zda scoreboard API odpovídá (test: `curl https://www.sentrysmp.eu/api/scoreboard/all`).
- Podívejte se do konzole serveru na chybové výpisy od `ScoreboardClient`.

### Hologram se neobnoví po restartu

- Soubor `plugins/SentryAPI/holo-sessions.yml` musí existovat a musí v něm být uložený hologram.
- Svět musí existovat (pokud byl svět smazán nebo přejmenován, hologram nelze obnovit).

### Prefix hráče se nezobrazuje v hologramu

- Ověřte, že LuckPerms je nainstalován a povolen.
- Prefix musí být nastaven v LuckPerms pomocí `lp user <hráč> meta setprefix <priorita> "<prefix>"`.
