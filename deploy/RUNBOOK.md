# Runbook de la demo en vivo - Tesoreria Distribuida (Equipo 18)

Guion paso a paso para la revision del jueves. La infraestructura YA EXISTE en GCP
(creada con `crear-infra.sh`); normalmente esta **apagada** para no gastar. El dia
de la revision es: **encender -> demostrar -> apagar**.

Todos los `gcloud` van fijados a la cuenta `martinviverosmora@gmail.com` y el
proyecto `project-83c85cfe-096a-4f0e-87d` (ya estan dentro de los scripts). Corre
estos comandos desde una maquina con `gcloud` autenticado (tu workstation o Cloud
Shell con la cuenta de viveros mora).

## Topologia

| Nodo       | Rol     | Maquina        | Notas                                  |
|------------|---------|----------------|----------------------------------------|
| nodo-1     | LIDER   | e2-standard-4  | IP estatica **34.67.240.245** (entrada unica) |
| nodo-2     | REPLICA | e2-standard-2  | catch-up por secuencia + checkpoint    |
| nodo-3     | REPLICA | e2-standard-2  | catch-up por secuencia + checkpoint    |
| generador  | carga   | e2-standard-4  | corre el LoadDriver (no es nodo del banco) |

- **Entrada unica:** `http://34.67.240.245:8080` (clientes + dashboard).
- La IP estatica del lider NO cambia al apagar/encender.

## 0. Encender (manana, ~1-2 min)

```bash
bash deploy/encender.sh        # arranca las 4 VMs y espera a que el lider sirva
# (opcional) arranque pristino lastTxId=0:  bash deploy/reset-estado.sh  ANTES de encender
```

Abre el **dashboard**: `http://34.67.240.245:8080/`

## 1. Los 4 endpoints con JWT (shapes exactos)

```bash
L=34.67.240.245
curl -s -X POST http://$L:8080/api/register -d '{"username":"demo","password":"p"}' -w '\n(register %{http_code})\n'
TOK=$(curl -s -X POST http://$L:8080/api/login -d '{"username":"demo","password":"p"}' | jq -r .token)
curl -s -H "Authorization: Bearer $TOK" http://$L:8080/api/accounts/5      # {"id":5,"propietario":"...","balance":...}
curl -s -X POST http://$L:8080/api/transactions/transfer -H "Authorization: Bearer $TOK" \
     -d '{"sourceAccountId":"1","targetAccountId":"5","amount":"100.00"}' -w '\n(transfer %{http_code})\n'
```

## 2. Invariante de conservacion

```bash
curl -s http://$L:8080/api/stats | jq '{accountCount,totalBalance,lastTxId}'
# totalBalance debe quedar CONSTANTE en 410113948069.86 antes y despues de transferir.
```

## 3. Tolerancia a fallos (apagar 1 y 2 replicas, seguir sirviendo)

```bash
ZONE=us-central1-a; GC="gcloud --account=martinviverosmora@gmail.com --project=project-83c85cfe-096a-4f0e-87d --quiet"
$GC compute instances stop nodo-3 --zone=$ZONE      # cae una replica
curl -s http://$L:8080/api/stats | jq '{role,status,lastTxId}'   # el lider SIGUE sirviendo
$GC compute instances stop nodo-2 --zone=$ZONE      # cae la otra
curl -s http://$L:8080/api/stats | jq '{role,status,lastTxId}'   # el lider SOLO sigue sirviendo
```

## 4. Revivir una replica y verla alcanzar por secuencia (catch-up exacto)

```bash
$GC compute instances start nodo-2 --zone=$ZONE     # revive
# en su log se ve que reanuda desde su secuencia EXACTA (no desde 0):
$GC compute ssh nodo-2 --zone=$ZONE --tunnel-through-iap \
   --command='sudo journalctl -u node.service | grep -E "checkpoint|resuming|Me quede" | tail -3'
#   -> [checkpoint] restored checkpoint/nodo-2.json at watermark <N>
#   -> [node] replica resuming at sequence <N>
#   -> [replica] Me quede en la secuencia <N>, enviame desde <N+1>   (texto LITERAL de la rubrica)
# y converge al lider (mismo lastTxId, mismo totalBalance):
N2=$($GC compute instances describe nodo-2 --zone=$ZONE --format='get(networkInterfaces[0].accessConfigs[0].natIP)')
curl -s http://$N2:8080/api/stats | jq '{nodeId,lastTxId,totalBalance}'
```

