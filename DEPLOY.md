# Despliegue en Azure Container Apps

Este repo construye la imagen (Dockerfile multi-stage con runtime Distroless), la escanea con Trivy
y la despliega en Azure Container Apps cada vez que se hace push a `main`
(`.github/workflows/deploy.yml`). Para no generar costos, al terminar **detiene la app y borra el
Container Registry**, que es lo único que cobra por existir (ver "Costos y limpieza final"). Para
dejarla prendida, por ejemplo para presentar, está el workflow **Levantar**; para apagarla, **Apagar**.

## Decisiones

- **Base de datos**: perfil `cloud` con H2 en memoria (`MODE=PostgreSQL`), permitido por la consigna
  si PostgreSQL complica el despliegue. Los datos se pierden al reiniciar el contenedor.
- **Archivos subidos** (`UPLOAD_DIR=/tmp/uploads`, definido en el Dockerfile): también efímeros.
- **Un solo proceso**: el ENTRYPOINT ejecuta `java -jar` directamente, sin shell ni supervisor.
- **Secretos**: nunca en el repo. Se cargan como secretos de GitHub (para el pipeline) y como
  secretos de la Container App (para la aplicación).
- **Acceso al registry**: GitHub sube la imagen con el service principal (`az acr login`). La
  Container App la descarga con el usuario admin del ACR, porque el entorno **Express** no admite
  identidad administrada. Como el ACR se recrea en cada levantada, su contraseña admin cambia: el
  pipeline la lee (`az acr credential show`) y la vuelve a cargar en la app (`registry set`).
- **ACR efímero**: el ACR existe solo mientras la app está levantada. La Container App, su
  configuración (secretos y variables) y el resource group **nunca** se borran.
- **Health check**: `GET /actuator/health` (público). Métricas de CPU/memoria en
  `/actuator/metrics` (requieren login admin). Trazas por request: header `X-Request-Id` y
  duración en los logs.

## Ubicación de la configuración

Las clases de configuración de Spring están en `src/main/java/com/iaperfumeadvisor/config/`
(paquete `com.iaperfumeadvisor.config`), al mismo nivel que `controller`, `service`, etc.:
`SecurityConfig`, `CorsConfig`, `WebMvcConfig`, `RequestTraceFilter`, `AdminUserInitializer` y
`GroqWarmupInitializer`. Antes estaban en `controller/config/` con paquetes mezclados.

Los valores (claves, contraseñas, URLs) no están en el código: salen de variables de entorno.
La lista completa está en `.env.example`. El perfil de desarrollo local tiene su plantilla en
`src/main/resources/application-dev.properties.example`; el archivo real está en `.gitignore`.

## Workflows de GitHub Actions

| Workflow | Cuándo corre | Qué hace | Cómo queda |
|---|---|---|---|
| `deploy.yml` (CI/CD) | push a `main` (salvo si solo cambian `.md`, `ScriptAz.java` o `.env.example`, que no entran en la imagen) o *Run workflow* | build → Trivy → crea el ACR → push → deploy → prueba de humo → **apaga** | app detenida, ACR borrado |
| `levantar.yml` (**Levantar**) | solo manual (*Run workflow*) | lo mismo, pero **no apaga** | app prendida, ACR existiendo |
| `apagar.yml` (**Apagar**) | solo manual | detiene la app, verifica `Stopped` y borra el ACR (si ya no existe, no falla) | app detenida, ACR borrado |
| `versioning.yml` | push a `main` | crea el tag `vX.Y.Z` | — |

Los tres primeros usan la misma lógica, que está en `ciclo-azure.yml` (workflow reutilizable con
tres interruptores: construir, desplegar y apagar), y comparten el grupo de `concurrency`
`azure-perfume`: nunca corren a la vez, así "Apagar" no puede borrar el ACR en medio de un deploy.

### Pasos del ciclo (`ciclo-azure.yml`)

1. **Verifica los secretos**: si falta alguno (o `ACR_NAME` trae `.azurecr.io`), falla con un mensaje claro.
2. **Login en Azure** con `AZURE_CREDENTIALS`.
3. **Construye** la imagen con dos tags (el SHA del commit y `latest`) y etiquetas OCI: título,
   versión, fecha, commit, repo y descripción (`docker inspect` las muestra).
