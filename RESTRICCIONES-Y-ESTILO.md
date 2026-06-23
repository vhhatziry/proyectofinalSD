# Restricciones y Estilo - Tesoreria Distribuida (Equipo 18)

Este documento es el **estandar de codigo y arquitectura** propio del proyecto
**Tesoreria Distribuida** del **Equipo 18**. Define la voz, la decomposicion y las
reglas de diseno que todo el codigo de este proyecto debe seguir al pie de la letra.
Un constructor que trabaje en un workspace separado debe poder producir, leyendo
solo este documento, exactamente esta voz, este andamiaje y esta arquitectura.

Sigue cada regla literalmente. El resultado es un codigo con identidad, estructura
y decomposicion propias.

---

## 1. Identidad y build

### Marca

- **Nombre del proyecto:** Tesoreria Distribuida.
- **Equipo:** 18.
- **Paquete raiz:** `mx.ipn.escom.tesoreria`.

### Coordenadas Maven

El build es **Maven**. Las coordenadas del artefacto son:

| Campo | Valor |
|---|---|
| `groupId` | `mx.ipn.escom.tesoreria` |
| `artifactId` | `tesoreria-distribuida` |
| `version` | `1.0.0` |
| `maven.compiler.release` | `17` |

### Empaquetado (fat jar)

- Se produce un **fat jar** con `maven-assembly-plugin`, descriptor
  `jar-with-dependencies`.
- La clase principal del manifiesto es `mx.ipn.escom.tesoreria.app.Node`.

### Dependencias permitidas (lista cerrada)

Estas son las **unicas** dependencias permitidas en el proyecto:

| Dependencia | Version | Uso |
|---|---|---|
| `org.mindrot:jbcrypt` | `0.4` | Hash de contrasenas |
| `com.auth0:java-jwt` | `4.3.0` | Emision y validacion de JWT |
| `com.google.code.gson:gson` | `2.13.1` | Serializacion JSON |

No se agregan dependencias nuevas fuera de esta lista.

### Acceso a Google Cloud Storage

El acceso a Google Cloud Storage se realiza **exclusivamente** por su **JSON API
REST**, usando `java.net.http.HttpClient` (cliente HTTP estandar de la JDK).

> El proyecto **no usa ningun SDK de Google Cloud**. No se incluye
> `com.google.cloud` ni `google-cloud-storage` ni ninguna libreria cliente de GCP.
> Todo el dialogo con GCS (autenticacion OAuth2 y operaciones de objetos) se
> construye a mano sobre la API REST.

---

## 2. Estilo de codigo

- **Idioma:** identificadores y comentarios en **ingles**, de forma consistente en
  todo el proyecto.
- **Charset:** **ASCII puro** en el codigo fuente. Sin acentos ni simbolos
  no-ASCII en ningun archivo `.java`.
- **Tamano de archivo:** cada archivo tiene **menos de 400 lineas**.
- **Dependencias:** sin dependencias nuevas (ver lista cerrada en seccion 1).
- **Documentacion:** cada tipo publico lleva un **doc comment breve** que describe
  su responsabilidad.

### Naturaleza del entregable: andamiaje (scaffold)

Este entregable es un **andamiaje**. Eso significa:

- `package`, `imports`, firmas, campos y doc comments estan **completos y
  correctos**.
- Los cuerpos de los metodos pueden quedar como `// TODO:` con
  `throw new UnsupportedOperationException("TODO")` o un placeholder minimo.
- El resultado debe estar **lo mas cerca posible de compilar**: las firmas y los
  tipos deben encajar entre si.

---

## 3. Roster de paquetes y clases

Todos los paquetes cuelgan de `mx.ipn.escom.tesoreria`.

| Paquete | Clases / tipos |
|---|---|
| `app` | `Node` (main), `NodeConfig`, `NodeStats` |
| `net` | `Server`, `IoLoop`, `Channel`, `RequestParser`, `Request`, `Reply`, `Routes`, `Endpoint` |
| `core` | `Account`, `Ledger`, `Bank`, `TransferException`, `Transfer`, `TransferLog`, `Money`, `Dataset`, `CommitListener` |
| `security` | `Tokens`, `Passwords`, `Credential`, `CredentialStore`, `Authenticator` |
| `api` | `RegisterEndpoint`, `LoginEndpoint`, `BalanceEndpoint`, `TransferEndpoint`, `StatsEndpoint`, `PanelEndpoint`, `DashboardEndpoint` |
| `cluster` | `ReplicaFeed` (lado lider), `ReplicaSync` (lado replica), `WireCodec` |
| `durable` | `Journal`, `GcsStore`, `GcsAuth` |
| `loadtest` | `LoadDriver` |
| `tests` (en `src/test/java/mx/ipn/escom/tesoreria/tests`) | `Assert`, `RunTests` + suites skeleton |

