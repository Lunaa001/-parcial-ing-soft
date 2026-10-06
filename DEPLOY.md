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

## Ubicación de la configuración

Las clases de configuración de Spring están en `src/main/java/com/iaperfumeadvisor/config/`
(paquete `com.iaperfumeadvisor.config`), al mismo nivel que `controller`, `service`, etc.:
`SecurityConfig`, `CorsConfig`, `WebMvcConfig`, `RequestTraceFilter`, `AdminUserInitializer` y
`GroqWarmupInitializer`. Antes estaban en `controller/config/` con paquetes mezclados.

Los valores (claves, contraseñas, URLs) no están en el código: salen de variables de entorno.
La lista completa está en `.env.example`. El perfil de desarrollo local tiene su plantilla en
`src/main/resources/application-dev.properties.example`; el archivo real está en `.gitignore`.

## Pipeline (`deploy.yml`)

Un solo job, en este orden:

1. **Verifica los secretos**: si falta alguno (o `ACR_NAME` trae `.azurecr.io`), falla con un mensaje claro.
2. **Lee el mensaje del commit** para ver si trae `/deploy` o `/notdeploy` (ver abajo).
3. **Login en Azure** con `AZURE_CREDENTIALS` y **login en el ACR** con `az acr login`.
4. **Construye** la imagen con dos tags (el SHA del commit y `latest`) y etiquetas OCI: título,
   versión, fecha, commit, repo y descripción (`docker inspect` las muestra).
5. **Escanea** la imagen con Trivy: falla si hay CVEs HIGH o CRITICAL que ya tengan parche. Se
   escanea antes del push, así una imagen vulnerable nunca llega al registry.
6. **Sube** la imagen al ACR.
7. **Despliega** con `az containerapp update --image <acr>/parcial-ing-soft:<sha>`.
8. **Prueba de humo**: espera a que la revisión activa tenga la imagen nueva y esté `Healthy`, y a
   que `/actuator/health` responda `UP` (10 intentos cada 15 segundos).
9. **Logout de Azure** (`az logout`), siempre, aunque haya fallado un paso anterior.

No usa `azure/container-apps-deploy-action` (como el proyecto de referencia) porque esa action
crea el entorno en otra región y modo; el nuestro es **Express en `chilecentral`**, y con
`az containerapp update` se actualiza la app existente sin tocar el entorno.

### Controles por mensaje de commit: `/deploy` y `/notdeploy`

| Mensaje del commit contiene | Construye, escanea y sube | Despliega |
|---|---|---|
| (nada especial) | sí | sí, la imagen recién construida |
| `/notdeploy` | sí | **no** |
| `/deploy` | **no** | sí, la última imagen publicada (`:latest`) |

Ejemplos: `git commit -m "docs: corregir README /notdeploy"` o
`git commit --allow-empty -m "chore: redesplegar /deploy"`.

Con `/deploy`, el pipeline busca en el ACR el digest de `parcial-ing-soft:latest` y despliega
`<acr>/parcial-ing-soft@sha256:...`. Así siempre se crea una revisión nueva (aunque la app ya
estuviera en `:latest`) y la prueba de humo sabe exactamente qué imagen tiene que estar corriendo.

El mensaje del commit se pasa al script como variable de entorno (`COMMIT_MSG`), nunca
interpolado: si alguien escribiera `$(comando)` en un commit, no se ejecuta.

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
| **2** Deploy | si la app no existe la crea; si existe actualiza imagen, CPU, memoria y réplicas. Si estaba detenida la inicia. Muestra la URL | `az containerapp create/update`, `az containerapp registry set` |
| **3a** Parar | detiene la app (no consume créditos) y espera `Stopped` | `az rest .../stop?api-version=2026-07-01` |
| **3b** Iniciar | la vuelve a levantar y espera `Running` | `az rest .../start?api-version=2026-07-01` |
| **3c** Listar | tabla con nombre, estado, CPU, memoria, réplicas y URL de las apps del grupo | `az containerapp list` |
| **3d** Eliminar | borra una app (pide confirmación; es irreversible) | `az containerapp delete` |
| **4** Pull | baja la imagen del ACR y ofrece correrla en local (con `--env-file .env`) | `docker pull`, `docker run` |
| **5** Logs | logs de la consola en vivo; **Enter** vuelve al menú | `az containerapp logs show --follow` |
| **6** Reconfigurar | vuelve a elegir los recursos | `az group/acr/containerapp list` |
| **0** Salir | ofrece cerrar la sesión de Azure CLI | `az logout` |

Diferencias con el `script_az.py` original:

- Está en Java, como el resto del proyecto.
- Puerto por defecto 8080 (no 3000), imagen `parcial-ing-soft` y api-version `2026-07-01` en
  parar/iniciar (la que se probó con nuestra app).
- En los logs se vuelve al menú con **Enter** y no con Ctrl+C: en Java, Ctrl+C cierra todo el
  programa.
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

## Estado real del despliegue (lo que se probó)

- Región: `chilecentral` (la política de la suscripción de estudiante solo permite
  newzealandnorth, mexicocentral, southafricanorth, chilecentral y northcentralus).
- Entorno Container Apps **Express**: no admite identidad administrada ni sufijos de revisión (la
  revisión se llama siempre `perfume-api--latest`). El registry usa usuario administrador.
- `az acr build` no funciona en `chilecentral`: la imagen se construye en GitHub Actions y se sube con Docker.
- Health check en producción: `https://<fqdn>/actuator/health` responde `UP`. Justo después de
  actualizar la imagen puede responder 503 mientras arranca la revisión nueva; luego vuelve a 200.
- Pipeline completo en verde (run 37527971295, commit `471292b`): Trivy 0 vulnerabilidades HIGH/CRITICAL
  en la base Distroless y en `app.jar`; la revisión activa quedó con la imagen
  `parcial-ing-soft:471292bb…`, `Healthy`, y `/actuator/health` responde `UP`.
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
