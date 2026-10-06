# Despliegue en Azure Container Apps

Este repo construye la imagen (Dockerfile multi-stage con runtime Distroless) y la despliega en
Azure Container Apps cada vez que se hace push a `main` (`.github/workflows/deploy.yml`).

## Decisiones

- **Base de datos**: perfil `cloud` con H2 en memoria, permitido por la consigna si PostgreSQL
  complica el despliegue. Los datos se pierden al reiniciar el contenedor.
- **Archivos subidos** (`UPLOAD_DIR=/tmp/uploads`): también efímeros, por el mismo motivo.
- **Un solo proceso**: el ENTRYPOINT ejecuta `java -jar` directamente, sin shell ni supervisor.
- **Secretos**: nunca en el repo. Se cargan como secretos de GitHub (para el pipeline) y como
  secretos de la Container App (para la aplicación).
- **Health check**: `GET /actuator/health` (público). Métricas de CPU/memoria en
  `/actuator/metrics` (requieren login admin). Trazas por request: header `X-Request-Id` y
  duración en los logs.

## Variables y secretos

| Dónde | Nombre | Qué es |
|---|---|---|
| GitHub Secrets | `AZURE_CREDENTIALS` | JSON de la service principal (ver paso 3) |
| GitHub Secrets | `ACR_NAME` | nombre del Container Registry (sin `.azurecr.io`) |
| GitHub Secrets | `CONTAINER_APP_NAME` | nombre de la Container App |
| GitHub Secrets | `RESOURCE_GROUP` | grupo de recursos |
| Container App (secreto) | `JWT_SECRET` | clave para firmar los tokens (mínimo 256 bits). Obligatoria |
| Container App (secreto) | `GROQ_API_KEY` | key de Groq. Obligatoria |
| Container App (secreto) | `ADMIN_PASSWORD` | contraseña del admin inicial |
| Container App | `ADMIN_USERNAME`, `ADMIN_EMAIL` | datos del admin inicial |
| Container App | `WHATSAPP_BUSINESS_NUMBER` | número para el link de pedido |

## Pasos manuales (una sola vez)

Variables de ejemplo: `RG=rg-perfume`, `LOC=eastus`, `ACR=acrperfumeadvisor<tuNombre>`,
`ENV=perfume-env`, `APP=perfume-api`.

```bash
# 1. Login y grupo de recursos
az login
az group create -n $RG -l $LOC

# 2. Container Registry
az acr create -g $RG -n $ACR --sku Basic --admin-enabled false

# 3. Entorno y Container App (imagen placeholder hasta el primer push del pipeline)
az containerapp env create -n $ENV -g $RG -l $LOC
az containerapp create -n $APP -g $RG --environment $ENV \
  --image mcr.microsoft.com/k8se/quickstart:latest \
  --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 1 \
  --registry-server $ACR.azurecr.io --registry-identity system

# 4. Permiso para que la app descargue imágenes del registro
PRINCIPAL=$(az containerapp show -n $APP -g $RG --query identity.principalId -o tsv)
ACR_ID=$(az acr show -n $ACR -g $RG --query id -o tsv)
az role assignment create --assignee $PRINCIPAL --role AcrPull --scope $ACR_ID

# 5. Secretos y variables de la aplicación
az containerapp secret set -n $APP -g $RG --secrets \
  jwt-secret="<clave-de-256-bits-o-mas>" groq-key="<tu-key-de-groq>" admin-pass="<contraseña-admin>"
az containerapp update -n $APP -g $RG --set-env-vars \
  SPRING_PROFILES_ACTIVE=cloud JWT_SECRET=secretref:jwt-secret GROQ_API_KEY=secretref:groq-key \
  ADMIN_PASSWORD=secretref:admin-pass ADMIN_USERNAME=admin ADMIN_EMAIL=admin@ejemplo.com

# 6. Service principal para GitHub (guardar el JSON que imprime como secreto AZURE_CREDENTIALS)
SUB=$(az account show --query id -o tsv)
az ad sp create-for-rbac --name perfume-gh --role Contributor \
  --scopes /subscriptions/$SUB/resourceGroups/$RG --sdk-auth
SP_ID=$(az ad sp list --display-name perfume-gh --query "[0].appId" -o tsv)
az role assignment create --assignee $SP_ID --role AcrPush --scope $ACR_ID
```