## 5. Dashboard

`http://34.67.240.245:8080/` muestra, por nodo: estado, #cuentas, saldo total,
#transferencias, ultima tx, %CPU/%RAM/%Disco, y el conteo de tx en Cloud Storage.
Se auto-actualiza solo: el toggle "En vivo" arranca **encendido por defecto**, asi
que el tablero refresca sin pulsar "Refrescar" (el extra reactivo).

## 6. Generador de carga (80/20, 1 min, 3 escenarios)

```bash
bash deploy/concurso.sh            # corre los 3 escenarios e imprime los scores + total
# (o uno solo a mano, desde el generador, contra la IP INTERNA del lider 10.128.0.16)
```

Cada escenario verifica el invariante (CONSISTENT) e imprime lecturas, transferencias
y `score = transferencias*4 + lecturas`. El total es la suma de los 3.

## 7. Apagar (al terminar)

```bash
bash deploy/apagar.sh              # detiene las VMs (NO borra); pausa el gasto de computo
# o, para borrar todo y no dejar NINGUN costo (discos + IP estatica):
bash deploy/destruir-infra.sh
```

## Throughput (2026-06-24)

### Capacidad REAL del banco (medida con `wrk`, cliente eficiente)

El lider (e2-standard-4, 4 vCPU), JVM caliente, contra la IP interna desde el generador:

| Operacion        | Throughput   | CPU del lider |
|------------------|--------------|---------------|
| Lecturas (GET)   | **~73,700/s**| ~99% (4 cores)|
| Transferencias   | **~43,500/s**| ~99%          |

Esto es lo que el generador del profesor (si es eficiente) extraera. El HTTP server
usa **multi-reactor** (`TES_REACTORS`, default = #vCPU, optimo en 4) para repartir el
I/O entre los 4 cores; con 1 solo reactor el techo era ~33k. Para medirlo tu mismo:

```bash
# en el generador (o cualquier instancia en la nube), contra la IP INTERNA del lider:
TOK=$(curl -s -X POST http://34.67.240.245:8080/api/login -d '{"username":"u","password":"p"}' | jq -r .token)
sudo apt-get install -y wrk
wrk -t4 -c200 -d20s -H "Authorization: Bearer $TOK" http://<IP-INTERNA-lider>:8080/api/accounts/5
```

### Concurso 80/20 con NUESTRO generador (`concurso.sh`)

| Escenario | Config            | score     |
|-----------|-------------------|-----------|
| 1         | 3 nodos           | ~656,000  |
| 2         | lider + 1 replica | ~678,000  |
| 3         | solo lider        | ~718,000  |
| **Total** |                   | **~2.05M** |

Todos **CONSISTENT**. OJO: este total esta **limitado por NUESTRO LoadDriver**, no por
el banco. El LoadDriver es un cliente Java SINCRONO (un hilo bloquea por request); en un
generador de 4 vCPU topa en ~7k ops/s aunque el banco sirva 73k. Por eso el numero de
`wrk` (arriba) es el real. La tolerancia a fallos tambien quedo probada: una corrida que
cruzo el apagado de una replica termino CONSISTENT y el lider nunca se cayo.

## Notas importantes

- **Apaga las replicas con SIGTERM** (`gcloud compute instances stop` o `systemctl
  stop`), nunca `kill -9`: el apagado graceful dispara el shutdown hook que escribe el
  checkpoint exacto. `node.service` tiene `TimeoutStopSec=120` para no cortar la subida.
- Cuota de CPU del proyecto: **12 vCPU** (4+2+2 del banco + 4 del generador = 12, al
  tope). Si necesitas un generador mas grande, pide aumento de cuota o apaga el
  generador y corre la carga desde otra instancia.
- El `secrets/gcs-key.json` del bucket es la llave del SA; es privada. No la publiques.
