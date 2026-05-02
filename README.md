# SentryAPI

**SentryAPI** je Minecraft Paper plugin pro server SentrySMP. Uvnitř pluginu běží vestavěný HTTP server (Ktor + Netty), který vystavuje REST API pro správu serveru a zobrazuje scoreboard jako hologramy ve světě.

> Podrobná dokumentace je v souboru [DOCS.md](DOCS.md).

---

## Funkce

| Funkce | Popis |
|--------|-------|
| Embedded HTTP server (Ktor / Netty) | REST API dostupné lokálně na `127.0.0.1:<port>` |
| API klíč (`X-API-Key`) | Jednoduchá autentizace hlavičkou; Swagger UI je veřejné |
| `POST /command` | Vykoná příkaz jako konzole, vrátí výsledek |
| `GET /players` | Vrátí seznam online hráčů (jméno + UUID) |
| `GET /banlist` | Vrátí seznam zabanovaných hráčů s důvodem |
| `GET /player/{name}` | Vrátí coins a money hráče (přes PlayerPoints + Essentials) |
| `GET /` | Health-check endpoint (bez autentizace) |
| Swagger UI | Interaktivní dokumentace API na `/swagger` |
| Hologramový scoreboard (`/sentrysmp holo`) | Zobrazí store leaderboard jako hologram ve světě; automatické obnovování |
| ScoreboardClient | HTTP klient s cache pro načítání dat z `sentrysmp.eu/api` |
| Soft-depend: LuckPerms, PlaceholderAPI | Prefix hráčů v hologramu (volitelné) |

---

## Sestavení & Spuštění

### Předpoklady

- **Java 21** (JDK)
- Gradle Wrapper (`./gradlew`) — není třeba mít Gradle nainstalovaný globálně

### Gradle úlohy

| Úloha | Popis |
|-------|-------|
| `./gradlew build` | Sestaví projekt a spustí testy |
| `./gradlew test` | Spustí pouze unit testy |
| `./gradlew fatJar` | Vytvoří spustitelný fat JAR se všemi závislostmi |
| `./gradlew classes` | Zkompiluje zdrojové soubory (bez testů) |

### Vytvoření fat JAR (nasazení jako plugin)

```bash
./gradlew fatJar
```

Výsledný JAR se nachází v `build/libs/`:

```
SentryAPI-1.2-all.jar
```

Zkopírujte soubor do složky `plugins/` svého Paper serveru a restartujte server.

### Konfigurace po prvním spuštění

Po prvním načtení pluginu se vytvoří `plugins/SentryAPI/config.yml`. Upravte alespoň:

```yaml
port: 8080
api-key: "vaše-tajné-heslo"
```

Podrobný popis všech konfiguračních položek naleznete v [DOCS.md](DOCS.md).

---

## Použití API

API naslouchá na `http://127.0.0.1:<port>`. Všechny endpointy (kromě `/` a `/swagger`) vyžadují hlavičku:

```
X-API-Key: <váš klíč z config.yml>
```

Příklad — seznam hráčů online:

```bash
curl -H "X-API-Key: vaše-heslo" http://127.0.0.1:8080/players
```

Swagger UI s interaktivní dokumentací je dostupné na:

```
http://127.0.0.1:8080/swagger
```

---

## In-game příkazy

| Příkaz | Popis |
|--------|-------|
| `/sentrysmp holo add <all\|today\|week\|month>` | Zobrazí hologramový store leaderboard na vaší pozici |
| `/sentrysmp holo remove` | Odstraní váš hologram |

Oprávnění: `sentrysmp.use`

---

## Jak to funguje

- **Typ projektu:** Paper plugin, který při spuštění (`onEnable`) nastartuje embedded Ktor/Netty HTTP server.
- **Bezpečnost:** Server naslouchá výhradně na `127.0.0.1` — API není dostupné z internetu přímo.
- **Thread safety:** Všechna volání Bukkit API (dispatch příkaz, seznam hráčů, spawn entit) probíhají v hlavním vlákně serveru pomocí `Bukkit.getScheduler().runTask(...)`.
- **Hologramy:** Jsou realizovány neviditelnými `ArmorStand` entitami s vlastním jménem. Při pádu serveru jsou UUIDs persistovány do `holos.txt` a při dalším startu jsou osiřelé entity automaticky odstraněny.
- **Životní cyklus:** Při `onDisable()` jsou odstraněny všechny hologramy a HTTP server je korektně zastaven.