4. **Escanea** la imagen con Trivy: falla si hay CVEs HIGH o CRITICAL que ya tengan parche. El
   build y Trivy no necesitan el ACR, así que si Trivy encuentra algo **el ACR ni se crea**.
5. **Crea el ACR** si no existe: `rg-perfume`, misma región, Basic, usuario admin habilitado. El
   nombre es global en Azure y recién borrado puede tardar en liberarse: reintenta hasta 10 minutos.
6. **Sube** la imagen al ACR (`az acr login` + `docker push`).
7. **Credenciales del registry**: lee la contraseña admin del ACR, la oculta del log
   (`::add-mask::`) y la carga en la app con `az containerapp registry set`.
8. **Despliega**: `az containerapp update --image <acr>/parcial-ing-soft:<sha>` y, si la app estaba
   detenida, la inicia (`az rest .../start`). Va en ese orden (**update → start**) porque si se
   prendiera antes, intentaría bajar la imagen anterior, que no existe en el ACR recreado. Si Azure
   no aceptara el update con la app detenida, el paso usa start → update y lo avisa con un warning;
   el orden usado queda en el resumen del run.
9. **Prueba de humo**: espera a que la revisión activa tenga la imagen nueva y esté `Healthy`, y a
   que `/actuator/health` responda `UP` (10 intentos cada 15 segundos).
10. **Apaga** (solo `deploy.yml` y "Apagar"): detiene la app, verifica `runningStatus=Stopped` y
    borra el ACR. Si hubo deploy, solo apaga si la prueba de humo pasó: si falló, la app queda
    prendida para revisar los logs, y después hay que correr "Apagar".
11. **Limpieza**: si algo falla después de crear el ACR y antes de tocar la app (por ejemplo el
    push), borra ese ACR recién creado, porque no sirve para nada y cobra.
12. **Logout de Azure** (`az logout`), siempre, aunque haya fallado un paso anterior.

No usa `azure/container-apps-deploy-action` (como el proyecto de referencia) porque esa action
crea el entorno en otra región y modo; el nuestro es **Express en `chilecentral`**, y con
`az containerapp update` se actualiza la app existente sin tocar el entorno.

### Controles por mensaje de commit: `/notdeploy` y `/keeprunning`

Solo cuentan si están en la **primera línea** del mensaje del commit. Así, un commit que *describe*
estos controles en el cuerpo no los activa.

| Primera línea del commit contiene | Construye, escanea y sube | Despliega | Apaga al final |
|---|---|---|---|
| (nada especial) | sí | sí | **sí** (app detenida, ACR borrado) |
| `/keeprunning` | sí | sí | **no** (queda prendida, como "Levantar") |
| `/notdeploy` | sí | **no** | **no**: el ACR queda existiendo (y cobrando) hasta correr "Apagar" |

Ejemplo: `git commit -m "fix: corregir validacion /keeprunning"`. Con `/notdeploy` solo se valida
que el build y Trivy pasen: la imagen subida se pierde en el próximo "Apagar".

El mensaje del commit se pasa al script como variable de entorno (`COMMIT_MSG`), nunca
interpolado: si alguien escribiera `$(comando)` en un commit, no se ejecuta.

### Por qué no hay `/deploy`

En la referencia, `/deploy` redespliega la última imagen guardada en el registry sin construir.
Acá el ACR se borra entero al apagar, así que **no quedan imágenes guardadas** para redesplegar:
cada levantada construye la imagen de nuevo desde el código. Por el mismo motivo no hace falta
limpiar imágenes viejas.

## Versionado automático (`versioning.yml`)

