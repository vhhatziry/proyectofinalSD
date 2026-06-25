# Tesoreria Distribuida -- Equipo 18

Workspace de la version del **Equipo 18** del Proyecto Final de Sistemas
Distribuidos. Es un mini banco distribuido escrito en Java puro: tres nodos que
mantienen un libro de cuentas (ledger) replicado, con registro/login por JWT,
transferencias entre cuentas, durabilidad en Cloud Storage (GCS) y un dashboard
para monitorear el cluster.

El paquete raiz es `mx.ipn.escom.tesoreria`. La marca del proyecto es
**"Tesoreria Distribuida"**. El build es con Maven y produce un fat jar
ejecutable; un mismo jar arranca como lider o como replica segun las variables
de entorno `TES_*`.

## Arbol de directorios

```
hatziry/
  pom.xml                                  build Maven (fat jar con assembly)
  README.md                                este archivo
  REQUERIMIENTOS.md                        que debe hacer el sistema
  RESTRICCIONES-Y-ESTILO.md                estandar de codigo y restricciones
  src/
    main/
      java/mx/ipn/escom/tesoreria/
        app/        Node (main), NodeConfig (env TES_*), NodeStats
        net/        Server, IoLoop, Channel, RequestParser, Request, Reply,
                    Routes, Endpoint  (transporte NIO crudo, sin librerias)
        core/       Account, Ledger, Bank, TransferException, Transfer,
                    TransferLog, Money, Dataset, CommitListener  (dominio del
                    banco: Ledger guarda cuentas/dinero, Bank orquesta la
                    transaccion)
        security/   Tokens, Passwords, Credential, CredentialStore,
                    Authenticator  (JWT + bcrypt)
        api/        RegisterEndpoint, LoginEndpoint, BalanceEndpoint,
                    TransferEndpoint, StatsEndpoint, PanelEndpoint,
                    DashboardEndpoint  (endpoints HTTP)
        cluster/    ReplicaFeed (lider), ReplicaSync (replica), WireCodec
                    (replicacion TCP; una linea JSON por transferencia)
        durable/    Journal, GcsStore, GcsAuth  (durabilidad en GCS REST)
        loadtest/   LoadDriver  (generador de carga y verificador de invariante)
      resources/    dashboard.html y demas recursos estaticos
    test/
      java/mx/ipn/escom/tesoreria/tests/
                    Assert, RunTests + suites (Ledger, Money, RequestParser,
                    WireCodec, Auth, Replay, Checkpoint, JwtCrossNode, Catchup)
                    -- harness Java puro, sin JUnit; ReplConsistency y
                    ReplInversion son pruebas de socket aparte
  material-profesor/                        archivos del profesor (ver abajo)
  deploy/                                   scripts/notas de despliegue de los nodos
```

## material-profesor (NO se regenera)

La carpeta `material-profesor/` contiene archivos entregados por el profesor,
**copiados tal cual** (sin modificar y sin volver a generar):

- `alumnos.csv` -- dataset de cuentas con formato
  `nombre,apellido1,apellido2,saldo` (el `id` se deriva por indice de fila, base
  1) que carga cada nodo.
- `GeneradorRegistros.java` -- generador original del profesor del dataset.
- `nombres.txt`, `apellidos.txt` -- insumos del generador.
- `PROYECTO FINAL_2026_ENERO.pdf` -- enunciado del proyecto.

Estos archivos son la fuente de verdad: **no se regeneran ni se editan**. El
nodo consume `alumnos.csv` directamente (ver `TES_DATASET`).

## Compilar

Se requiere JDK 17 y Maven. Desde la raiz del workspace (`hatziry/`):

```
mvn clean package
```

El plugin `maven-assembly-plugin` genera un fat jar
(`jar-with-dependencies`) con la clase principal
`mx.ipn.escom.tesoreria.app.Node`.

## Correr un nodo

El primer argumento es el puerto HTTP (por defecto `8080`). El rol (lider o
replica) y el resto de la configuracion se toman de variables de entorno con
prefijo `TES_`; un nodo es **lider** cuando `TES_LEADER_HOST` esta vacio o
ausente, y **replica** cuando apunta al host del lider.

Lider:

```
export TES_DATASET=material-profesor/alumnos.csv
export TES_JWT_SECRET=<mismo-secreto-en-los-3-nodos>
export TES_NODE_ID=nodo-1
export TES_REPL_PORT=9090
export TES_BUCKET=<bucket-gcs>
export TES_GCS_KEYFILE=<ruta-key.json>
export TES_WORKERS=8

java -jar target/tesoreria-distribuida-jar-with-dependencies.jar 8080
```

Replica (mismo jar, agregando el host del lider):

```
export TES_LEADER_HOST=<host-del-lider>
# ...resto de TES_* igual; TES_JWT_SECRET debe ser el MISMO valor...

java -jar target/tesoreria-distribuida-jar-with-dependencies.jar 8080
```

Variables `TES_*` principales: `TES_DATASET`, `TES_JWT_SECRET`, `TES_NODE_ID`,
`TES_PEERS`, `TES_LEADER_HOST`, `TES_REPL_PORT` (default `9090`), `TES_BUCKET`,
`TES_GCS_KEYFILE`, `TES_WORKERS`, `TES_REACTORS` (numero de reactores NIO; default
= numero de CPUs, que es el optimo).

## Documentacion relacionada

- [`REQUERIMIENTOS.md`](REQUERIMIENTOS.md) -- requisitos funcionales del sistema.
- [`RESTRICCIONES-Y-ESTILO.md`](RESTRICCIONES-Y-ESTILO.md) -- estandar de codigo,
  dependencias permitidas y restricciones del proyecto.