---

## 4. Reglas de diseno por subsistema

### 4.1 Transporte NIO (`net`)

Transporte construido sobre **`java.nio` puro**, sin librerias de red.

**`Server`**

- Posee **un** `ServerSocketChannel`, **un** hilo `IoLoop` (con **un solo**
  `Selector`) y un `ExecutorService` de workers cuyo tamano viene de `TES_WORKERS`.
- Firma: `Server(int port, Routes routes, int workers)`; metodos `start()`,
  `int port()`, `stop()`.

**`IoLoop`**

- Hace **solo** tres cosas: `accept`, `read`, `write`.
- Cuando una request queda **completa**, **siempre** la entrega al pool de workers.
  No hay manejo inline en el hilo del selector.
- El worker calcula la `Reply` y encola sus bytes; el `IoLoop` hace el flush de
  esos bytes.
- Patron: **Reactor + thread pool** (Doug Lea, pattern 3).

**`Channel`**

- Estado por conexion, adjunto a la `SelectionKey`: buffer de lectura, **una**
  instancia de `RequestParser`, cola de escritura saliente y banderas.

**`RequestParser`**

- Instancia **con estado**, una por conexion.
- Maquina de estados **explicita** por enum:
  `READ_LINE -> READ_HEADERS -> READ_BODY -> DONE`.
- Consume bytes **incrementalmente** (no es un re-scan estatico ni sin estado).

**`Routes`**

- `register(method, pattern, Endpoint)`.
- El `pattern` soporta una variable de ruta `"{id}"`.
- `match(method, path)` devuelve el `Endpoint` resuelto mas los `params`
  extraidos, por **igualdad de segmentos** (no por prefijo mas largo).
- Devuelve **405** si el path existe pero el metodo no coincide; **404** si no hay
  path.

**`Endpoint`**

- Interfaz **funcional**: `Reply serve(Request req)`.

**`Request`** expone:

- `method()`, `path()`, `header(name)` (case-insensitive), `body()` (`byte[]`),
  `bodyText()`, `pathParam(name)`, `wantsClose()`.

**`Reply`** ofrece factories:

- `json(int, String)`, `text(int, String)`, `html(int, byte[])`, `status(int)`,
  y `close()`.

### 4.2 Dominio (`core`)

El dominio se parte en **dos responsabilidades separadas** para que ninguna clase
sea un dios-objeto. El `Ledger` es el **almacen de cuentas y la mecanica del
dinero**: nada mas. El `Bank` es la **capa de transaccion** que orquesta la
secuencia, el log y los listeners alrededor de cada movimiento. El control de
errores en la ruta de dinero viaja por **excepciones** (`TransferException`), no
por enums de resultado.

**`Account`**

- `final int id`; `final String owner`; `long balanceCents`.
- `balanceCents` se muta **solo** dentro del monitor intrinseco de la propia
  `Account`. No hay `ReentrantLock` por campo; se usa `synchronized` sobre los
  objetos `Account`.

**`Ledger`** (solo cuentas + mecanica de dinero)

- **No es singleton.** Se construye y se inyecta por constructor.
- Guarda las cuentas en un `ConcurrentHashMap<Integer, Account>`.
- API: `add(Account)`, `get(int) -> Account | null`, `size() -> int`,
  `totalCents() -> long` (suma de saldos; es el invariante del sistema),
  `move(from, to, cents)`.
- `move` mueve dinero entre dos cuentas tomando **ambos** monitores intrinsecos
  de las `Account`, adquiridos en orden por id (menor primero) para que dos
  movimientos cruzados no se traben entre si. Si una de las cuentas no existe
  lanza `TransferException.noSuchAccount(id)`; si el saldo origen no alcanza,
  lanza `TransferException.lowBalance(id)`.
- El `Ledger` **no** valida auto-transferencia ni el signo del monto (eso es
  responsabilidad del `Bank`) y **no** asigna secuencia ni escribe en el log.
