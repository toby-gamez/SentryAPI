# SentryAPI

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

* [Ktor Documentation](https://ktor.io/docs/home.html)
* [Ktor GitHub page](https://github.com/ktorio/ktor)
* [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).

## Features

Here's a list of features included in this project:

| Name                                                                      | Description                                                         |
|---------------------------------------------------------------------------|---------------------------------------------------------------------|
| [AsyncAPI](https://start.ktor.io/p/com.asyncapi/server-asyncapi)          | Generates and serves AsyncAPI documentation                         |
| [CORS](https://start.ktor.io/p/io.ktor/server-cors)                       | Enables Cross-Origin Resource Sharing (CORS)                        |
| [Caching Headers](https://start.ktor.io/p/io.ktor/server-caching-headers) | Provides options for responding with standard cache-control headers |
| [Swagger](https://start.ktor.io/p/io.ktor/server-swagger)                 | Serves Swagger UI for your project                                  |
| [Authentication](https://start.ktor.io/p/io.ktor/server-auth)             | Provides extension point for handling the Authorization header      |
| [Authentication Basic](https://start.ktor.io/p/io.ktor/server-auth-basic) | Handles 'Basic' username / password authentication scheme           |
| [Koin](https://start.ktor.io/p/io.insert-koin/server-koin)                | Provides dependency injection                                       |

## Building & Running

To build or run the project, use one of the following tasks:

| Task | Description |
|------|-------------|

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Vytvoření JAR (fat JAR)

Pro vytvoření spustitelného "fat" JAR (obsahuje závislosti) použijte Gradle wrapper a úlohu `fatJar` definovanou v `build.gradle.kts`:

```bash
./gradlew fatJar
```

Po dokončení najdete JAR v adresáři `build/libs/`. Název souboru bude vypadat přibližně takto:

```
SentryAPI-1.0.0-SNAPSHOT-all.jar
```

Spuštění JAR:

```bash
java -jar build/libs/SentryAPI-1.0.0-SNAPSHOT-all.jar
```

Poznámky:
- Pokud nepoužíváte wrapper, lze spustit `gradle fatJar` pokud máte gradle nainstalovaný.
- Pokud by úloha `fatJar` selhala kvůli duplicitám v `META-INF`, podívejte se do `build.gradle.kts` pro `duplicatesStrategy` nebo používejte `shadowJar` (pokud je dostupný).

## Jak to funguje

Krátké shrnutí architektury a chování projektu:

- **Typ projektu:** Minecraft Paper/PaperMC plugin, který zároveň spouští vestavěný HTTP server (Ktor).
- **Hlavní třída:** `com.sentrysmp.CommandApiPlugin` (definováno v `plugin.yml`). Při zapnutí pluginu se zavolá `onEnable()` a spustí se embedded Ktor server.
- **Síťové nastavení:** Server naslouchá na `127.0.0.1` a portu definovaném v `src/main/resources/config.yml` (výchozí `8080`). To zajišťuje, že API je dostupné pouze lokálně ze serveru.
- **Autentizace:** API používá hlavičku `X-API-Key`. Klíč nastavíte v `config.yml` (`api-key`). Požadavky bez správného klíče dostanou `401 Unauthorized`. Výjimka: Swagger UI (`/swagger`) je přístupné bez klíče.
- **Endpointy:**
	- `POST /command` — přijme JSON `{ "command": "..." }`, vykoná příkaz na serveru jako konzole a vrátí `CommandResponse` s `success` (boolean) a `output` (seznam stringů). Ve stávající implementaci může být `output` prázdný kvůli rozdílům v Paper API.
	- `GET /players` — vrací seznam online hráčů jako `PlayersResponse` s objekty `PlayerInfo { name, uuid }`.
- **Swagger / OpenAPI:** UI je dostupné na `http://127.0.0.1:<port>/swagger` a používá soubor `openapi/documentation.yaml` ze zdrojů.
- **Běh v rámci Bukkit/Paper:** Volání příkazů a získávání hráčů probíhá přes Bukkit API spuštěné v hlavním vlákně serveru pomocí `Bukkit.getScheduler().runTask(...)`, aby byla zaručena bezpečnost API.
- **Životní cyklus:** Při vypnutí pluginu (`onDisable()`) se HTTP server správně zastaví (`httpServer?.stop(...)`).

Pokud budete potřebovat rozšíření (např. vracet konzolový výstup, změnit bind adresu nebo přidat další endpointy), můžu to implementovat nebo navrhnout konkrétní změny.
