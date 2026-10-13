# Informe de vulnerabilidades

Cómo se revisa la seguridad del proyecto, qué encontraron las herramientas y cómo se corrigió.

| Herramienta | Qué revisa | Dónde corre | Resultado actual |
|---|---|---|---|
| **Trivy** | imagen Docker: sistema operativo + librerías Java (app y agente) | pipeline, antes de publicar la imagen | **0 HIGH/CRITICAL** |
| **Grype** | lo mismo, con otra base de vulnerabilidades | pipeline, antes de publicar la imagen | **0 HIGH/CRITICAL** |
| **CodeQL** | nuestro código fuente Java (análisis estático) | GitHub code scanning, en cada push y PR | 2 high y 1 medium revisadas (ver abajo) |

## Escaneo de la imagen: Trivy y Grype

- **Dónde:** en el pipeline (`.github/workflows/ciclo-azure.yml`), en cada push a `main` y en
  "Levantar", **antes** de subir la imagen al registry. Si cualquiera de los dos encuentra algo, el
  pipeline se frena: la imagen no se publica, el ACR ni se crea y la app sigue con la versión anterior.
- **Criterio (el mismo en los dos):** falla con vulnerabilidades **HIGH o CRITICAL que ya tengan
  parche**. Trivy: `severity: HIGH,CRITICAL`, `ignore-unfixed: true`. Grype: `severity-cutoff: high`,
  `only-fixed: true`.
- **Versiones fijadas por commit, no por tag** (para que nadie pueda cambiar el código que corre; en
  2026 hubo un incidente en el que se modificaron tags de `trivy-action`):
  - `aquasecurity/trivy-action` v0.36.0 (Trivy 0.70.0) → `ed142fd0673e97e23eac54620cfb913e5ce36c25`
  - `anchore/scan-action` v7.4.2 (Grype) → `27805bf3b4e84b4a5c980df22ed233c00390a439`
- **Qué hay en la imagen:** el sistema operativo Distroless (Debian 13), `app.jar` (la app con sus
  dependencias) y, desde `v0.4.0`, `applicationinsights-agent.jar` (agente de monitoreo).

### Antes: 13 vulnerabilidades (06/10/2026)