Copiado tal cual del proyecto de referencia. En cada push a `main` crea un tag `vX.Y.Z` según el
prefijo del commit ([Conventional Commits](https://www.conventionalcommits.org/)):

| Prefijo del commit | Cambio de versión | Ejemplo |
|---|---|---|
| `feat!:` o `BREAKING CHANGE:` en el cuerpo | major: v1.2.3 → **v2.0.0** | cambio incompatible de la API |
| `feat:` / `feat(scope):` | minor: v1.2.3 → v1.**3**.0 | funcionalidad nueva |
| `fix:` / `refactor:` | patch: v1.2.3 → v1.2.**4** | corrección |
| `docs:`, `chore:`, `ci:` u otro | sin tag | |

Si todavía no hay ningún tag, parte de `v0.0.0`. Los tags se ven en GitHub → *Tags* o con
`git fetch --tags && git tag`.

## Correr en local con Docker Compose

```bash
cp .env.example .env      # completar JWT_SECRET, GROQ_API_KEY y ADMIN_PASSWORD
docker compose up --build # construye la imagen (mismo Dockerfile que el pipeline) y la levanta
curl http://localhost:8080/actuator/health
docker compose logs -f    # logs
docker compose down       # frenar y borrar el contenedor
```

`docker-compose.yml` levanta un solo servicio (`perfume-api`) en el puerto 8080 con el perfil
`cloud` (H2 en memoria) y las variables del `.env`. Ojo: `env_file: .env` pasa **todas** las
variables del archivo al contenedor, incluidas las de `ScriptAz.java` (credenciales del service
principal). No es un problema en tu máquina, pero no conviene compartir ese contenedor ni su
`docker inspect`.

## Script de gestión (`ScriptAz.java`)

Programa de consola en **Java** (un solo archivo, sin dependencias) que porta el `script_az.py`
del proyecto de referencia, para manejar el ciclo de vida sin recordar los comandos de `az`.
Necesita JDK 25, Docker Desktop, Azure CLI y un `.env` con las 4 credenciales del service
principal (`AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`;
ver `.env.example`). No se compila con Gradle ni entra en la imagen: Java ejecuta el archivo directo.

```bash
java ScriptAz.java             # usa el .env de la carpeta
java ScriptAz.java .env.prod   # otro archivo
```

Al arrancar verifica Docker y Azure CLI, hace `az login` con el service principal y lista tus
recursos (resource group, ACR, entorno y Container App) para que elijas con un número. Después
pregunta nombre de imagen (`parcial-ing-soft`), tag y puerto (`8080`).

| Opción | Qué hace | Comandos que usa |
|---|---|---|
| **1** Construir y subir | build local `linux/amd64` con etiquetas OCI, push (tag + `latest`) y verifica que el tag esté en el ACR | `docker build`, `az acr login`, `docker push`, `az acr repository show-tags` |
| **2** Deploy | si la app no existe la crea; si existe carga las credenciales admin del ACR y actualiza imagen, CPU, memoria y réplicas. Si estaba detenida la inicia. Muestra la URL | `az acr credential show`, `az containerapp registry set`, `az containerapp create/update` |
| **3a** Parar | detiene la app (no consume créditos) y espera `Stopped` | `az rest .../stop?api-version=2026-07-01` |
| **3b** Iniciar | la vuelve a levantar y espera `Running` (necesita el ACR: al iniciar baja la imagen) | `az rest .../start?api-version=2026-07-01` |
| **3c** Listar | tabla con nombre, estado, CPU, memoria, réplicas y URL de las apps del grupo | `az containerapp list` |
| **3d** Eliminar | borra una app (pide confirmación; es irreversible) | `az containerapp delete` |
| **4** Pull | baja la imagen del ACR y ofrece correrla en local (con `--env-file .env`) | `docker pull`, `docker run` |
| **5** Logs | logs de la consola; **Enter** vuelve al menú. En Express los lee de Log Analytics cada 10 s (llegan con unos minutos de demora) | `az containerapp logs show --follow` o, en Express, `az rest` a la API de Log Analytics |
| **6** Reconfigurar | vuelve a elegir los recursos | `az group/acr/containerapp list` |
| **0** Salir | ofrece cerrar la sesión de Azure CLI | `az logout` |

**Opciones que necesitan el ACR: 1 (build/push), 2 (deploy), 3b (iniciar) y 4 (pull).** Como el
ACR solo existe mientras la app está levantada, antes de hacer nada verifican que exista. Si no
existe, muestran: *"El ACR no existe: está apagado para no generar costos. Corré el workflow
'Levantar'..."*. Las opciones 3a, 3c, 3d, 5 y 6 funcionan sin el ACR.

Diferencias con el `script_az.py` original:

- Está en Java, como el resto del proyecto.
- El deploy (opción 2) usa las **credenciales admin del ACR**, igual que el pipeline, y no las del
  service principal. El secreto del service principal vence (el nuestro, el 2027-10-06) y tiene
  permisos sobre todo el resource group; el usuario admin solo sirve para el registry y no vence.
- Puerto por defecto 8080 (no 3000), imagen `parcial-ing-soft` y api-version `2026-07-01` en
  parar/iniciar (la que se probó con nuestra app).
- En los logs se vuelve al menú con **Enter** y no con Ctrl+C: en Java, Ctrl+C cierra todo el
  programa.
- **Logs en Express:** `az containerapp logs show` falla en nuestro entorno con
  `KeyError: 'eventStreamEndpoint'` (Express no da endpoint de streaming en vivo). Si la app no
  tiene ese endpoint, la opción 5 consulta la tabla `ContainerAppConsoleLogs_CL` del Log Analytics
  del entorno cada 10 segundos, con la API REST (`az rest`, sin extensiones). Muestra las últimas
  30 líneas de los últimos 15 minutos y después las nuevas a medida que llegan, con unos minutos
  de demora.
- En el pull, `docker run` agrega `--env-file .env`: nuestra app no arranca sin `JWT_SECRET` y
  `GROQ_API_KEY`.

**Ojo con la opción 0:** `az logout` cierra la sesión de Azure CLI de la máquina, también la tuya
personal si es la misma. Además, el login del script reemplaza la cuenta activa de `az`. Para no
pisar tu sesión, correrlo con una carpeta de sesión aparte:
`AZURE_CONFIG_DIR=$HOME/.azure-script java ScriptAz.java` (PowerShell:
`$env:AZURE_CONFIG_DIR="$HOME\.azure-script"; java ScriptAz.java`).

## Secretos de GitHub

Se cargan en **Settings → Secrets and variables → Actions → New repository secret**. Son 4:

| Nombre | Valor exacto | Ejemplo |
|---|---|---|
| `AZURE_CREDENTIALS` | JSON del service principal (ver abajo) | `{"clientId":"...", ...}` |
| `ACR_NAME` | nombre del Container Registry, **sin** `.azurecr.io`, en minúsculas | `acrperfume45184` |
| `CONTAINER_APP_NAME` | nombre de la Container App | `perfume-api` |
| `RESOURCE_GROUP` | grupo de recursos | `rg-perfume` |

> No hacen falta `ACR_USERNAME` ni `ACR_PASSWORD`: el login al ACR se hace con el service principal.
> Se usaban en una versión anterior del pipeline y se borraron de GitHub.

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

# 2. Container Registry: NO hace falta crearlo a mano. Lo crea el pipeline (o "Levantar") cuando
#    lo necesita y lo borra al apagar. A mano: az acr create -g $RG -n $ACR --sku Basic --admin-enabled true

# 3. Entorno (en nuestra suscripción quedó en modo Express) y Container App,
#    con una imagen placeholder hasta el primer push del pipeline
az containerapp env create -n $ENV -g $RG -l $LOC
az containerapp create -n $APP -g $RG --environment $ENV \
  --image mcr.microsoft.com/k8se/quickstart:latest \
  --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 1

# 4. Credenciales del registry: las carga el pipeline en cada deploy (cambian al recrear el ACR).
#    A mano, con el ACR existiendo:
#    az containerapp registry set -n $APP -g $RG --server $ACR.azurecr.io \
#      --username $ACR --password "$(az acr credential show -n $ACR --query 'passwords[0].value' -o tsv)"

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
```

**Logs:** `az containerapp logs show` **no funciona en el entorno Express** (falla con
`KeyError: 'eventStreamEndpoint'`). Los logs de la app se guardan en Log Analytics, con unos
minutos de demora, y se consultan así (necesita la extensión `log-analytics`:
`az extension add -n log-analytics`), o con la opción 5 de `ScriptAz.java`:

```bash
WS=$(az containerapp env show -n perfume-env -g rg-perfume \
  --query properties.appLogsConfiguration.logAnalyticsConfiguration.customerId -o tsv)
az monitor log-analytics query -w $WS -o table --analytics-query \
  "ContainerAppConsoleLogs_CL | where ContainerAppName_s == 'perfume-api' | project TimeGenerated, Log_s | order by TimeGenerated desc | take 20"
```

Cada request aparece con su `requestId`, método, ruta, código y duración (`RequestTraceFilter`).

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

**Importante:** con el ACR borrado (estado normal, todo apagado), el `start` a mano **no alcanza**:
la app intenta bajar la imagen de un registry que no existe y no levanta. Para prender y apagar se
usan los workflows **Levantar** y **Apagar** (ver "Costos y limpieza final"). El `stop` a mano
sí funciona siempre, pero deja el ACR existiendo (y cobrando).

## Fork o repo nuevo

Los secretos **no se copian** con el fork ni al cambiar de repo. Hay que cargar a mano:

- En el repo nuevo de GitHub: los 4 secretos de la tabla "Secretos de GitHub".
- En la Container App nueva (si también se crea una): los secretos y variables de su tabla. Las
  credenciales del registry las carga el pipeline solo.

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

### Versiones parcheadas a mano en `build.gradle`

El primer run del pipeline nuevo encontró con Trivy 13 CVEs (3 CRITICAL, 10 HIGH) en versiones que
trae Spring Boot 4.1.1, que es la última publicada. Como no hay un Spring Boot nuevo con los
arreglos, se pisan las versiones con las propiedades del BOM de Spring Boot:

| Propiedad | Spring Boot 4.1.1 trae | Se fija en | CVEs que arregla |
|---|---|---|---|
| `tomcat.version` | 11.0.24 | 11.0.26 | CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 (CRITICAL) |
| `jackson-bom.version` (Jackson 3) | 3.1.5 | 3.1.7 | CVE-2026-89407, -89425, -68497, -91776, -91777 (HIGH) |
| `jackson-2-bom.version` (Jackson 2, lo usa `jjwt-jackson`) | 2.21.5 | 2.21.7 | los mismos 5 de Jackson (HIGH) |

Son parches dentro de la misma línea (11.0.x, 3.1.x, 2.21.x), por eso el riesgo de romper algo es
bajo. Se probó el contenedor con el perfil cloud: health, `/api/perfumes`, login del admin con JWT
y `/actuator/metrics` con el token.

**Hay que sacar estas 3 líneas** cuando se suba a una versión de Spring Boot que ya traiga estas
versiones o superiores (se ve en `spring-boot-dependencies-<version>.pom`, en Maven Central). Si se
dejan, podrían quedar fijadas versiones más viejas que las que traería el Spring Boot nuevo.

## Costos y limpieza final

### Qué cobra y qué no

| Recurso | ¿Cobra? | Detalle |
|---|---|---|
| **Container Registry (ACR) Basic** | **sí, por existir** | ~USD 0,17 por día, proporcional a las horas, aunque no se use. No se puede detener: solo deja de cobrar si se borra. Por eso se crea al levantar y se borra al apagar |
| Container App **detenida** | no | `Stopped` no consume CPU ni memoria |
| Container App **prendida** | casi nada | entra en el cupo gratis mensual de Container Apps mientras se use poco |
| Log Analytics (`workspace-rgperfume…`) | prácticamente no | lo usa el entorno para los logs; los primeros 5 GB por mes son gratis. Se deja |
| Entorno Container Apps, resource group | no | |

**Con todo apagado (app detenida y ACR borrado), el gasto es cero.** Los USD 0,11 que aparecieron
en Cost Management fueron todos del ACR (06/10/2026, unas 16 horas de existencia), cuando quedaba
creado todo el día.

### Antes de presentar: Levantar

1. GitHub → **Actions → Levantar → Run workflow** (rama `main`), **unos 15 minutos antes**.
2. Tarda **unos 4 minutos y medio** (medido: 4 min 13 s y 4 min 16 s): crear el ACR, build, Trivy,
   push, deploy y arranque. Termina en verde solo si la prueba de humo vio `UP` con la imagen nueva.
3. Verificar:
   ```bash
   az containerapp show -n perfume-api -g rg-perfume --query properties.runningStatus -o tsv   # Running
   curl https://<fqdn>/actuator/health                                                         # {"status":"UP"}
   ```
   El FQDN también aparece en el resumen del run.

### Después de presentar: Apagar

GitHub → **Actions → Apagar → Run workflow**. Detiene la app, verifica `Stopped` y borra el ACR
(medido: unos 45 segundos). También sirve si un run falló y dejó la app prendida o el ACR existiendo.

Para comprobar que no queda nada cobrando:
```bash
az containerapp show -n perfume-api -g rg-perfume --query properties.runningStatus -o tsv   # Stopped
az acr list -g rg-perfume -o table                                                           # vacío
```

### Al terminar la materia: borrar todo

```bash
az group delete -n rg-perfume
```

Borra **todo** el resource group: la Container App con sus secretos, el entorno, el Log Analytics y
el ACR si existiera. **Es irreversible**: para volver a desplegar habría que repetir los "Pasos
manuales" desde cero. Después conviene borrar también el service principal
(`az ad sp delete --id <appId>`) y el secreto `AZURE_CREDENTIALS` de GitHub.

## Estado real del despliegue (lo que se probó)

- Región: `chilecentral` (la política de la suscripción de estudiante solo permite
  newzealandnorth, mexicocentral, southafricanorth, chilecentral y northcentralus).
- Entorno Container Apps **Express**: no admite identidad administrada ni sufijos de revisión (la
  revisión se llama siempre `perfume-api--latest`). El registry usa usuario administrador.
- Se borró la identidad administrada `id-perfume-acr`, que había quedado de un intento anterior y
  no se usaba (Express no la admite).
- `az acr build` no funciona en `chilecentral`: la imagen se construye en GitHub Actions y se sube con Docker.
- Health check en producción: `https://<fqdn>/actuator/health` responde `UP`. Justo después de
  actualizar la imagen puede responder 503 mientras arranca la revisión nueva; luego vuelve a 200.
- Pipeline completo en verde (run 37527971295, commit `471292b`): Trivy 0 vulnerabilidades HIGH/CRITICAL
  en la base Distroless y en `app.jar`; la revisión activa quedó con la imagen
  `parcial-ing-soft:471292bb…`, `Healthy`, y `/actuator/health` responde `UP`.
- **Ciclo de costo cero probado (06/10/2026)**: push (`164fee6`, tag `v0.2.0`) → Levantar →
  Apagar → Levantar → Apagar, todo en verde. Azure acepta el `update` con la app detenida, así que
  el orden que quedó es **update → start**. El nombre del ACR se pudo reutilizar en el primer
  intento incluso 30 segundos después de borrarlo, y la app tomó cada vez las credenciales nuevas.
  Con la app levantada: health `UP`, `/api/perfumes` 200, login del admin con token y
  `/actuator/metrics` 401 sin token y 200 con token. "Apagar" con el ACR ya borrado no falla.
- Spring Boot 4.0.5 tenía 58 dependencias vulnerables (Spring, Tomcat y Spring Security) según
  Dependency-Check; se subió a Spring Boot 4.1.1 y se migró a Jackson 3.

## Limitaciones

- **Datos efímeros**: H2 en memoria, carrito en memoria (`CartStore`) y `/tmp/uploads` se pierden al
  reiniciar. Funciona porque hay una sola réplica (`--max-replicas 1`); con más réplicas cada una
  tendría sus propios datos.
- **Paridad dev/prod**: en la nube se usa H2 y en desarrollo PostgreSQL. Se atenúa con
  `MODE=PostgreSQL` y con las mismas migraciones de Flyway en los dos.
- **Usuario admin del ACR**: tiene que quedar habilitado porque Express no admite identidad
  administrada para descargar la imagen. El pipeline crea el ACR con el usuario admin activado.
- **ACR efímero**: con todo apagado no hay imágenes guardadas. Prender la app siempre implica
  construir de nuevo (workflow "Levantar", unos minutos), y no existe `/deploy`.