- El `Ledger` **no** conoce la secuencia, el `TransferLog`, los `CommitListener`
  ni la replicacion: todo eso vive en el `Bank`.

**`Bank`** (capa de transaccion sobre el `Ledger`)

- Se construye con `Bank(Ledger ledger)`.
- Estado interno: `AtomicLong sequence` y `applied`, `volatile long lastSeq`, un
  `TransferLog` y una lista de `CommitListener` (`CopyOnWriteArrayList`).
- `transfer(from, to, cents) -> long`: valida la auto-transferencia
  (`TransferException.selfTransfer()`) y el monto positivo
  (`TransferException.badAmount()`), delega el movimiento real a
  `ledger.move(...)`, y al exito estampa una secuencia nueva
  (`sequence.incrementAndGet()`), arma el `Transfer`, lo agrega al log, avanza
  `applied` y `lastSeq` y notifica a los `CommitListener`. Devuelve el `seq`.
- `applyReplicated(Transfer)`: ruta de la replica. Intenta `ledger.move(...)`
  ignorando una `TransferException` si la cuenta no existe, registra la entrada en
  el log, sube `applied`/`lastSeq`, y eleva el contador local al `seq` de la
  entrada usando un bloque `synchronized` con un `if` explicito (**sin**
  `accumulateAndGet(Math::max)`).
- Lectores: `sequence()`, `appliedCount()`, `lastSeq()`, `log()`,
  `addCommitListener(CommitListener)`.

**`TransferException`** (checked, **sin** enum)

- `extends Exception`; lleva un `String code` y un constructor privado.
- `code()` expone el codigo. Factories estaticas:
  `selfTransfer() -> "self_transfer"`, `badAmount() -> "bad_amount"`,
  `noSuchAccount(int id) -> "no_such_account"`, `lowBalance(int id) -> "low_balance"`.

**`CommitListener`**

- Interfaz: `{ void onCommit(Transfer t); }`, registrada en una lista del `Bank`.

**`Transfer`**

- `record (long seq, int from, int to, long cents)`.

**`TransferLog`**

- `append(Transfer)`, `List<Transfer> since(long seq)`, `long size()`.

**`Money`** (helper extraido, **no** inline en los endpoints)

- `toCents(String decimal)`: `BigDecimal.movePointRight(2)` con `HALF_UP` ->
  `long`.
- `toDecimal(long cents)`: `String` con 2 decimales.

**`Dataset`**

- `int loadInto(Ledger, Path csv)`: parsea un CSV de cuentas con el formato
  `nombre,apellido1,apellido2,saldo` (4 columnas, **sin** columna `id` y **sin**
  cabecera; ejemplo: `ABRAHAM,AGUILAR,AGUILAR,361803.11`).
- El `id` de cada cuenta es el **numero de fila en base 1** (la primera fila es
  `id` 1; no se salta ninguna linea). El `owner` es
  `nombre + " " + apellido1 + " " + apellido2`. El saldo decimal (columna 4) se
  convierte a centavos via `Money`.
- Construye cada `Account(id, owner, cents)`, hace `ledger.add(...)` y devuelve
  cuantas cuentas cargo.

### 4.3 Seguridad (`security`)

**`Tokens`**

- Es **instancia** (no estatico), construida con el secreto de `TES_JWT_SECRET`.
- Usa `com.auth0` java-jwt con **HMAC256**, issuer `"tesoreria"`.
- `issue(subject) -> token`; `validate(token) -> subject` (lanza si es invalido).

**`Passwords`**

- `static encode(raw)` / `static matches(raw, hash)` con
  `org.mindrot.jbcrypt.BCrypt`.

**`Credential`**

- `record (String username, String passwordHash)`.

**`CredentialStore`**

- `ConcurrentHashMap<String, Credential>`.
- `register(username, hash)` (devuelve `false` si ya existe), `find(username)`.

**`Authenticator`**

- `signup(u, p)`; `login(u, p) -> token | null`; `authorize(Request)` valida el
  header `Bearer`.

### 4.4 API (`api`)

- Cada endpoint implementa `Endpoint`, valida el metodo (**405**), valida el JWT
  donde aplica (**401**), parsea JSON con gson y devuelve una `Reply`.
