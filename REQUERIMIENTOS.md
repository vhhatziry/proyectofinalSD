# Requerimientos del Proyecto Final — Mini Banco Concurrente Distribuido en la Nube

**Materia:** Sistemas Distribuidos — ESCOM-IPN
**Profesor:** Ukranio Coronilla
**Fuente del contrato:** `PROYECTO FINAL_2026_ENERO.pdf` (en `material-profesor/`)
**Revision en vivo:** jueves 25 de junio, 8:00 AM (checklist; ver seccion 11)

Este documento es el **contrato** del proyecto. Define que debe construirse, con que reglas y como se evalua, de forma autocontenida para implementarlo desde cero en un workspace limpio. Lo que aqui se marca como **OBLIGATORIO** debe cumplirse al pie de la letra (es identico entre todos los equipos y se prueba con la misma herramienta del profesor). Lo marcado como **LIBRE** es decision de diseno del equipo.

---

## 1. Objetivo

Construir un **mini banco** que vive como cluster distribuido de **3 nodos** en Google Cloud. El sistema mantiene **miles de cuentas** en memoria y soporta una sola operacion de negocio en las pruebas: **transferencias entre cuentas**. Debe:

- Exponer una API REST con autenticacion JWT (endpoints exactos en seccion 6).
- Conservar el invariante de dinero (seccion 3) bajo concurrencia.
- Tolerar la caida de nodos sin perder servicio ni datos (seccion 5).
- Persistir cada transaccion de forma durable en Cloud Storage (seccion 5.3).
- Ofrecer un dashboard de monitoreo (seccion 7).
- Ganar el concurso de carga maximizando el puntaje (seccion 8).

**Backend:** 100% Java, "Java puro como en clase" (sin Spring Boot u otros frameworks de aplicacion; libreria HTTP estandar del JDK + libs minimas para JWT/JSON). El **dashboard** puede usar JavaScript en el navegador.

---

## 2. Dataset

- Archivo: `material-profesor/alumnos.csv`, provisto por el profesor e **IDENTICO entre todos los equipos** (semilla fija). Todos arrancan con la misma base inicial.
- **Sin cabecera.** Cada linea es una cuenta. Formato:

  ```
  nombre,apellido1,apellido2,saldo
  ```

  Ejemplo real (primera linea): `ABRAHAM,AGUILAR,AGUILAR,361803.11`

- **820,000 cuentas** en total.
- Campos derivados por cuenta:

  | Campo        | Origen                                    |
  |--------------|-------------------------------------------|
  | `id`         | indice de fila (ver nota de base abajo)   |
  | `propietario`| `nombre + " " + apellido1 + " " + apellido2` (ej. `ABRAHAM AGUILAR AGUILAR`) |
  | `balance`    | columna `saldo`, en pesos con 2 decimales (se almacena en centavos, ver seccion 3) |

- **Base del id (LIBRE pero a CONFIRMAR):** el material del profesor NO define el id; el generador solo produce las 4 columnas. La opcion natural es **base 1 en orden de fila** (`ABRAHAM AGUILAR AGUILAR` = id 1). RIESGO: el generador de carga del concurso (que puede ser el del profesor o de otro equipo) podria indexar desde otra base. Hacer la base un **OFFSET constante configurable** (default 1) y CONFIRMAR la convencion antes del concurso.

- **Carga:** leer el CSV completo a memoria al arrancar el nodo (no saltar la primera linea). No hay SGBD: la "base de datos" es la estructura en memoria.

---

## 3. Reglas de dinero e invariante de conservacion (CRITICO)

- En las pruebas, la **unica operacion** es la transferencia entre dos cuentas. No hay depositos ni retiros externos.
- Por lo tanto, **la suma de TODOS los saldos debe permanecer CONSTANTE** en todo momento. Este es el **invariante de conservacion** y es la propiedad que el concurso verifica al final.
- **El dinero se maneja SIEMPRE en CENTAVOS como `long`. NUNCA `double`/`float`.** Las operaciones con punto flotante introducen errores de redondeo que rompen el invariante.
  - Conversion al cargar: `centavos = round(saldoPesos * 100)`.
  - Conversion al exponer en JSON: `pesos = centavos / 100.0` formateado con 2 decimales.
  - Una transferencia es: `origen.centavos -= montoCentavos; destino.centavos += montoCentavos;` de forma **atomica** (todo o nada) y sin condiciones de carrera.