Después cargar en GitHub (Settings → Secrets and variables → Actions): `AZURE_CREDENTIALS`,
`ACR_NAME`, `CONTAINER_APP_NAME` y `RESOURCE_GROUP`.

## Verificar que el contenedor corre

```bash
az containerapp show -n $APP -g $RG --query properties.runningStatus -o tsv
az containerapp show -n $APP -g $RG --query properties.configuration.ingress.fqdn -o tsv
curl https://<fqdn>/actuator/health            # debe devolver {"status":"UP"}
az containerapp logs show -n $APP -g $RG --follow
```

## Detener la aplicación

```bash
# Detener la ejecución (sin borrar nada): escala a cero réplicas
az containerapp update -n $APP -g $RG --min-replicas 0 --max-replicas 1
az containerapp replica list -n $APP -g $RG -o table   # debe quedar vacío

# Para volver a levantarla
az containerapp update -n $APP -g $RG --min-replicas 1 --max-replicas 1
```

## Migrar a otro repo o fork

Las variables y secretos de GitHub y de Azure no se copian solos: hay que cargarlos a mano en el
nuevo repo y en la nueva Container App, como en los pasos 5 y 6.

## Estado real del despliegue (lo que se probó)

- Región: `chilecentral` (la política de la suscripción de estudiante solo permite
  newzealandnorth, mexicocentral, southafricanorth, chilecentral y northcentralus).
- Entorno Container Apps **Express**: no admite identidad administrada ni sufijos de revisión.
  El registry usa usuario administrador (credenciales guardadas como secretos).
- `az acr build` no funciona en `chilecentral`: la imagen se construye en GitHub Actions y se sube con Docker.
- Health check en producción: `https://<fqdn>/actuator/health` responde `UP`. Justo después de
  actualizar la imagen puede responder 503 mientras arranca la revisión nueva; luego vuelve a 200.
- Versión desplegada: `parcial-ing-soft:v3` (Spring Boot 4.1.1, Jackson 3). `/actuator/metrics`
  responde 401 sin login.
- Control de ciclo de vida: `--min-replicas 0` pide escalar a cero, pero Azure tarda en bajar la
  réplica; `--min-replicas 1` la vuelve a levantar y el health responde 200.

## Limitaciones

- **Despliegue automático**: la suscripción de estudiante bloquea `az ad sp create-for-rbac` y la asignación
  de roles (error `MissingSubscription`). El job `deploy` del workflow necesita un secreto
  `AZURE_CREDENTIALS` que hay que crear desde una cuenta con permisos de administrador de Entra ID.
  Mientras tanto, la imagen se publica sola en el registry y la Container App se actualiza a mano con
  `az containerapp update --image <registry>/parcial-ing-soft:<tag>`.
- **Auditoria de CVEs**: `./gradlew dependencyCheckAnalyze` necesita una API key de NVD (nvd.nist.gov),
  cargada solo en la variable de entorno `NVD_API_KEY` (nunca en un archivo). El analizador OSS Index
  está desactivado porque exige credenciales. Con Spring Boot 4.0.5 la auditoría encontró 58 dependencias
  vulnerables (Spring, Tomcat y Spring Security); se subió a Spring Boot 4.1.1 y se migró a Jackson 3.
  La auditoría completa sobre 4.1.1 queda pendiente: su resultado está en
  `build/reports/dependency-check-report.html` al correrla.
- **Base de datos y archivos**: H2 en memoria y `/tmp/uploads`, ambos efimeros.
