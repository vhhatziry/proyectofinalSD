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
   --command='sudo journalctl -u node.service | grep -E "checkpoint|resuming" | tail -2'
#   -> [checkpoint] restored checkpoint/nodo-2.json at watermark <N>
#   -> [node] replica resuming at sequence <N>
# y converge al lider (mismo lastTxId, mismo totalBalance):
N2=$($GC compute instances describe nodo-2 --zone=$ZONE --format='get(networkInterfaces[0].accessConfigs[0].natIP)')
curl -s http://$N2:8080/api/stats | jq '{nodeId,lastTxId,totalBalance}'
```

## 5. Dashboard

`http://34.67.240.245:8080/` muestra, por nodo: estado, #cuentas, saldo total,
#transferencias, ultima tx, %CPU/%RAM/%Disco, y el conteo de tx en Cloud Storage.
Se auto-actualiza (reactivo, +1.5 pts).

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

## Resultados del ensayo (2026-06-24, jar actual)

Medido desde el generador (e2-standard-4) contra la IP interna del lider, 60s, 128 hilos:

| Escenario | Config            | lecturas | transferencias | score     |
|-----------|-------------------|----------|----------------|-----------|
| 1         | 3 nodos           | 333,451  | 82,915         | 665,111   |
| 2         | lider + 1 replica | 347,927  | 87,249         | 696,923   |
| 3         | solo lider        | 329,730  | 83,133         | 662,262   |
| **Total** |                   |          |                | **~2,024,296** |

- Todos **CONSISTENT** (invariante conservado). Una corrida de 90s que cruzo el
  apagado de una replica tambien quedo CONSISTENT (score 1,024,608): el lider no se
  cae cuando una replica muere bajo carga.
- El throughput esta **limitado por el generador** (e2-standard-4, tope de cuota de
  12 vCPU; loadavg ~85). El lider tiene headroom: con un generador mas grande el
  score sube. Para mas carga, sube el numero de hilos o usa un generador con mas vCPU.

## Notas importantes

- **Apaga las replicas con SIGTERM** (`gcloud compute instances stop` o `systemctl
  stop`), nunca `kill -9`: el apagado graceful dispara el shutdown hook que escribe el
  checkpoint exacto. `node.service` tiene `TimeoutStopSec=120` para no cortar la subida.
- Cuota de CPU del proyecto: **12 vCPU** (4+2+2 del banco + 4 del generador = 12, al
  tope). Si necesitas un generador mas grande, pide aumento de cuota o apaga el
  generador y corre la carga desde otra instancia.
- El `secrets/gcs-key.json` del bucket es la llave del SA; es privada. No la publiques.
