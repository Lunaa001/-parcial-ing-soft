# Despliegue en Azure Container Apps

Este repo construye la imagen (Dockerfile multi-stage con runtime Distroless), la escanea con Trivy
y la despliega en Azure Container Apps cada vez que se hace push a `main`
(`.github/workflows/deploy.yml`). También se puede correr a mano desde Actions → *Run workflow*.

## Decisiones

- **Base de datos**: perfil `cloud` con H2 en memoria (`MODE=PostgreSQL`), permitido por la consigna
  si PostgreSQL complica el despliegue. Los datos se pierden al reiniciar el contenedor.
- **Archivos subidos** (`UPLOAD_DIR=/tmp/uploads`, definido en el Dockerfile): también efímeros.
- **Un solo proceso**: el ENTRYPOINT ejecuta `java -jar` directamente, sin shell ni supervisor.
- **Secretos**: nunca en el repo. Se cargan como secretos de GitHub (para el pipeline) y como
  secretos de la Container App (para la aplicación).
- **Acceso al registry**: GitHub sube la imagen con el service principal (`az acr login`). La
  Container App la descarga con el usuario admin del ACR, porque el entorno **Express** no admite
  identidad administrada. Por eso el usuario admin del ACR tiene que quedar habilitado.
- **Health check**: `GET /actuator/health` (público). Métricas de CPU/memoria en
  `/actuator/metrics` (requieren login admin). Trazas por request: header `X-Request-Id` y
  duración en los logs.

## Pipeline (`deploy.yml`)

Un solo job, en este orden:

1. **Verifica los secretos**: si falta alguno (o `ACR_NAME` trae `.azurecr.io`), falla con un mensaje claro.
2. **Login en Azure** con `AZURE_CREDENTIALS` y **login en el ACR** con `az acr login`.
3. **Construye** la imagen con dos tags: el SHA del commit y `latest`.
4. **Escanea** la imagen con Trivy: falla si hay CVEs HIGH o CRITICAL que ya tengan parche. Se
   escanea antes del push, así una imagen vulnerable nunca llega al registry.
5. **Sube** la imagen al ACR.
6. **Despliega** con `az containerapp update --image <acr>/parcial-ing-soft:<sha>`.
7. **Prueba de humo**: espera a que la revisión activa tenga la imagen nueva y esté `Healthy`, y a
   que `/actuator/health` responda `UP` (10 intentos cada 15 segundos).

## Secretos de GitHub

Se cargan en **Settings → Secrets and variables → Actions → New repository secret**. Son 4:

| Nombre | Valor exacto | Ejemplo |
|---|---|---|
| `AZURE_CREDENTIALS` | JSON del service principal (ver abajo) | `{"clientId":"...", ...}` |
| `ACR_NAME` | nombre del Container Registry, **sin** `.azurecr.io`, en minúsculas | `acrperfume45184` |
| `CONTAINER_APP_NAME` | nombre de la Container App | `perfume-api` |
| `RESOURCE_GROUP` | grupo de recursos | `rg-perfume` |

> `ACR_USERNAME` y `ACR_PASSWORD` ya no se usan (el login al ACR se hace con el service principal).
> Se borran de GitHub después del primer run en verde del pipeline nuevo.

### Crear el service principal (`AZURE_CREDENTIALS`)

Rol **Contributor** solo sobre el grupo de recursos. Alcanza para subir imágenes al ACR (el
registry usa el modo de permisos `LegacyRegistryPermissions`) y para actualizar la Container App.

**Git Bash** (importante el `MSYS_NO_PATHCONV=1`, ver "Problema de Git Bash" más abajo):

```bash
SUB=$(az account show --query id -o tsv)
MSYS_NO_PATHCONV=1 az ad sp create-for-rbac \
  --name perfume-gh-actions \
  --role Contributor \
  --scopes /subscriptions/$SUB/resourceGroups/rg-perfume
echo "subscriptionId: $SUB"
```

**PowerShell** (no tiene el problema de las rutas):

```powershell
$SUB = az account show --query id -o tsv
az ad sp create-for-rbac --name perfume-gh-actions --role Contributor `
  --scopes "/subscriptions/$SUB/resourceGroups/rg-perfume"
"subscriptionId: $SUB"
```

**Azure Cloud Shell** (bash en el navegador, tampoco tiene el problema): el mismo comando de Git
Bash, sin `MSYS_NO_PATHCONV=1`.

El comando imprime `appId`, `password` y `tenant`. Con eso se arma el JSON del secreto
`AZURE_CREDENTIALS`, copiado tal cual (comillas dobles, sin coma después del último campo):

```json
{
  "clientId": "<appId>",
  "clientSecret": "<password>",
  "subscriptionId": "<SUB>",
  "tenantId": "<tenant>"
}
```

El `clientSecret` se muestra una sola vez: va directo al secreto de GitHub, nunca a un archivo del repo.

### Si da "Insufficient privileges"

La cuenta no tiene permiso para registrar aplicaciones en Entra ID (pasa en algunas cuentas de
alumno). En ese caso se usa un service principal que ya exista (por ejemplo, el que da el
profesor) y se arma el mismo JSON a mano con sus 4 valores:

- `clientId`, `clientSecret`, `tenantId`: los del service principal.
- `subscriptionId`: el de **nuestra** suscripción (donde está `rg-perfume`), no el del dueño del SP.

Ese service principal necesita el rol Contributor sobre `rg-perfume`; si no lo tiene, el pipeline
falla con `AuthorizationFailed`. Se asigna así (lo puede hacer el dueño de la suscripción):

```bash
MSYS_NO_PATHCONV=1 az role assignment create --assignee <clientId> \
  --role Contributor --scopes /subscriptions/$SUB/resourceGroups/rg-perfume
