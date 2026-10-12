# Informe de vulnerabilidades (Trivy)

Escaneo de la imagen Docker de la app (`parcial-ing-soft`) con
[Trivy](https://trivy.dev/), antes y después de corregir las dependencias.

## Cómo se escanea

- **Dónde:** en el pipeline de GitHub Actions (`.github/workflows/ciclo-azure.yml`), en cada push a
  `main` y en el workflow "Levantar", **antes** de subir la imagen al registry. Si encuentra algo, el
  pipeline se frena: la imagen no se publica, el ACR ni se crea y la app sigue con la versión anterior.
- **Herramienta:** `aquasecurity/trivy-action` v0.36.0 (Trivy 0.70.0), fijada al commit
  `ed142fd0673e97e23eac54620cfb913e5ce36c25` y no a un tag, para que nadie pueda cambiar el código que
  corre (en 2026 hubo un incidente en el que se modificaron tags de esa action).
- **Qué revisa:** el sistema operativo de la imagen (Distroless, Debian 13) y las librerías Java
  dentro de `app.jar`.
- **Criterio:** falla con vulnerabilidades **HIGH o CRITICAL que ya tengan parche**
  (`severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: 1`).

## Antes: 13 vulnerabilidades (06/10/2026)

Primer escaneo, con Spring Boot 4.1.1 tal cual (run
[37495913227](https://github.com/Lunaa001/-parcial-ing-soft/actions/runs/37495913227), commit `711f3f9`).

| Imagen | Resultado |
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

## Cómo se corrigió

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

## Después: 0 vulnerabilidades HIGH/CRITICAL

| Imagen | Resultado |
|---|---|
| Sistema operativo (Debian 13.7, Distroless) | **0** |
| `app.jar` | **0** |

Primer escaneo limpio: run
[37527971295](https://github.com/Lunaa001/-parcial-ing-soft/actions/runs/37527971295) (commit
`471292b`, 06/10/2026). Desde entonces, todos los runs del pipeline (push, "Levantar") volvieron a dar
**0 HIGH/CRITICAL**, por ejemplo el del commit `cbf572f` (tag `v0.2.1`). Como Trivy corre en cada
build con la base de vulnerabilidades actualizada, una vulnerabilidad nueva frenaría el próximo
despliegue.

## Auditoría complementaria a mano

OWASP Dependency-Check revisa solo las dependencias Java contra la base NVD. Necesita una API key
gratuita de NVD en la variable `NVD_API_KEY`:

```bash
NVD_API_KEY=<tu-key> ./gradlew dependencyCheckAnalyze   # reporte en build/reports/dependency-check-report.html
```
