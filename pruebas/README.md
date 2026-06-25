# Pruebas - Tesoreria Distribuida (Equipo 18)

Suite de verificacion local (sin costo en la nube, salvo la de GCS que es opcional).
Util para ensayar antes de la revision en vivo.

## Correr todo

```
bash pruebas/correr-todo.sh
```

Compila, y corre: tests unitarios, consistencia de replicacion, convergencia ante
inversion de secuencia, cluster local y hardening de endpoints. Las de GCS
(recuperacion en frio y checkpoint de replica) solo corren si defines `TES_BUCKET`
y `TES_GCS_KEYFILE`.

## Pruebas individuales

- `cluster-local.sh` - lider + 2 replicas como procesos locales: replicacion en
  vivo, servir con 1 y 2 replicas caidas, revivir + catch-up por secuencia,
  invariante de dinero constante.
- `endpoints.sh` - tolerancia de `Authorization` (Bearer case-insensitive + espacios),
  `GET /api/accounts/{id}` fuera de rango -> 404, y la matriz de codigos de error de
  `POST /api/transactions/transfer`.
- `gcs-recovery.sh` - requiere `TES_BUCKET` + `TES_GCS_KEYFILE` (opcional
  `GCLOUD_ACCOUNT`, `GCLOUD_PROJECT` para listar/limpiar): journal asincrono por
  lotes -> apagado ordenado que drena -> reinicio que recupera de GCS y reanuda en
  la secuencia correcta sin sobrescribir. Vacia `journal/` al terminar.
- Test de consistencia de replicacion (clase Java, martillea con 8 hilos y compara
  el saldo POR CUENTA lider == replica):

  ```
  mvn -q -DskipTests package
  java -cp target/test-classes:target/tesoreria-distribuida-jar-with-dependencies.jar \
      mx.ipn.escom.tesoreria.tests.ReplConsistency
  ```

## Requisitos

- JDK 17 + Maven, `jq`, y `curl`. Para `gcs-recovery.sh`, `gcloud` autenticado.
- Puertos locales libres: 8080, 8082, 8083, 9090.