Primer escaneo con Trivy, con Spring Boot 4.1.1 tal cual (run
[37495913227](https://github.com/Lunaa001/-parcial-ing-soft/actions/runs/37495913227), commit `711f3f9`).

| Parte de la imagen | Resultado |
|---|---|
| Sistema operativo (Debian 13.7, Distroless) | 0 |
| `app.jar` | **13 (3 CRITICAL, 10 HIGH)** |

| Librería | Versión | CVE | Severidad | Problema | Corregido en |
|---|---|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | CVE-2026-65182 | **CRITICAL** | Se pueden saltear restricciones de seguridad (control de acceso) | 11.0.25 |
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | CVE-2026-65905 | **CRITICAL** | Bypass de autenticación DIGEST por repetición de pedidos | 11.0.25 |
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | CVE-2026-68525 | **CRITICAL** | Acceso no autorizado por bypass de autenticación FORM | 11.0.25 |
| `tools.jackson.core:jackson-core` (Jackson 3) | 3.1.5 | CVE-2026-89407 | HIGH | Denegación de servicio por backtracking de expresión regular | 3.1.7 |
| `tools.jackson.core:jackson-core` (Jackson 3) | 3.1.5 | CVE-2026-89425 | HIGH | Denegación de servicio por crecimiento sin límite de un buffer | 3.1.7 |
| `tools.jackson.core:jackson-databind` (Jackson 3) | 3.1.5 | CVE-2026-68497 | HIGH | Denegación de servicio (CPU) al parsear números | 3.1.6 |
| `tools.jackson.core:jackson-databind` (Jackson 3) | 3.1.5 | CVE-2026-91776 | HIGH | Denegación de servicio por crecimiento sin límite de una caché | 3.1.7 |
| `tools.jackson.core:jackson-databind` (Jackson 3) | 3.1.5 | CVE-2026-91777 | HIGH | Denegación de servicio por resolución cuadrática de referencias | 3.1.7 |
| `com.fasterxml.jackson.core:jackson-core` (Jackson 2) | 2.21.5 | CVE-2026-89407 | HIGH | Igual que en Jackson 3 | 2.21.7 |
| `com.fasterxml.jackson.core:jackson-core` (Jackson 2) | 2.21.5 | CVE-2026-89425 | HIGH | Igual que en Jackson 3 | 2.21.7 |
| `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) | 2.21.5 | CVE-2026-68497 | HIGH | Igual que en Jackson 3 | 2.21.6 |
| `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) | 2.21.5 | CVE-2026-91776 | HIGH | Igual que en Jackson 3 | 2.21.7 |
| `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) | 2.21.5 | CVE-2026-91777 | HIGH | Igual que en Jackson 3 | 2.21.7 |

Jackson aparece dos veces porque Spring Boot 4 usa Jackson 3, pero la librería de JWT
(`jjwt-jackson`) todavía usa Jackson 2.

### Cómo se corrigió

Spring Boot 4.1.1 era (y sigue siendo) la última versión publicada, así que no había una versión
nueva de Spring Boot con los arreglos. Se fijaron versiones parcheadas con las propiedades del BOM
de Spring Boot, en `build.gradle` (commit `471292b`):

```groovy
// Parches de seguridad sobre las versiones de Spring Boot 4.1.1 (CVEs detectados por Trivy).
// Sacar cuando una version nueva de Spring Boot ya traiga estas versiones o superiores.
ext['tomcat.version'] = '11.0.26'
ext['jackson-bom.version'] = '3.1.7'
ext['jackson-2-bom.version'] = '2.21.7'
```

| Propiedad | Spring Boot 4.1.1 trae | Se fija en |
|---|---|---|
| `tomcat.version` | 11.0.24 | 11.0.26 |
| `jackson-bom.version` (Jackson 3) | 3.1.5 | 3.1.7 |
| `jackson-2-bom.version` (Jackson 2) | 2.21.5 | 2.21.7 |

- Son **parches de la misma línea** (11.0.x, 3.1.x y 2.21.x): no cambian la versión menor, así que el
  riesgo de romper compatibilidad con Spring Boot es mínimo.
- Antes de subir se comprobó dentro del jar que quedaran esas versiones, y se probó el contenedor
  con el perfil cloud: health, `/api/perfumes`, login del admin con JWT (Jackson 2) y un endpoint
  protegido con el token.
- **Hay que sacar estas 3 líneas** cuando se suba a una versión de Spring Boot que ya traiga estas
  versiones o superiores; si no, podrían quedar fijadas versiones más viejas.

### Después: 0 vulnerabilidades HIGH/CRITICAL con los dos escáneres

Último escaneo, versión `v0.4.0` (run
[38323200188](https://github.com/Lunaa001/-parcial-ing-soft/actions/runs/38323200188), commit
`aedb2eb`, 12/10/2026), ya con el agente de Application Insights dentro de la imagen:

| Parte de la imagen | Trivy | Grype |
|---|---|---|
| Sistema operativo (Debian 13.7, Distroless) | **0** | **0** |
| `app.jar` | **0** | **0** |
| `applicationinsights-agent.jar` (agente 3.7.10) | **0** | **0** |
| **Total** | **0 HIGH/CRITICAL** | **"No vulnerabilities found"** |

Grype no encontró nada que Trivy no hubiera encontrado. El primer escaneo limpio con Trivy fue el
run [37527971295](https://github.com/Lunaa001/-parcial-ing-soft/actions/runs/37527971295) (commit
`471292b`, 06/10/2026), y desde entonces todos los runs dieron 0. Como los dos escáneres corren en
cada build con su base de vulnerabilidades actualizada, una vulnerabilidad nueva frenaría el próximo
despliegue.

## Análisis del código: CodeQL

`.github/workflows/codeql.yml` corre CodeQL para Java (`codeql-action` v4.38.3, fijado por commit
`24c54180a607b1449ed407dd24f251e4e9147c8d`) en cada push a `main`, en cada PR y los lunes, con las
consultas `security-and-quality`, sin compilar (`build-mode: none`). Los resultados están en
GitHub → *Security → Code scanning*.

Primer análisis (12/10/2026): **11 alertas**.

| Severidad | Regla | Dónde | Evaluación |
|---|---|---|---|
| high | `java/spring-disabled-csrf-protection` | `SecurityConfig.java:39` | **Falso positivo en este contexto.** La API es stateless (`SessionCreationPolicy.STATELESS`) y el token JWT viaja en el header `Authorization`, no en cookies. CSRF necesita que el navegador mande la credencial solo (cookie), así que no aplica. Spring recomienda desactivarlo en APIs así |
| high | `java/user-controlled-bypass` | `JwtAuthenticationFilter.java:52` | **Falso positivo.** El usuario sale de `parseSignedClaims` con `verifyWith(key)`: jjwt verifica la firma y el vencimiento antes de devolverlo; un token adulterado lanza excepción y no autentica. CodeQL no modela esa verificación |
| medium | `java/log-injection` | `RequestTraceFilter.java:41` | **Real, riesgo bajo.** Se registra la URL que manda el cliente. Tomcat rechaza saltos de línea crudos en la URL y `getRequestURI()` la devuelve sin decodificar (`%0A` queda literal), así que no se pueden falsificar líneas de log. Se podría sanear igual |
| note (8) | `java/deprecated-call`, `java/internal-representation-exposure`, `java/uncaught-number-format-exception` | `GroqServiceImpl`, `Cart`, `ScriptAz.java` | Calidad de código, no seguridad |

## Diferencias entre las herramientas

| | **Trivy** | **Grype** | **Snyk** | **CodeQL** |
|---|---|---|---|---|
| **Qué analiza** | imágenes, archivos, repos, IaC (Dockerfile, Terraform), secretos | imágenes y archivos (dependencias y SO) | dependencias, código (Snyk Code), contenedores, IaC | **código fuente** propio |
| **Tipo de análisis** | composición de software (SCA): busca versiones con CVEs conocidos | SCA | SCA + análisis estático (SAST) | análisis estático (SAST): sigue el flujo de datos del código |
| **Encuentra** | librerías y paquetes del SO vulnerables, configuraciones inseguras, secretos | librerías y paquetes del SO vulnerables | lo de Trivy/Grype + vulnerabilidades en el código | inyecciones, validaciones faltantes, criptografía débil, errores de lógica de seguridad |
| **No encuentra** | errores en nuestro código | errores en nuestro código, IaC, secretos | — | librerías vulnerables (eso lo hacen los otros) |
| **Base de vulnerabilidades** | propia de Aqua (NVD, GitHub Advisories, avisos de distros) | propia de Anchore (NVD, GitHub Advisories, distros) | propia de Snyk (curada, con análisis de alcance) | consultas (queries) de GitHub y la comunidad |
| **Licencia / costo** | open source, gratis | open source, gratis | comercial; plan gratis con límite de escaneos | gratis en repos públicos; en privados requiere GitHub Advanced Security |
| **Necesita cuenta o token** | no | no | sí (token de Snyk) | no (integrado en GitHub) |
| **En este proyecto** | ✅ en el pipeline | ✅ en el pipeline | ❌ no se usa: pide cuenta y token, y lo que aporta en SCA ya lo cubren Trivy y Grype | ✅ `codeql.yml` |

**Por qué dos escáneres de imagen:** Trivy y Grype usan bases de vulnerabilidades distintas y
detectan las librerías de forma distinta (por ejemplo, cómo identifican un jar empaquetado dentro de
otro). Lo que a uno se le escapa, el otro lo puede encontrar. CodeQL cubre lo que ninguno de los dos
ve: los errores de seguridad en el código que escribimos nosotros.

## Auditoría complementaria a mano

OWASP Dependency-Check revisa solo las dependencias Java contra la base NVD. Necesita una API key
gratuita de NVD en la variable `NVD_API_KEY`:

```bash
NVD_API_KEY=<tu-key> ./gradlew dependencyCheckAnalyze   # reporte en build/reports/dependency-check-report.html
```