- Toda transferencia debe ser **atomica y aislada** bajo concurrencia (p. ej. bloqueo por cuenta tomado en **orden consistente por id** para evitar interbloqueos). Validar saldo suficiente antes de aplicar; si no alcanza, rechazar sin modificar nada.

---

## 4. Restricciones de plataforma

- **Sin SGBD (OBLIGATORIO):** PROHIBIDO Cloud SQL y cualquier sistema gestor de base de datos, incluidos los **embebidos** (SQLite, H2, Derby, etc.). La base vive **en memoria**. La unica persistencia/durabilidad permitida es **Cloud Storage** (seccion 5.3).
- **Solo instancias serie E2 (OBLIGATORIO):** exactamente **2x `e2-standard-2` + 1x `e2-standard-4`**. No usar otro tipo o serie de instancia para los nodos del banco.
- **Backend en Java puro** (ver seccion 1).

---

## 5. Topologia, tolerancia a fallos y durabilidad

### 5.1 Topologia lider/replicas

| Nodo    | Rol     | Maquina         | Responsabilidad                                              |
|---------|---------|-----------------|-------------------------------------------------------------|
| nodo-1  | LIDER   | `e2-standard-4` | Recibe **TODAS** las lecturas y escrituras. IP estatica compartida (punto de entrada unico). |
| nodo-2  | REPLICA | `e2-standard-2` | Copia del estado; se mantiene al dia con el lider.          |
| nodo-3  | REPLICA | `e2-standard-2` | Copia del estado; se mantiene al dia con el lider.          |

- **Todas** las peticiones (lecturas Y escrituras) llegan al **nodo-1 (lider)** mediante una **IP estatica compartida**. Las replicas no atienden trafico de clientes durante la prueba; existen para tolerancia a fallos y para el dashboard.

### 5.2 Tolerancia a fallos con catch-up por numero de secuencia

- Apagar **una** replica: el sistema **sigue funcionando**.
- Apagar **la otra** replica: el sistema **sigue funcionando** (solo con el lider).
- Cada transaccion tiene un **numero de secuencia** monotono (1, 2, 3, ...) asignado por el lider.
- **Catch-up al revivir una replica (OBLIGATORIO):** cuando una replica vuelve a encenderse, debe **ALCANZAR al lider** pidiendo solo lo que le falta por su numero de secuencia: *"voy en la tx 4500, dame de la 4501 en adelante"*. El lider le envia las transacciones faltantes y la replica las aplica en orden.
- **PROHIBIDO** borrar todo el estado de la replica y pedir la base completa de nuevo. La sincronizacion es **incremental** por secuencia.
- **Mecanismo de transporte lider->replica: LIBRE** (stream directo, Pub/Sub, etc.).

### 5.3 Log durable en Cloud Storage

- **Cada transaccion**, con su numero de secuencia, se persiste de forma durable en **Cloud Storage**.
- Proposito: recuperar el **estado COMPLETO** si caen **TODOS** los nodos (el estado en memoria se pierde, pero el log durable permite reconstruirlo reaplicando las transacciones en orden de secuencia sobre la base inicial del CSV).
- **Acceso a Cloud Storage via su API REST (JSON API)** desde Java.
  - Nota tecnica: Cloud Storage **no soporta append**; cada transaccion se guarda como objeto independiente (p. ej. `transactions/{seq}.json`) o se componen objetos. Diseno LIBRE.
- El PDF **sugiere Pub/Sub** como opcion para el flujo, pero el diseno es **LIBRE**: *"Se recomienda el uso de Pub/Sub pero cada equipo puede disenar su solucion como desee"*. Lo **OBLIGATORIO** es que el log durable viva en Cloud Storage.
- **Recuperacion en frio:** al arrancar el lider tras una caida total, debe reconstruir el estado desde el CSV + el log de Cloud Storage **antes** de servir trafico, de modo que no quede en secuencia 0.

---

## 6. API REST — Endpoints EXACTOS

Mismos **paths y metodos** para todos los equipos. Usar **codigos HTTP correctos** (200, 201, 400, 401, 404, 409, ...). JWT viaja en el header `Authorization: Bearer <token>`.

