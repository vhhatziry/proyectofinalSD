# Manual de estudio -- Tesoreria Distribuida (Equipo 18)

> **VERSION DE ESTUDIO -- NO ENTREGAR.** Este documento es interno: sirve para
> entender el sistema y poder explicarlo ante el profesor. **No** forma parte
> de la entrega oficial. Puede parecerse a otra documentacion; es intencional,
> su unico fin es estudiar.

Documentacion tecnica en LaTeX del mini banco distribuido en Java puro
(paquete `mx.ipn.escom.tesoreria`). El documento principal incluye 10
capitulos como fragmentos `\input`.

## Estructura

```
docs/
  manual-estudio.tex     documento principal (preambulo, portada, indice, \input)
  compilar.sh            compila a PDF (pdflatex x2)
  README.md              este archivo
  GUIA.md                convenciones para redactar los fragmentos .tex
  PROMPT-AGENTE.md       prompt listo para pasarle al otro agente
  capitulos/
    01-arquitectura.tex            vision general, roles lider/replica, topologia
    02-arranque-config.tex         app/: Node, NodeConfig (TES_*), arranque del jar
    03-servidor-nio.tex            net/: servidor HTTP en NIO puro
    04-nucleo-banco.tex            core/: dinero en centavos, ledger, invariante
    05-seguridad-jwt.tex           security/: JWT, passwords, credenciales
    06-api-rest.tex                api/: endpoints REST
    07-replicacion.tex             cluster/ + ReplicaCheckpoint: reanudacion por secuencia
    08-persistencia-gcs.tex        durable/: bitacora en Cloud Storage y recuperacion
    09-dashboard.tex               dashboard.html + endpoints de monitoreo
    10-carga-pruebas-despliegue.tex  loadtest/, tests/, deploy/
```

## Como compilar

Requiere una distribucion de LaTeX con `pdflatex` (TeX Live o MiKTeX) y los
paquetes `listings`, `xcolor`, `geometry`, `hyperref`, `fancyhdr`, `babel`.

```
cd ~/hatziry/docs
./compilar.sh          # genera manual-estudio.pdf
```

o manualmente:

```
pdflatex manual-estudio.tex
pdflatex manual-estudio.tex   # segunda pasada por el indice
```

## Estado

Scaffolding generado. Cada capitulo trae el esqueleto de secciones y
comentarios `% GUIA:` / `% CITAR:` / `% SNIPPET:` que indican que cubrir y que
archivos del codigo citar. Donde falta prosa hay un marcador `\pendiente{...}`
(sale en rojo en el PDF). El documento **ya compila** con esos marcadores.

**Para completarlo:** pasarle a otro agente el contenido de
[`PROMPT-AGENTE.md`](PROMPT-AGENTE.md) y seguir las convenciones de
[`GUIA.md`](GUIA.md). El trabajo termina cuando no queda ningun `\pendiente`,
el PDF compila limpio y cada componente se cita desde su archivo real.

## Fuente de la verdad

Todo el contenido tecnico debe salir del codigo real bajo
`src/main/java/mx/ipn/escom/tesoreria/` y de los documentos del repo
(`README.md`, `REQUERIMIENTOS.md`, `RESTRICCIONES-Y-ESTILO.md`,
`deploy/RUNBOOK.md`). No inventar metodos, firmas ni APIs.
