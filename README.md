> Despliegue (Docker, Azure Container Apps y CI/CD): ver [DEPLOY.md](DEPLOY.md).

# AI Perfume Advisor

Sistema inteligente de recomendación de perfumes que funciona como un asistente virtual de ventas. Permite interpretar las preferencias del usuario mediante lenguaje natural y sugerir productos personalizados disponibles en el sistema.

---

## Descripción

AI Perfume Advisor es una solución backend desarrollada en Java con Spring Boot que gestiona productos de perfumería y proporciona recomendaciones basadas en los gustos del usuario. El sistema expone una API REST que puede ser consumida por aplicaciones cliente como interfaces móviles o web.

---

## Arquitectura

El sistema está diseñado bajo una arquitectura en capas:

- Controller → Manejo de endpoints REST
- Service → Lógica de negocio y procesamiento
- Repository → Acceso a datos
- Model → Representación de entidades

---

## Tecnologías utilizadas

- Java
- Spring Boot
- Gradle
- API REST

---

## 📁 Estructura del proyecto

src/main/java/com/iaperfumeadvisor/

- controller
- service
- model
- repository

src/main/resources/

- application.properties

---

## Funcionalidades del sistema

### - Gestión de perfumes (CRUD)
- Crear perfumes
- Obtener lista de perfumes
- Obtener perfume por ID
- Actualizar perfumes
- Eliminar perfumes

---

### - Filtrado de productos
- Filtrado por stock disponible
- Filtrado por estado (activo/inactivo)

---

### - Procesamiento de mensajes
- Recepción de mensajes del usuario
- Normalización de texto
- Identificación de palabras clave
- Detección de preferencias

---

### - Sistema de recomendación
- Análisis de preferencias del usuario
- Comparación con datos de perfumes
- Ordenamiento por relevancia
- Selección de mejores coincidencias

---

### - Generación de respuestas
- Construcción de respuestas en lenguaje natural
- Explicación personalizada de recomendaciones
- Inclusión de productos sugeridos

---

### - Endpoint de chat
- Recepción de mensajes del usuario
- Generación de respuesta automática
- Retorno de productos recomendados

---

### - Integración con canales de venta
- Generación de enlace de compra
- Integración con WhatsApp
- Creación de mensajes automáticos

---

### - Validaciones y seguridad
- Validación de datos de entrada
- Control de campos obligatorios
- Manejo de errores
- Sanitización de inputs

---

## 📡 API REST

### - Endpoints principales

- GET /api/perfumes
- GET /api/perfumes/{id}
- POST /api/perfumes
- PUT /api/perfumes/{id}
- DELETE /api/perfumes/{id}

---

### - Endpoint de chat

POST /api/chat

Entrada:

{
"mensaje": "Me gustan perfumes dulces"
}

Salida:

{
"respuesta": "Te recomiendo opciones con notas dulces...",
"productos": [
{
"nombre": "Perfume X",
"descripcion": "Ideal para uso nocturno",
"precio": 100
}
]
}

---

## Ejecución del proyecto

En Windows:
gradlew.bat bootRun

En Linux/Mac:
./gradlew bootRun