| Metodo | Path                            | Auth | Descripcion                                  |
|--------|---------------------------------|------|----------------------------------------------|
| POST   | `/api/register`                 | No   | Registro de usuario (credencial de prueba).  |
| POST   | `/api/login`                    | No   | Devuelve un **token JWT**.                   |
| GET    | `/api/accounts/{id}`            | Si   | Consulta de cuenta (shape obligatorio abajo).|
| POST   | `/api/transactions/transfer`    | Si   | Transferencia (shape obligatorio abajo).     |

### 6.1 `POST /api/register`
- Body: como en clase, p. ej. `{"username":"...","password":"..."}`.
- Crea la credencial de usuario para autenticarse. La autenticacion es una **compuerta** independiente de las 820k cuentas del banco (el usuario JWT no es lo mismo que una cuenta).
- Respuesta tipica: **201 Created**.

### 6.2 `POST /api/login`
- Body: las mismas credenciales (`username` + `password`).
- Respuesta: **200 OK** con un **token JWT**. Credenciales invalidas: **401 Unauthorized**.
- El **secreto e issuer del JWT deben ser compartidos por los 3 nodos** para que un token emitido por el lider sea valido en las replicas.

### 6.3 `GET /api/accounts/{id}` (requiere JWT)
- **Shape de respuesta OBLIGATORIO** (200 OK):

  ```json
  {"id":125,"propietario":"JUAN MOLINAR HERNANDEZ","balance":15750.25}
  ```

  - `id`: **numerico** (no string).
  - `propietario`: nombre completo en mayusculas (`nombre apellido1 apellido2`).
  - `balance`: **decimal** en pesos (centavos / 100, con 2 decimales). NO es el entero de centavos.
- Sin token o token invalido: **401**. Cuenta inexistente: **404**.

### 6.4 `POST /api/transactions/transfer` (requiere JWT)
- **Shape de body OBLIGATORIO:**

  ```json
  {"sourceAccountId":"123","targetAccountId":"456","amount":200.00}
  ```

  - `sourceAccountId` y `targetAccountId`: **strings** (aunque representen numeros).
  - `amount`: **decimal** en pesos (convertir a centavos `long` internamente: `montoCentavos = round(amount * 100)`).
- Comportamiento:
  - Aplica la transferencia de forma atomica conservando el invariante (seccion 3).
  - Exito: **200 OK** (o 201).
  - Body malformado / monto invalido (<= 0): **400**.
  - Sin/JWT invalido: **401**.
  - Cuenta origen o destino inexistente: **404**.
  - Saldo insuficiente (o conflicto de concurrencia, segun diseno): **409** (o 400). Codigo LIBRE pero coherente.

> **OJO al JSON asimetrico:** `GET /api/accounts` devuelve `id` **numerico** + campos `propietario`/`balance`; el body de `transfer` usa ids **string** + `amount`. Son dos formatos distintos; serializar/parsear cada uno como se especifica.

---

## 7. Dashboard

- **Una sola pagina** (single-page) que muestre el estado del cluster.
- **Por CADA nodo** (nodo-1, nodo-2, nodo-3), mostrar:

  | Campo                       | Significado                                  |
  |-----------------------------|----------------------------------------------|
  | Estado                      | vivo / caido / alcanzable                     |
  | Numero de cuentas           | total de cuentas en memoria                   |
  | Saldo total                 | suma de todos los saldos (debe ser constante) |
  | Numero de transferencias    | transferencias aplicadas en ese nodo          |
  | Id de la ultima transaccion | numero de secuencia de la ultima tx aplicada  |
  | %CPU                        | uso de CPU del nodo                           |
  | %RAM                        | uso de memoria del nodo                        |
  | %Disco                      | uso de disco del nodo                          |

- **Ademas (global o del lider):** **numero de transacciones almacenadas en Cloud Storage**.
- **Boton "Refrescar"** que actualiza los datos. **+1.5 puntos** si en vez del boton el dashboard es **reactivo / en tiempo real** (auto-actualizacion).

---

## 8. Concurso (generador de carga)

- **Programa Java** independiente que corre **1 minuto** contra el cluster. Se ejecuta en **cualquier instancia** en la nube (tipo y serie **LIBRES** para el generador; las restricciones E2 son solo para los nodos del banco).
- Mezcla de carga: **80% lecturas de saldo** (`GET /api/accounts/{id}`) y **20% transferencias** (`POST /api/transactions/transfer`).
- Al terminar:
  1. La base debe quedar **consistente** (invariante de conservacion intacto).
  2. Si detecta **inconsistencia**: indicar **el registro** afectado.
  3. Si todo cuadra: imprimir **numero de transferencias exitosas** y **numero de lecturas exitosas**.