- `RegisterEndpoint` y `LoginEndpoint` son **dos endpoints separados**.
- `BalanceEndpoint` devuelve el shape obligatorio
  `{id, propietario, balance}`.
- `TransferEndpoint` acepta el shape obligatorio
  `{sourceAccountId, targetAccountId, amount}`, y usa `Money` + `Bank.transfer`;
  ante una `TransferException` responde `404` si el codigo es `"no_such_account"`
  y `400` en cualquier otro caso, con cuerpo `{"error": <code>}`.
- `StatsEndpoint` devuelve el `NodeStats` de este nodo en JSON.
- `PanelEndpoint` agrega este nodo mas los peers (`TES_PEERS`) para el dashboard.
- `DashboardEndpoint` sirve `dashboard.html` desde `resources`.

### 4.5 Cluster (`cluster`) - TCP, una linea JSON por transferencia

**`ReplicaFeed`** (lado lider)

- Servidor TCP (`ServerSocketChannel` / `Socket`) en `TES_REPL_PORT`; se
  construye con `ReplicaFeed(int port, TransferLog log)` e implementa
  `CommitListener`.
- La replica conecta y manda una linea de saludo `"CATCHUP <seq>"`.
- El feed reemite por `WireCodec` todas las entradas del log desde `<seq>`
  (catch-up) y luego queda conectado empujando los nuevos commits **en vivo**:
  su `onCommit(Transfer)` envia la nueva entrada JSON a las replicas conectadas.

**`ReplicaSync`** (lado replica)

- Se construye con `ReplicaSync(String leaderHost, int port, Bank bank)`.
- Conecta a `leaderHost:port`, manda `"CATCHUP <bank.sequence()>"`, lee lineas,
  decodifica con `WireCodec` y aplica via `bank.applyReplicated`. Tiene su propia
  logica de reconexion.

**`WireCodec`** (formato de cable JSON)

- `encode(Transfer) -> String`: **una linea JSON** por transferencia con la forma
  `{"seq":..,"from":..,"to":..,"cents":..}` (serializada con gson). Este formato
  de cable es deliberadamente JSON, no CSV.
- `decode(String line) -> Transfer`: parsea esa linea JSON con gson y arma
  `Transfer(seq, from, to, cents)`.

### 4.6 Durable (`durable`) - GCS por JSON API, `java.net.http` puro

**`GcsAuth`**

- Se construye con la ruta de un archivo de credenciales de cuenta de servicio
  (`TES_GCS_KEYFILE`).
- Genera un access token OAuth2 **firmando** un JWT assertion con la llave
  privada de la cuenta de servicio:
  - RS256 con `java.security.Signature("SHA256withRSA")` +
    `PKCS8EncodedKeySpec` + Base64 URL.
  - `POST` con `grant_type` jwt-bearer al `token_uri`.
  - Cachea el `access_token` hasta casi expirar.
- Usa el **flujo key-file** (practica 28). **No** usa el metadata server de la VM.

**`GcsStore`**

- `put(Transfer)`: `POST` del objeto `journal/tx-<seq>.json` al API de upload de
  `storage.googleapis.com`, con Bearer token.
- `count()` / `readAll()`: `list` + `GET` de los objetos con prefijo `journal/`.
- Parseo con gson.

**`Journal`** (solo en el lider; persiste y recupera)

- Implementa `CommitListener`.
- `recover(CommitListener apply) -> int`: en arranque frio reaplica los objetos
  existentes (cada uno via `apply.onCommit`, tipicamente `bank::applyReplicated`)
  y devuelve cuantos recupero.
- `start(int recovered)`: fija el contador.
- `onCommit(Transfer)`: registrado como `CommitListener` en el `Bank` del lider,
  sube cada nueva transferencia a GCS.
- `stored()`: devuelve el conteo.

---

## 5. App / runtime (`app`)

**`Node.main(args)`**

1. `port = args[0]` o `8080`.
2. Lee `NodeConfig`.
3. Carga el dataset en un `Ledger` nuevo (via `Dataset`) y envuelve ese ledger en
   un `Bank` nuevo.
4. Si es **lider** (`TES_LEADER_HOST` vacio): arranca `journal.recover(bank::applyReplicated)`,
   arranca el `Journal`, lo registra como `CommitListener` del `Bank`, arranca el
   `ReplicaFeed(replPort, bank.log())` y lo registra tambien como `CommitListener`
   del `Bank` para el fan-out de commits en vivo.
