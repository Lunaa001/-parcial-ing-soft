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
  Container App la descarga con el usuario admin del ACR (al principio el entorno era **Express**,
  que no admitía identidad administrada, y se mantuvo así). Como el ACR se recrea en cada levantada, su contraseña admin cambia: el
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

## Twelve-Factor

Cómo cumple el proyecto cada uno de los [12 factores](https://12factor.net/es/). ✅ = se cumple,
⚠️ = se cumple en parte por usar **H2 en memoria en la nube**, que la consigna permite.

| Factor | Estado | Cómo se cumple |
|---|---|---|
| **I. Código base** | ✅ | Un solo repo en git. El mismo código se despliega en local (docker compose) y en Azure. Cada versión queda con un tag `vX.Y.Z` automático |
| **II. Dependencias** | ✅ | Todas declaradas en `build.gradle` y resueltas con el Gradle wrapper (no hace falta tener Gradle instalado). La imagen trae todo lo que necesita: JRE + jar |
| **III. Configuración** | ✅ | Todo lo que cambia entre entornos sale de variables de entorno: `JWT_SECRET`, `GROQ_API_KEY`, `DB_*`, `PORT`, `GROQ_MODEL`... Los secretos están en la Container App y en GitHub, nunca en el código. Lista completa en `.env.example` |
| **IV. Servicios de apoyo** | ⚠️ | Groq es un recurso conectado por URL y key, intercambiable por configuración. La base PostgreSQL de los perfiles `dev`/`prod` también (`DB_URL`). Pero en la nube la base es **H2 embebida en el mismo proceso**: no es un servicio aparte que se pueda cambiar sin tocar la app |
| **V. Construir, distribuir, ejecutar** | ✅ | *Build*: el pipeline compila, corre los tests y arma la imagen con el SHA del commit. *Release*: esa imagen + la configuración de la Container App forman una revisión. *Run*: Azure ejecuta la revisión. Nunca se modifica código en el contenedor |
| **VI. Procesos** | ⚠️ | La app no guarda sesiones (login con JWT), pero **sí guarda estado en memoria**: la base H2, el carrito (`CartStore`) y las fotos en `/tmp/uploads`. Si el proceso se reinicia, ese estado se pierde |
| **VII. Asignación de puertos** | ✅ | La app trae su propio servidor (Tomcat embebido) y escucha en `server.port=${PORT:8080}`: el puerto viene del entorno, con 8080 por defecto. Azure expone ese puerto con el ingress |
| **VIII. Concurrencia** | ⚠️ | Se escala agregando procesos (réplicas), no hilos a mano. Pero está limitada a **una réplica** (`--max-replicas 1`): con más, cada una tendría su propia base y su propio carrito, y los datos no coincidirían |
| **IX. Desechabilidad** | ✅ | Apagado prolijo: `server.shutdown=graceful` con `timeout-per-shutdown-phase=20s` termina las requests en curso antes de cerrar (Azure espera 30 s; docker compose, 30 s con `stop_grace_period`). Arranca solo y se puede detener e iniciar cuando haga falta (workflows Levantar y Apagar) |
| **X. Paridad entre entornos** | ⚠️ | La misma imagen corre en local y en Azure, con las mismas migraciones de Flyway. Pero en la nube la base es H2 y en desarrollo PostgreSQL. Se atenúa con `MODE=PostgreSQL` en H2 |
| **XI. Logs** | ✅ | La app escribe a stdout como un flujo de eventos (sin archivos de log), con `requestId` y duración por request. Azure los junta en Log Analytics. Las consultas al health no se registran, para no tapar las requests reales |
| **XII. Procesos de administración** | ✅ | Las migraciones de base (Flyway) corren con el mismo código y configuración que la app, al arrancar. El ciclo de vida se administra con los workflows y con `ScriptAz.java`, que usan la misma imagen y configuración |

### Qué habría que cambiar para cumplir IV, VI, VIII y X al 100%

Los cuatro tienen la misma causa: **el estado vive dentro del proceso** (H2, carrito y fotos en
memoria). La consigna permite H2 en memoria, y con una sola réplica funciona bien, pero para cumplir
del todo:

| Cambio | Qué resuelve |
|---|---|
| **PostgreSQL externo** (por ejemplo Azure Database for PostgreSQL) con el perfil `prod`, que ya existe y solo necesita `DB_HOST`, `DB_NAME`, `DB_USER` y `DB_PASSWORD` | IV: la base pasa a ser un servicio conectado por configuración. X: misma base en desarrollo y en la nube. VI: los datos sobreviven a reinicios |
| **Carrito en la base o en Redis** (Azure Cache for Redis) en vez de `CartStore` en memoria | VI: el proceso queda sin estado. VIII: cualquier réplica puede atender a cualquier cliente |
| **Fotos en Azure Blob Storage** en vez de `/tmp/uploads` | VI: las fotos no se pierden al reiniciar |
| **Más réplicas** (`--max-replicas` > 1 y reglas de escalado por HTTP), una vez hecho lo anterior | VIII: escalar agregando procesos |

## Workflows de GitHub Actions

| Workflow | Cuándo corre | Qué hace | Cómo queda |
|---|---|---|---|
| `deploy.yml` (CI/CD) | push a `main` (salvo si solo cambian `.md`, `ScriptAz.java` o `.env.example`, que no entran en la imagen) o *Run workflow* | tests → build → Trivy + Grype → crea el ACR → push → deploy → prueba de humo → **apaga** | app detenida, ACR borrado |
| `levantar.yml` (**Levantar**) | solo manual (*Run workflow*) | lo mismo, pero **no apaga** | app prendida, ACR existiendo |
| `apagar.yml` (**Apagar**) | solo manual | detiene la app, verifica `Stopped` y borra el ACR (si ya no existe, no falla) | app detenida, ACR borrado |
| `versioning.yml` | push a `main` | crea el tag `vX.Y.Z` | — |
| `codeql.yml` (**CodeQL**) | push a `main`, PR y los lunes | análisis estático del código Java (GitHub code scanning) | resultados en *Security → Code scanning* |

Los tres primeros usan la misma lógica, que está en `ciclo-azure.yml` (workflow reutilizable con
tres interruptores: construir, desplegar y apagar), y comparten el grupo de `concurrency`
`azure-perfume`: nunca corren a la vez, así "Apagar" no puede borrar el ACR en medio de un deploy.

### Pasos del ciclo (`ciclo-azure.yml`)

1. **Verifica los secretos**: si falta alguno (o `ACR_NAME` trae `.azurecr.io`), falla con un mensaje claro.
2. **Calcula la versión** (`vX.Y.Z`) con `.github/scripts/version.sh`, el mismo script que usa
   `versioning.yml` (ver "Versionado automático").
3. **Tests**: instala Java 25 (con caché de Gradle) y corre `./gradlew test` con el perfil `cloud`
   (H2) y valores de prueba para `JWT_SECRET` y `GROQ_API_KEY`, nunca los reales. Si un test falla,
   el pipeline se frena antes de construir y el ACR ni se crea (el reporte queda como artifact
   `reporte-tests` del run).
4. **Login en Azure** con `AZURE_CREDENTIALS`.
5. **Construye** la imagen con **tres tags**: la versión (`v0.4.0`), el SHA del commit y `latest`.
   Etiquetas OCI: título, `version` = vX.Y.Z, `revision` = SHA, fecha, repo y descripción.
6. **Escanea** la imagen con **Trivy** y con **Grype**: cualquiera de los dos frena el pipeline si
   encuentra CVEs HIGH o CRITICAL que ya tengan parche. Como no necesitan el ACR, si encuentran
   algo **el ACR ni se crea**. Detalle en [docs/seguridad/vulnerabilidades.md](docs/seguridad/vulnerabilidades.md).
7. **Crea el ACR** si no existe: `rg-perfume`, misma región, Basic, usuario admin habilitado. El
   nombre es global en Azure y recién borrado puede tardar en liberarse: reintenta hasta 10 minutos.
8. **Sube** los tres tags al ACR (`az acr login` + `docker push --all-tags`).
9. **Credenciales**: lee la contraseña admin del ACR y la connection string de Application
   Insights, las oculta del log (`::add-mask::`) y las carga en la app (`az containerapp registry
   set` y el secreto `appinsights-cs`).
10. **Despliega** la imagen **por versión**: `az containerapp update --image <acr>/parcial-ing-soft:vX.Y.Z`
    con las variables `APP_VERSION`, `GIT_SHA`, `APPLICATIONINSIGHTS_CONNECTION_STRING=secretref:appinsights-cs`
    y `APPLICATIONINSIGHTS_ROLE_NAME`. `GIT_SHA` fuerza una revisión nueva aunque la versión no
    cambie (un commit `chore:` reusa el último tag). Si la app estaba detenida, la inicia después
    (**update → start**: si se prendiera antes, intentaría bajar la imagen anterior, que no existe en
    el ACR recreado). El orden usado queda en el resumen del run.
11. **Prueba de humo**: espera a que la revisión activa tenga la imagen de esta versión **y** el
    `GIT_SHA` de este commit, esté `Healthy`, y `/actuator/health` responda `UP` (10 intentos cada 15 s).
12. **Apaga** (solo `deploy.yml` y "Apagar"): detiene la app, verifica `runningStatus=Stopped` y
    borra el ACR. Si hubo deploy, solo apaga si la prueba de humo pasó: si falló, la app queda
    prendida para revisar los logs, y después hay que correr "Apagar".
13. **Limpieza**: si algo falla después de crear el ACR y antes de tocar la app (por ejemplo el
    push), borra ese ACR recién creado, porque no sirve para nada y cobra.
14. **Logout de Azure** (`az logout`), siempre, aunque haya fallado un paso anterior.

No usa `azure/container-apps-deploy-action` (como el proyecto de referencia) porque esa action
    crea el entorno en otra región y modo; el nuestro es **`perfume-env-std` en `chilecentral`**, y con
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

Basado en el del proyecto de referencia. En cada push a `main` crea un tag `vX.Y.Z` según el
prefijo del commit ([Conventional Commits](https://www.conventionalcommits.org/)):

| Prefijo del commit | Cambio de versión | Ejemplo |
|---|---|---|
| `feat!:` o `BREAKING CHANGE:` en el cuerpo | major: v1.2.3 → **v2.0.0** | cambio incompatible de la API |
| `feat:` / `feat(scope):` | minor: v1.2.3 → v1.**3**.0 | funcionalidad nueva |
| `fix:` / `refactor:` | patch: v1.2.3 → v1.2.**4** | corrección |
| `docs:`, `chore:`, `ci:` u otro | sin tag | |

Si todavía no hay ningún tag, parte de `v0.0.0`. Los tags se ven en GitHub → *Tags* o con
`git fetch --tags && git tag`.

**Diferencia con la referencia:** el cálculo se movió a `.github/scripts/version.sh` (la regla es
idéntica), porque el pipeline también lo usa para **etiquetar la imagen con la versión**. Así
`versioning.yml` y el pipeline calculan siempre la misma versión aunque corran al mismo tiempo:

- si el commit ya tiene un tag (porque `versioning.yml` terminó antes, o en un re-run), los dos usan ese;
- si no, los dos calculan el siguiente con la misma regla y el mismo último tag;
- si el commit no sube versión (`docs:`, `chore:`) o el workflow se corrió a mano, se usa la última.

Además, `versioning.yml` ya no crea un tag nuevo si el commit ya tiene uno (en la referencia, un
re-run creaba otro).

### Cómo se ve la imagen en el ACR

Cada deploy sube la misma imagen con tres nombres:

```
acrperfume45184.azurecr.io/parcial-ing-soft:v0.4.0      <- la que se despliega
acrperfume45184.azurecr.io/parcial-ing-soft:<sha>       <- el commit exacto
acrperfume45184.azurecr.io/parcial-ing-soft:latest
```

```bash
az acr repository show-tags -n acrperfume45184 --repository parcial-ing-soft -o table
az containerapp show -n perfume-api2 -g rg-perfume --query "properties.template.containers[0].image" -o tsv
```

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
| **5** Logs | logs de la consola **en vivo**; **Enter** vuelve al menú. En un entorno Express (sin streaming) los lee de Log Analytics cada 10 s | `az containerapp logs show --follow` (en Express: `az rest` a la API de Log Analytics) |
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
- **Logs en Express:** `az containerapp logs show` falla en entornos Express con
  `KeyError: 'eventStreamEndpoint'` (no dan endpoint de streaming en vivo). Si la app no
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
| `CONTAINER_APP_NAME` | nombre de la Container App | `perfume-api2` |
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
| Secreto (lo crea Azure) | registry | contraseña del usuario admin del ACR, para descargar la imagen. La recarga el pipeline en cada deploy |
| Secreto (`appinsights-cs`) | `APPLICATIONINSIGHTS_CONNECTION_STRING` | connection string de Application Insights. **La carga el pipeline** en cada deploy, leyéndola del recurso `appi-perfume`; nunca va en el código ni en los `.properties` |
| Variable (la pone el pipeline) | `APPLICATIONINSIGHTS_ROLE_NAME` | nombre de la app en el mapa de aplicación (`perfume-api`) |
| Variable (la pone el pipeline) | `APP_VERSION`, `GIT_SHA` | versión y commit que corre la revisión |
| Variable | `SPRING_PROFILES_ACTIVE` | `cloud` |
| Variable | `ADMIN_USERNAME`, `ADMIN_EMAIL` | datos del admin inicial |
| Variable (opcional) | `WHATSAPP_BUSINESS_NUMBER` | número para el link de pedido |

`UPLOAD_DIR` **no** se carga en la Container App: ya viene en el Dockerfile (`/tmp/uploads`).

## Pasos manuales (una sola vez)

Variables de ejemplo: `RG=rg-perfume`, `LOC=chilecentral`, `ACR=acrperfume<numero>`,
`ENV=perfume-env-std`, `APP=perfume-api2`. En Git Bash, anteponer `MSYS_NO_PATHCONV=1` a cualquier
comando que tenga un argumento que empiece con `/`.

```bash
# 1. Login y grupo de recursos
az login
az group create -n $RG -l $LOC

# 2. Container Registry: NO hace falta crearlo a mano. Lo crea el pipeline (o "Levantar") cuando
#    lo necesita y lo borra al apagar. A mano: az acr create -g $RG -n $ACR --sku Basic --admin-enabled true

# 3. Entorno ESTANDAR y Container App, con una imagen placeholder hasta el primer push del pipeline.
#    OJO: hay que pasar --environment-mode WorkloadProfiles. Sin eso, esta version de la CLI crea
#    un entorno Express, que NO publica metricas de plataforma (ver "Entorno estándar").
#    WS_ID / WS_KEY: customerId y primarySharedKey del Log Analytics.
az containerapp env create -n $ENV -g $RG -l $LOC --environment-mode WorkloadProfiles \
  --logs-destination log-analytics --logs-workspace-id $WS_ID --logs-workspace-key $WS_KEY
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

# 7. Application Insights (monitoreo), sobre el Log Analytics que creó el entorno. No se borra al apagar.
az provider register -n Microsoft.Insights --wait
WSID=$(az monitor log-analytics workspace list -g $RG --query "[0].id" -o tsv)
MSYS_NO_PATHCONV=1 az monitor app-insights component create --app appi-perfume -g $RG -l $LOC \
  --kind web --application-type web --workspace "$WSID"
# Tope diario de ingesta (0,15 GB): si algo se descontrola, deja de recibir datos en vez de cobrar
az monitor log-analytics workspace update -g $RG -n <workspace> --quota 0.15
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

## Entorno estándar (`perfume-env-std`) y métricas de plataforma

La app corre como **`perfume-api2`** en **`perfume-env-std`**: un entorno de Container Apps
**estándar**, en modo `WorkloadProfiles` y solo con el perfil **Consumption** (sin perfiles
dedicados, así que sin costo fijo: se paga por uso, con el mismo cupo gratis). Está en
`chilecentral` y manda sus logs al mismo Log Analytics (`workspace-rgperfumeOfnB`).

**Por qué se cambió:** antes la app (`perfume-api`) corría en `perfume-env`, un entorno **Express**.
Express no publica **métricas de plataforma** en Azure Monitor: en el portal, *Supervisión →
Métricas* mostraba "--", y `az monitor metrics list` daba vacío durante 7 días con la app prendida.
Tampoco tiene logs en vivo (`az containerapp logs show` fallaba). La documentación de Microsoft
avisa que en Express "algunas ventanas del portal no funcionan". En el entorno estándar las dos
cosas funcionan.

**Métricas de plataforma** (Portal → `perfume-api2` → *Supervisión → Métricas*, o por consola):

| Métrica | Qué mide |
|---|---|
| `WorkingSetBytes` (Memory Working Set Bytes) | memoria del contenedor |
| `UsageNanoCores` (CPU Usage) | CPU usada (1.000.000 nanocores = 1 milicore) |
| `Requests` | requests que entraron por el ingress |
| `Replicas` (Replica Count) | réplicas corriendo |

```bash
APP_ID=$(az containerapp show -n perfume-api2 -g rg-perfume --query id -o tsv)
MSYS_NO_PATHCONV=1 az monitor metrics list --resource "$APP_ID" \
  --metrics WorkingSetBytes UsageNanoCores Requests Replicas --interval PT1M --offset 30m -o table
```

Primera medición (20/10/2026, a 2 minutos de crear la app): memoria 744 MB, CPU 48,9 milicores
(~10 % de 0,5 vCPU), 40 requests (exactamente las enviadas) y 1 réplica.

**Por qué la memoria de plataforma da más que la de la JVM:** `WorkingSetBytes` mide **todo el
contenedor** desde el sistema operativo: el heap de Java, la memoria fuera del heap (metaspace,
hilos, código compilado), el agente de Application Insights **y la caché de archivos del sistema
operativo** (páginas de los jars y bibliotecas que se leyeron). Application Insights, en cambio,
muestra la memoria **del proceso Java** (`Private Bytes`, ~340 MB) y del heap (`jvm_memory_used`).
Por eso la plataforma marca más (~744 MB); lo que importa es que quede por debajo del límite de
1 GiB, y la caché de archivos el sistema la libera si hace falta memoria.

**Cómo se pasó de Express a estándar** (sin cortar la app vieja): se creó el entorno estándar y una
app nueva en paralelo, copiando secretos y variables; se verificó (health, login, métricas,
Application Insights); se cambió el secreto `CONTAINER_APP_NAME` de GitHub a `perfume-api2`, y se
detuvo la app vieja. **Ojo:** con la CLI actual, `az containerapp env create` sin
`--environment-mode WorkloadProfiles` crea un entorno Express (así quedó creado por error
`perfume-env2`, vacío). La suscripción de estudiante permite **un solo** entorno estándar por región.

## Monitoreo con Application Insights

`appi-perfume` es un recurso **Application Insights en modo workspace**: guarda los datos en el mismo
Log Analytics que ya usaba el entorno (`workspace-rgperfumeOfnB`), así que no suma un almacenamiento
aparte. **No se borra con "Apagar"** (ese workflow solo toca la app y el ACR).

**Cómo se integra:** con el **agente Java de Application Insights 3.7.10**, que es la Azure Monitor
OpenTelemetry Distro para Java (por dentro es OpenTelemetry). Para Spring Boot en JVM es lo que
recomienda Microsoft (el starter `spring-cloud-azure-starter-monitor` es para imágenes nativas GraalVM).

- `build.gradle` declara el agente (configuración `appInsightsAgent`, fuera del classpath de la app).
- El Dockerfile lo copia a la imagen y el `ENTRYPOINT` lo carga con `-javaagent`. Sigue siendo **un
  solo proceso**: el agente corre dentro de la misma JVM.
- No hace falta código: el agente captura solo **requests** (con su duración), **dependencias** (las
  llamadas HTTP a `api.groq.com`), **excepciones**, **logs** y **métricas** de CPU y memoria.
- La **connection string** la carga el pipeline como secreto de la Container App. Sin ella (por
  ejemplo en docker compose local) el agente no envía nada y la app funciona igual.

**Dónde mirar** (Portal → `appi-perfume`):

| Pantalla | Qué muestra |
|---|---|
| *Investigate → Transaction search* / *Performance* | cada request (por ejemplo `POST /api/auth/login`) con su duración y código |
| *Investigate → Application map* | `perfume-api` → `api.groq.com`, con cantidad de llamadas, duración y errores |
| *Investigate → Failures* | excepciones y requests fallidas |
| *Monitoring → Metrics* | CPU del proceso, memoria, requests por segundo, tiempos de respuesta |
| *Monitoring → Logs* | consultas KQL sobre todo lo anterior |

Las mismas consultas por línea de comandos (extensión `log-analytics`):

```bash
WS=$(az containerapp env show -n perfume-env -g rg-perfume --query properties.appLogsConfiguration.logAnalyticsConfiguration.customerId -o tsv)
az monitor log-analytics query -w $WS -o table --analytics-query \
  "AppRequests | where TimeGenerated > ago(1h) | project TimeGenerated, Name, ResultCode, DurationMs | order by TimeGenerated desc"
az monitor log-analytics query -w $WS -o table --analytics-query \
  "AppDependencies | where TimeGenerated > ago(1h) | summarize llamadas=count(), ms_promedio=avg(DurationMs) by Target, ResultCode"
az monitor log-analytics query -w $WS -o table --analytics-query \
  "AppPerformanceCounters | where TimeGenerated > ago(1h) | summarize avg(Value) by Name"
```

**Lo que se vio en la prueba (v0.4.0, 13/10/2026):**

| Qué | Resultado |
|---|---|
| `POST /api/auth/login` | 4 requests: 731 ms (la primera, en frío), 137 ms, 102 ms y un 401 de credenciales inválidas en 99 ms |
| Otras requests | `POST /api/chat` 1163 ms, `POST /api/recommendations` 512 ms, `GET /api/perfumes` 68 ms |
| Mapa de aplicación (dependencias) | `perfume-api` → **`api.groq.com`** (`POST /openai/v1/chat/completions`, 3 llamadas, ~508 ms promedio, todas OK) y → **H2** (consultas SQL a `users` y `perfumes`, < 1 ms) |
| CPU | `% Processor Time` ~1,2 % promedio, 3 % máximo (en reposo, con 0,5 vCPU) |
| Memoria | `Private Bytes` del proceso ~340 MB promedio, **388 MB máximo**; además métricas de la JVM (`jvm_memory_used`, hilos, buffers) |
| Logs | cada línea del log de la app llega a `AppTraces` (por ejemplo `POST /api/auth/login -> 200 en 722 ms`) |
| Volumen | ~0,46 MB en 7 minutos (casi todo métricas): ~95 MB por día si quedara prendida todo el día, debajo del tope de 0,15 GB |

**Recursos de la app:** con el agente, **0,25 vCPU / 0,5 GiB no alcanza**: el agente solo tarda 31 s
en arrancar y la app no llegó a levantar en 4,5 minutos (Azure reinició el contenedor), y la
memoria del proceso llega a 388 MB, demasiado justa para 512 MB. Se pasó a **0,5 vCPU / 1 GiB**
(`APP_CPU` y `APP_MEMORY` en `ciclo-azure.yml`, y los defaults de `ScriptAz.java`): el agente arranca
en 15,7 s y Spring en 32,7 s, **unos 50 segundos en total**. Solo se cobra mientras la app está
prendida, dentro del cupo gratis de Container Apps.

**Costo:** el recurso no cobra. La telemetría entra al workspace, cuyos primeros 5 GB por mes son
gratis, y el workspace tiene un **tope de 0,15 GB por día**: si se llegara, deja de recibir datos
hasta el día siguiente, en vez de cobrar.

## Verificar que el contenedor corre

```bash
az containerapp show -n $APP -g $RG --query properties.runningStatus -o tsv
az containerapp revision list -n $APP -g $RG -o table      # imagen y estado (Healthy) de la revisión
az containerapp show -n $APP -g $RG --query properties.configuration.ingress.fqdn -o tsv
curl https://<fqdn>/actuator/health                         # debe devolver {"status":"UP"}
```

**Logs en vivo** (entorno estándar), o con la opción 5 de `ScriptAz.java`:

```bash
az containerapp logs show -n perfume-api2 -g rg-perfume --follow --tail 20   # Ctrl+C para cortar
```

Los logs también quedan guardados en Log Analytics (con unos minutos de demora), para buscar hacia
atrás (necesita la extensión `log-analytics`: `az extension add -n log-analytics`):

```bash
WS=$(az containerapp env show -n perfume-env-std -g rg-perfume \
  --query properties.appLogsConfiguration.logAnalyticsConfiguration.customerId -o tsv)
az monitor log-analytics query -w $WS -o table --analytics-query \
  "ContainerAppConsoleLogs_CL | where ContainerAppName_s == 'perfume-api2' | project TimeGenerated, Log_s | order by TimeGenerated desc | take 20"
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
  push. Desde `v0.4.0` también escanea con **Grype**. El antes y el después y la comparación de
  herramientas están en [docs/seguridad/vulnerabilidades.md](docs/seguridad/vulnerabilidades.md).
- **CodeQL** (`codeql.yml`): análisis estático de nuestro código Java en cada push y PR. Alertas en
  GitHub → *Security → Code scanning*. No necesita API key. La action está fijada al commit `ed142fd…` (v0.36.0) y no a un tag,
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
| Container App **prendida** (0,5 vCPU / 1 GiB) | casi nada | entra en el cupo gratis mensual de Container Apps (180.000 vCPU-segundos y 360.000 GiB-segundos) mientras se use poco: unas 100 horas prendida por mes |
| Log Analytics (`workspace-rgperfume…`) | prácticamente no | lo usan el entorno (logs) y Application Insights (telemetría); los primeros 5 GB por mes son gratis y tiene **tope de 0,15 GB/día**. Se deja |
| Application Insights (`appi-perfume`) | no | modo workspace: no cobra aparte, solo la ingesta del workspace (ver arriba). No se borra al apagar |
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
   az containerapp show -n perfume-api2 -g rg-perfume --query properties.runningStatus -o tsv   # Running
   curl https://<fqdn>/actuator/health                                                         # {"status":"UP"}
   ```
   El FQDN también aparece en el resumen del run.

### Después de presentar: Apagar

GitHub → **Actions → Apagar → Run workflow**. Detiene la app, verifica `Stopped` y borra el ACR
(medido: unos 45 segundos). También sirve si un run falló y dejó la app prendida o el ACR existiendo.

Para comprobar que no queda nada cobrando:
```bash
az containerapp show -n perfume-api2 -g rg-perfume --query properties.runningStatus -o tsv   # Stopped
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
- Hasta el 20/10/2026 la app (`perfume-api`) corrió en un entorno **Express** (`perfume-env`): no
  admite identidad administrada ni sufijos de revisión (la revisión se llamaba siempre
  `perfume-api--latest`) y no publica métricas de plataforma. Desde el 20/10/2026 corre como
  **`perfume-api2` en el entorno estándar `perfume-env-std`** (ver "Entorno estándar").
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
- **Usuario admin del ACR**: la app baja la imagen con el usuario admin (se eligió así cuando el
  entorno era Express, que no admitía identidad administrada). El pipeline crea el ACR con el
  usuario admin activado.
- **ACR efímero**: con todo apagado no hay imágenes guardadas. Prender la app siempre implica
  construir de nuevo (workflow "Levantar", unos minutos), y no existe `/deploy`.