### 8.1 Formula de puntaje

```
puntaje_escenario = (transferencias_exitosas * 4) + lecturas_exitosas
```

Las transferencias valen **4x** una lectura.

### 8.2 Tres escenarios (se suman)

| Escenario | Nodos encendidos | Que prueba                          |
|-----------|------------------|-------------------------------------|
| 1         | nodo-1, nodo-2, nodo-3 | Cluster completo               |
| 2         | nodo-1, nodo-2         | Tolerancia a 1 replica caida   |
| 3         | solo nodo-1           | Solo lider                      |

```
puntaje_total = puntaje_escenario_1 + puntaje_escenario_2 + puntaje_escenario_3
```

> El generador de carga es tambien la prueba viva del invariante: si tras un minuto de 80/20 la suma de saldos cambia, el sistema esta mal.

---

## 9. Resumen de obligatorio vs libre

| Aspecto                                  | Estado     |
|------------------------------------------|------------|
| Dataset `alumnos.csv` (identico)         | OBLIGATORIO|
| Dinero en centavos `long`, invariante    | OBLIGATORIO|
| Sin SGBD; base en memoria                | OBLIGATORIO|
| Solo E2 (2x std-2 + 1x std-4)            | OBLIGATORIO|
| Lider unico recibe todo (IP estatica)    | OBLIGATORIO|
| Catch-up incremental por secuencia       | OBLIGATORIO|
| Log durable en Cloud Storage (JSON API)  | OBLIGATORIO|
| 4 endpoints exactos + shapes JSON        | OBLIGATORIO|
| JWT en `Authorization: Bearer`           | OBLIGATORIO|
| Codigos HTTP correctos                   | OBLIGATORIO|
| Dashboard 1 pagina con campos por nodo   | OBLIGATORIO|
| Generador 80/20, 1 min, 3 escenarios     | OBLIGATORIO|
| Backend Java puro (sin frameworks)       | OBLIGATORIO|
| Base del id de cuenta (default 1)        | LIBRE (confirmar) |
| Transporte lider->replica (TCP/PubSub)   | LIBRE      |
| Estructura del log en GCS                | LIBRE      |
| Dashboard reactivo vs boton              | LIBRE (+1.5 si reactivo) |
| Maquina del generador de carga           | LIBRE      |

---

## 10. Lista de construccion (desde cero)

1. Cargar `alumnos.csv` (820k cuentas) en memoria; id por orden de fila (OFFSET 1); saldo a centavos `long`.
2. Servidor HTTP en Java puro con los 4 endpoints y los shapes JSON exactos.
3. Auth: `register`/`login` con JWT (HMAC, secreto compartido entre nodos).
4. Transferencia atomica con bloqueo ordenado por id; validar saldo; conservar invariante.
5. Numero de secuencia monotono por transaccion.
6. Replicacion lider->replica con catch-up incremental por secuencia (`RESUME N`).
7. Log durable en Cloud Storage via JSON API; un objeto por transaccion.
8. Recuperacion en frio: reconstruir desde CSV + log de GCS antes de servir.
9. Dashboard de una pagina con todos los campos por nodo + tx en GCS (reactivo para +1.5).
10. Generador de carga 80/20, 1 minuto, verificacion de invariante, salida de conteos y puntaje; correrlo en los 3 escenarios.
11. Desplegar: 3 VMs E2, IP estatica en el lider, firewall, secreto JWT compartido.

---

## 11. Condiciones de evaluacion

- **Revision EN VIVO con checklist:** jueves **25 de junio, 8:00 AM**. Se demuestra el sistema corriendo (no es entrega de codigo a ciegas): cada punto del checklist debe poder mostrarse funcionando en el momento.
- Tener listo para demostrar en vivo: los 4 endpoints con JWT y los shapes correctos; una transferencia que conserva el invariante; apagar una y dos replicas y seguir operando; revivir una replica y verla alcanzar al lider por secuencia; el dashboard con los campos por nodo y el conteo de GCS; el generador de carga corriendo 1 minuto y reportando sin inconsistencias en los 3 escenarios.
- **Plagio detectado = 0 para AMBOS equipos.** El codigo y los artefactos deben ser propios del equipo; no compartir implementacion con otros equipos.
