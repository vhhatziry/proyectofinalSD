# Setup de Cloud Storage (JSON API) para el log durable

El log durable de transacciones vive en un bucket de Google Cloud Storage y se
accede por su **JSON API REST** con `java.net.http.HttpClient` (sin SDK de Google).
Para autenticar, el nodo lider firma un **JWT con la llave privada de una cuenta de
servicio** (RS256) y lo intercambia por un access token OAuth2 (`durable/GcsAuth`).
Hay que hacer, una sola vez, tres cosas: crear la cuenta de servicio, darle permiso
sobre el bucket y descargar su llave `credentials.json`.

## Prerrequisitos
- Un bucket de GCS (su nombre va en la variable de entorno `TES_BUCKET`).
- `gcloud` autenticado: `gcloud auth login` y `gcloud config set project <PROJECT_ID>`
  (o usar Cloud Shell, que ya viene autenticado).

## Ruta A - Terminal con gcloud (rapida, 3 comandos)

```bash
# 1) Crear la cuenta de servicio
gcloud iam service-accounts create tesoreria-gcs \
  --display-name="Tesoreria log durable"

# 2) Permiso de ESCRITURA sobre el bucket
gcloud storage buckets add-iam-policy-binding gs://<BUCKET> \
  --member="serviceAccount:tesoreria-gcs@<PROJECT_ID>.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"

# 3) Crear y descargar la llave JSON
gcloud iam service-accounts keys create credentials.json \
  --iam-account="tesoreria-gcs@<PROJECT_ID>.iam.gserviceaccount.com"
```

Verificar que la llave quedo registrada:

```bash
gcloud iam service-accounts keys list \
  --iam-account="tesoreria-gcs@<PROJECT_ID>.iam.gserviceaccount.com"
```

## Ruta B - Consola web (equivalente, por clicks)

1. **Crear la cuenta de servicio:** IAM y administracion > Cuentas de servicio >
   Crear cuenta de servicio > nombre > Listo.
2. **Permisos al bucket:** Cloud Storage > Buckets > tu bucket > pestana Permisos >
   Otorgar acceso > en *Entidades nuevas* pega el correo de la SA > Asignar roles >
   Cloud Storage > **Administrador de objetos de Storage** > Guardar.
3. **Descargar la llave:** entra a la SA > pestana Claves > Agregar clave >
   Crear clave nueva > tipo JSON > se descarga `credentials.json`.

## Rol correcto

Usa **`roles/storage.objectAdmin`** (crear + listar + leer). El log SUBE objetos y
ademas los RELEE en el catch-up de arranque en frio, asi que `objectCreator` por si
solo no alcanza.

## Seguridad de la llave

- `credentials.json` **NUNCA** se sube al repositorio. Si llega a GitHub, la llave
  queda comprometida. Ya esta en el `.gitignore`.
- En la VM, sube la llave por separado y apunta `TES_GCS_KEYFILE` a su ruta.

## Variables de entorno relacionadas

| Variable | Valor |
|---|---|
| `TES_BUCKET` | nombre del bucket (solo en el nodo lider) |
| `TES_GCS_KEYFILE` | ruta a `credentials.json` en la VM |
