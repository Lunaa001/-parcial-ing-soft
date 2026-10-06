# AI Perfume Advisor

Asistente virtual de ventas para una perfumería. Entiende lo que el cliente pide en lenguaje
natural ("quiero algo dulce para la noche"), recomienda perfumes del catálogo y arma el pedido
por WhatsApp. Es una API REST en **Java 25 + Spring Boot 4.1** que usa **Groq** (modelos de
lenguaje) para interpretar los mensajes.

Se despliega sola en **Azure Container Apps** con cada push a `main`. Detalle del despliegue,
del pipeline y del script de gestión: [DEPLOY.md](DEPLOY.md).

---

## Qué hace

- **Catálogo de perfumes**: listado, detalle, filtros por categoría, género y disponibilidad.
- **Administración** (con login de admin): alta, edición, baja y subida de fotos de perfumes.
- **Recomendaciones y chat**: analiza las preferencias del cliente y devuelve los perfumes que
  mejor coinciden, con una respuesta en lenguaje natural.
- **Carrito y pedido**: carrito por cliente y link de WhatsApp con el pedido armado.
- **Seguridad**: login con JWT, roles (admin/cliente), validación de datos de entrada.
- **Observabilidad**: health check, métricas de CPU y memoria, y un id por request en los logs.

## API

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/api/perfumes`, `/api/perfumes/{id}`, `/api/perfumes/available` | público |
| GET | `/api/perfumes/category/{category}`, `/api/perfumes/gender/{gender}` | público |
| POST | `/api/auth/login`, `/api/auth/register` | público |
| POST | `/api/recommendations`, `/api/chat` | público |
| GET/POST/PUT/DELETE | `/api/cart`, `/api/cart/items`, `/api/cart/checkout` | público (carrito por id de cliente) |
| POST/PUT/DELETE | `/api/admin/perfumes`, `/api/admin/perfumes/{id}`, `/api/admin/perfumes/images` | admin (JWT) |
| GET | `/actuator/health` | público |
| GET | `/actuator/metrics` | admin (JWT) |

Ejemplo de login: `POST /api/auth/login` con `{"username":"admin","password":"..."}` devuelve
`{"token":"...","type":"Bearer",...}`. El token va en el header `Authorization: Bearer <token>`.

## Estructura

```
src/main/java/com/iaperfumeadvisor/
├── ai/           motor de recomendación, análisis de preferencias y prompts
├── cart/         carrito en memoria
├── config/       seguridad, CORS, filtro de trazas, admin inicial, precalentado de Groq
├── controller/   endpoints REST (admin, api, auth, client)
├── dto/          objetos de request y response
├── entity/       entidades JPA (User, Perfume)
├── enums/        categorías, géneros, estados, roles
├── exception/    excepciones y manejador global de errores
├── mapper/       conversión entidad ↔ DTO
├── repository/   acceso a datos (Spring Data JPA)
├── security/     JWT y autenticación
└── service/      lógica de negocio
src/main/resources/
├── application.properties                 configuración común (lee variables de entorno)
├── application-cloud.properties           perfil de la nube: H2 en memoria
├── application-prod.properties            perfil con PostgreSQL de producción
├── application-dev.properties.example     plantilla del perfil de desarrollo local
└── db/migration/                          migraciones de Flyway
```

## Correrlo en local

### Con Docker Compose (recomendado)

Solo hace falta Docker. Usa el perfil `cloud` (base H2 en memoria), igual que en Azure.

```bash
cp .env.example .env          # en Windows: copy .env.example .env
# completar en .env: JWT_SECRET, GROQ_API_KEY y ADMIN_PASSWORD
docker compose up --build
```

La API queda en `http://localhost:8080`. Probar con `curl http://localhost:8080/actuator/health`.
Para frenarla: `docker compose down`.

### Con Gradle (desarrollo, PostgreSQL local)

Necesita JDK 25 y un PostgreSQL en `localhost:5432` con la base `perfume_advisor`.

```bash
cp src/main/resources/application-dev.properties.example src/main/resources/application-dev.properties
# completar los valores de desarrollo
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun          # Windows: set SPRING_PROFILES_ACTIVE=dev && gradlew.bat bootRun
```

## Desplegar

- **Automático**: cada push a `main` construye la imagen, la escanea con Trivy, la sube al Azure
  Container Registry y actualiza la Container App (`.github/workflows/deploy.yml`).
  - `/notdeploy` en el mensaje del commit: construye y sube, pero no despliega.
  - `/deploy` en el mensaje del commit: no construye, redespliega la última imagen publicada.
- **Versionado automático**: cada push a `main` crea un tag `vX.Y.Z` según el prefijo del commit
  (`feat:` sube la Y, `fix:`/`refactor:` la Z, `!` o `BREAKING CHANGE:` la X).
- **A mano**: `java ScriptAz.java` (menú para construir, desplegar, parar, iniciar, ver logs).

Paso a paso y comandos: [DEPLOY.md](DEPLOY.md).

## Variables y secretos

Nada sensible está en el código: todo sale de variables de entorno. La lista completa, con qué
es cada una y si es obligatoria, está en [`.env.example`](.env.example).

| Dónde se cargan | Qué | Para qué |
|---|---|---|
| `.env` en tu máquina (no se sube) | `JWT_SECRET`, `GROQ_API_KEY`, `ADMIN_PASSWORD`, ... | correr la app con `docker compose` |
| `.env` en tu máquina (no se sube) | `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` | usar `ScriptAz.java` |
| GitHub → Settings → Secrets and variables → Actions | `AZURE_CREDENTIALS`, `ACR_NAME`, `CONTAINER_APP_NAME`, `RESOURCE_GROUP` | el pipeline de despliegue |
| Azure → Container App → secretos y variables | `JWT_SECRET`, `GROQ_API_KEY`, `ADMIN_PASSWORD`, `SPRING_PROFILES_ACTIVE`, ... | la app corriendo en Azure |

Obligatorias para que la app arranque en el perfil `cloud`: `JWT_SECRET` (32 caracteres o más) y
`GROQ_API_KEY`. Si se hace fork o se usa otro repo, **todos los secretos se cargan de nuevo a mano**.