5. Si es **replica**: arranca `ReplicaSync(leaderHost, replPort, bank)`.
6. Construye `Routes` + `Authenticator` + endpoints (las lecturas de saldo van
   contra el `Ledger`, las transferencias contra el `Bank`), arranca el `Server`
   y bloquea.

**`NodeConfig`**

- Lee: `TES_DATASET`, `TES_JWT_SECRET`, `TES_NODE_ID`, `TES_PEERS`,
  `TES_LEADER_HOST`, `TES_REPL_PORT` (default `9090`), `TES_BUCKET`,
  `TES_GCS_KEYFILE`, `TES_WORKERS`.
- `isLeader()` = `TES_LEADER_HOST` vacio o ausente.

**`NodeStats`**

- Se construye con `NodeStats(Ledger ledger, Bank bank, Journal journal, NodeConfig config)`.
- Numero de cuentas y saldo total desde el `Ledger` (`size`/`totalCents`); numero
  de transferencias, secuencia y ultima tx desde el `Bank`
  (`appliedCount`/`sequence`/`lastSeq`).
- Mas `%CPU` / `%RAM` / `%Disco` leidos de `/proc` y del filesystem (java puro,
  sin libs).
- Mas el conteo de transferencias en GCS (del `Journal`, en el lider; el
  `journal` puede ser `null` en una replica, en cuyo caso ese dato es `0`/`N-A`).

---

## 6. Generador de carga (`loadtest`)

**`LoadDriver.main(host, port, segundos, ...)`**

- Hace `register` + `login` para obtener un JWT.
- Durante 1 minuto lanza **80% GET balance / 20% POST transfer** con
  `java.net.http.HttpClient` (puede usar varios clientes/hilos para throughput).
- Cuenta lecturas y transferencias exitosas, e imprime ademas el **puntaje** segun
  la formula del PDF: `transferencias * 4 + lecturas`. Permite **etiquetar el
  escenario** por argumento.
- Al final verifica el **invariante**: la suma de saldos debe ser igual al total
  inicial conocido.
  - Si no cuadra: reporta la cuenta culpable.
  - Si cuadra: imprime los dos conteos.

---

## 7. Tests (`tests`)

- Harness en **Java puro**: `Assert` + `RunTests` (sin JUnit).
- Suites skeleton:
  - `Ledger`: `move` lanzando `TransferException`, `Bank.transfer` asignando
    secuencia, e invariante con `totalCents()` bajo concurrencia.
  - `Money`.
  - `RequestParser`.
  - `WireCodec`.

---

## 8. Variables de entorno (prefijo `TES_`)

Todas las variables de entorno del proyecto llevan el prefijo `TES_`. El **rol**
del nodo (lider o replica) se infiere de `TES_LEADER_HOST`.

> El secreto JWT es el **mismo valor** en los 3 nodos; la variable se llama
> `TES_JWT_SECRET`.

| Variable | Rol | Default | Descripcion |
|---|---|---|---|
| `TES_DATASET` | ambos | (sin default) | Ruta al CSV de cuentas (`nombre,apellido1,apellido2,saldo`; el `id` se deriva del indice de fila, base 1) que se carga en el `Ledger`. |
| `TES_JWT_SECRET` | ambos | (sin default) | Secreto HMAC256 para firmar/validar JWT. Mismo valor en los 3 nodos. |
| `TES_NODE_ID` | ambos | (sin default) | Identificador de este nodo. |
| `TES_PEERS` | ambos | (sin default) | Lista de peers que `PanelEndpoint` agrega para el dashboard. |
| `TES_LEADER_HOST` | ambos | vacio | Host del lider. Si esta vacio o ausente, este nodo es **lider**; si tiene valor, es **replica**. Determina `isLeader()`. |
| `TES_REPL_PORT` | ambos | `9090` | Puerto TCP del feed de replicacion (`ReplicaFeed` / `ReplicaSync`). |
| `TES_BUCKET` | lider | (sin default) | Bucket de GCS donde el `Journal` persiste las transferencias. |
| `TES_GCS_KEYFILE` | lider | (sin default) | Ruta al archivo de credenciales de cuenta de servicio para `GcsAuth` (flujo key-file). |
| `TES_WORKERS` | ambos | (sin default) | Tamano del `ExecutorService` de workers del `Server`. |