```

## Secretos y variables de la Container App

| Tipo | Nombre | Qué es |
|---|---|---|
| Secreto (`jwt-secret`) | `JWT_SECRET` | clave para firmar los tokens (mínimo 256 bits). Obligatoria: sin ella la app no arranca |
| Secreto (`groq-key`) | `GROQ_API_KEY` | key de Groq. Obligatoria: sin ella la app no arranca |
| Secreto (`admin-pass`) | `ADMIN_PASSWORD` | contraseña del admin inicial |
| Secreto (lo crea Azure) | registry | contraseña del usuario admin del ACR, para descargar la imagen |
| Variable | `SPRING_PROFILES_ACTIVE` | `cloud` |
| Variable | `ADMIN_USERNAME`, `ADMIN_EMAIL` | datos del admin inicial |
| Variable (opcional) | `WHATSAPP_BUSINESS_NUMBER` | número para el link de pedido |

`UPLOAD_DIR` **no** se carga en la Container App: ya viene en el Dockerfile (`/tmp/uploads`).

## Pasos manuales (una sola vez)

Variables de ejemplo: `RG=rg-perfume`, `LOC=chilecentral`, `ACR=acrperfume<numero>`,
`ENV=perfume-env`, `APP=perfume-api`. En Git Bash, anteponer `MSYS_NO_PATHCONV=1` a cualquier
comando que tenga un argumento que empiece con `/`.

```bash
# 1. Login y grupo de recursos
az login
az group create -n $RG -l $LOC

# 2. Container Registry, con usuario admin (lo usa la Container App para descargar la imagen)
az acr create -g $RG -n $ACR --sku Basic --admin-enabled true

# 3. Entorno (en nuestra suscripción quedó en modo Express) y Container App,
#    con una imagen placeholder hasta el primer push del pipeline
az containerapp env create -n $ENV -g $RG -l $LOC
az containerapp create -n $APP -g $RG --environment $ENV \
  --image mcr.microsoft.com/k8se/quickstart:latest \
  --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 1

# 4. Credenciales del registry para que la app descargue la imagen
az containerapp registry set -n $APP -g $RG --server $ACR.azurecr.io \
  --username $ACR --password "$(az acr credential show -n $ACR --query 'passwords[0].value' -o tsv)"

# 5. Secretos y variables de la aplicación
az containerapp secret set -n $APP -g $RG --secrets \
  jwt-secret="<clave-de-256-bits-o-mas>" groq-key="<tu-key-de-groq>" admin-pass="<contraseña-admin>"
az containerapp update -n $APP -g $RG --set-env-vars \
  SPRING_PROFILES_ACTIVE=cloud JWT_SECRET=secretref:jwt-secret GROQ_API_KEY=secretref:groq-key \
  ADMIN_PASSWORD=secretref:admin-pass ADMIN_USERNAME=admin ADMIN_EMAIL=admin@ejemplo.com

# 6. Service principal y secretos de GitHub: ver "Secretos de GitHub" más arriba
```

## Problema de Git Bash en Windows (`MissingSubscription`)

Git Bash convierte cualquier argumento que empiece con `/` en una ruta de Windows. Por ejemplo,
`--scopes /subscriptions/...` le llega a Azure como `C:/Program Files/Git/subscriptions/...`, y
Azure responde `MissingSubscription`. Se comprobó con un comando de solo lectura:

```bash
az role assignment list --scope /subscriptions/$SUB/resourceGroups/rg-perfume                    # MissingSubscription
MSYS_NO_PATHCONV=1 az role assignment list --scope /subscriptions/$SUB/resourceGroups/rg-perfume # funciona
```

**También rompe los valores de variables de entorno.** `--set-env-vars UPLOAD_DIR=/tmp/uploads`,
cargado desde Git Bash, dejó en la Container App `UPLOAD_DIR=C:/Users/<usuario>/AppData/Local/Temp/uploads`.
Esa ruta no tiene sentido en el contenedor Linux y pisaba la del Dockerfile (la app, que corre
como `nonroot`, no puede crear carpetas dentro de `/app`). Se arregló borrando la variable (`az containerapp update ... --remove-env-vars UPLOAD_DIR`).

Solución: en Git Bash usar siempre `MSYS_NO_PATHCONV=1 az ...` para comandos con rutas que empiezan
con `/`, o usar PowerShell o Azure Cloud Shell, que no tienen este problema.

## Verificar que el contenedor corre

```bash
az containerapp show -n $APP -g $RG --query properties.runningStatus -o tsv
az containerapp revision list -n $APP -g $RG -o table      # imagen y estado (Healthy) de la revisión
az containerapp show -n $APP -g $RG --query properties.configuration.ingress.fqdn -o tsv
curl https://<fqdn>/actuator/health                         # debe devolver {"status":"UP"}
az containerapp logs show -n $APP -g $RG --follow           # logs en vivo (con requestId por request)
```

## Detener e iniciar la aplicación

`az containerapp stop` y `az containerapp start` **no existen** en Azure CLI 2.89 (ni con la
extensión `containerapp` 1.3.0b5: dan "misspelled or not recognized"). Azure sí tiene las
operaciones `start` y `stop` para Container Apps, así que se llaman directo a la API con `az rest`:

```bash
APP_ID=$(az containerapp show -n $APP -g $RG --query id -o tsv)

# Detener (no borra nada: la app queda configurada pero sin ejecutarse)
MSYS_NO_PATHCONV=1 az rest --method post \
  --url "https://management.azure.com${APP_ID}/stop?api-version=2026-07-01"
az containerapp show -n $APP -g $RG --query properties.runningStatus -o tsv   # Stopped

# Iniciar
MSYS_NO_PATHCONV=1 az rest --method post \
  --url "https://management.azure.com${APP_ID}/start?api-version=2026-07-01"
az containerapp show -n $APP -g $RG --query properties.runningStatus -o tsv   # Running
```

Probado: el estado cambia a `Stopped` / `Running` en unos 10 segundos. Con la app detenida,
`/actuator/health` responde 502. Después de iniciarla, las primeras peticiones no responden
mientras arranca la JVM; a los ~30 segundos vuelve `{"status":"UP"}` con HTTP 200.

Si en una versión futura de la CLI aparecen `az containerapp stop/start`, hacen lo mismo:
`az containerapp stop -n $APP -g $RG`.

## Fork o repo nuevo

Los secretos **no se copian** con el fork ni al cambiar de repo. Hay que cargar a mano:

- En el repo nuevo de GitHub: los 4 secretos de la tabla "Secretos de GitHub".
- En la Container App nueva (si también se crea una): los secretos y variables de su tabla, y las
  credenciales del registry (paso 4).

Si falta alguno de GitHub, el primer paso del pipeline lo avisa con su nombre.

## Auditoría de CVEs

- **En el pipeline**: Trivy escanea la imagen completa (jars de la app y base Distroless) en cada
  push. No necesita API key. La action está fijada al commit `ed142fd…` (v0.36.0) y no a un tag,
  porque en 2026 hubo un incidente en el que se modificaron tags de `trivy-action`.
- **A mano** (OWASP Dependency-Check, solo dependencias Java): necesita una API key gratuita de
  NVD (nvd.nist.gov/developers/request-an-api-key), cargada solo como variable de entorno, nunca
  en un archivo. La primera corrida descarga la base de NVD y tarda varios minutos.

  ```powershell
  $env:NVD_API_KEY = "<tu-key>"
  .\gradlew dependencyCheckAnalyze
  ```
  ```bash
  NVD_API_KEY=<tu-key> ./gradlew dependencyCheckAnalyze
  ```
  El reporte queda en `build/reports/dependency-check-report.html`. El analizador OSS Index está
  desactivado porque exige credenciales.

## Estado real del despliegue (lo que se probó)

- Región: `chilecentral` (la política de la suscripción de estudiante solo permite
  newzealandnorth, mexicocentral, southafricanorth, chilecentral y northcentralus).
- Entorno Container Apps **Express**: no admite identidad administrada ni sufijos de revisión (la
  revisión se llama siempre `perfume-api--latest`). El registry usa usuario administrador.
- `az acr build` no funciona en `chilecentral`: la imagen se construye en GitHub Actions y se sube con Docker.
- Health check en producción: `https://<fqdn>/actuator/health` responde `UP`. Justo después de
  actualizar la imagen puede responder 503 mientras arranca la revisión nueva; luego vuelve a 200.
- Spring Boot 4.0.5 tenía 58 dependencias vulnerables (Spring, Tomcat y Spring Security) según
  Dependency-Check; se subió a Spring Boot 4.1.1 y se migró a Jackson 3.

## Limitaciones

- **Datos efímeros**: H2 en memoria, carrito en memoria (`CartStore`) y `/tmp/uploads` se pierden al
  reiniciar. Funciona porque hay una sola réplica (`--max-replicas 1`); con más réplicas cada una
  tendría sus propios datos.
- **Paridad dev/prod**: en la nube se usa H2 y en desarrollo PostgreSQL. Se atenúa con
  `MODE=PostgreSQL` y con las mismas migraciones de Flyway en los dos.
- **Usuario admin del ACR**: tiene que quedar habilitado porque Express no admite identidad
  administrada para descargar la imagen.
