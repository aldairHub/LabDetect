<h1 align="center">
  <img src="https://api.iconify.design/tabler/scan-eye.svg?color=%2335d05b" width="30" valign="middle"/>
  LabDetect
</h1>

<p align="center">
  Asistente móvil inteligente para detectar equipos del Laboratorio de Bromatología de la UTEQ y consultar su información técnica mediante voz o texto.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=flat&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/CameraX-137A22?style=flat&logo=android&logoColor=white" alt="CameraX">
  <img src="https://img.shields.io/badge/YOLO26n-35D05B?style=flat&logo=yolo&logoColor=black" alt="YOLO26n">
  <img src="https://img.shields.io/badge/ONNX%20Runtime-005CED?style=flat&logo=onnx&logoColor=white" alt="ONNX Runtime">
  <img src="https://img.shields.io/badge/Python-3776AB?style=flat&logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/PyTorch-EE4C2C?style=flat&logo=pytorch&logoColor=white" alt="PyTorch">
  <img src="https://img.shields.io/badge/Roboflow-6706CE?style=flat&logo=roboflow&logoColor=white" alt="Roboflow">
  <img src="https://img.shields.io/badge/OpenAI-412991?style=flat&logo=openai&logoColor=white" alt="OpenAI">
  <img src="https://img.shields.io/badge/Material%203-79BC35?style=flat&logo=materialdesign&logoColor=white" alt="Material 3">
  <img src="https://img.shields.io/badge/Min%20SDK-26-137A22?style=flat" alt="Min SDK 26">
  <img src="https://img.shields.io/github/last-commit/aldairHub/LabDetect" alt="Last Commit">
  <img src="https://img.shields.io/github/repo-size/aldairHub/LabDetect" alt="Repo Size">
</p>

---

## <img src="https://api.iconify.design/tabler/info-circle.svg?color=%2335d05b" width="20" valign="middle"/> Descripción

**LabDetect** es una aplicación Android que detecta, localiza e identifica en tiempo real **25 clases de equipos** del Laboratorio de Bromatología de la UTEQ. La cámara se procesa con **CameraX** y el modelo **Ultralytics YOLO26n**, exportado a **ONNX**, se ejecuta completamente dentro del teléfono mediante **ONNX Runtime Mobile**.

Después de detectar un equipo, el usuario puede tocar el micrófono para comenzar, hablar con normalidad y tocarlo nuevamente para enviar, o escribir una pregunta. La aplicación fija el último equipo detectado como contexto, recupera su manual local y construye un prompt restringido para la **OpenAI Responses API**. La respuesta es breve, técnica y conversacional; se reproduce con la voz **Marin** de OpenAI y utiliza **Android TextToSpeech** cuando no hay conexión.

La aplicación también conserva fichas técnicas, manuales, consultas básicas y favoritos de forma local para seguir siendo útil sin internet.

## <img src="https://api.iconify.design/tabler/school.svg?color=%2379bc35" width="20" valign="middle"/> Datos académicos

- <img src="https://api.iconify.design/tabler/building-bank.svg?color=%2335d05b" width="16" valign="middle"/> **Universidad:** Universidad Técnica Estatal de Quevedo
- <img src="https://api.iconify.design/tabler/building.svg?color=%2379bc35" width="16" valign="middle"/> **Facultad:** Ciencias de la Computación
- <img src="https://api.iconify.design/tabler/certificate.svg?color=%2334d399" width="16" valign="middle"/> **Carrera:** Software
- <img src="https://api.iconify.design/tabler/flask.svg?color=%23fb923c" width="16" valign="middle"/> **Materia:** Aplicaciones Móviles — Examen Final, Tema 2: Laboratorio de Bromatología

## <img src="https://api.iconify.design/tabler/users-group.svg?color=%23facc15" width="20" valign="middle"/> Integrantes — Grupo 2 (CHRS)

- Joseph Calderón Saltos
- Humberto Herrera Barco
- Eduardo Reinoso Vélez
- John Silva Triviño

## <img src="https://api.iconify.design/tabler/cpu.svg?color=%2335d05b" width="20" valign="middle"/> Tecnologías

| Tecnología | Uso dentro de LabDetect |
|---|---|
| **Kotlin** | Lenguaje principal de la aplicación. |
| **Android SDK 34** | Plataforma móvil; compatibilidad desde Android 8.0, API 26. |
| **Gradle Kotlin DSL + JDK 17** | Compilación, configuración y dependencias. |
| **CameraX** | Vista previa, ciclo de vida de la cámara y captura periódica de fotogramas. |
| **Ultralytics YOLO26n** | Modelo entrenado para reconocer los 25 equipos. |
| **Python + PyTorch + CUDA** | Entrenamiento y evaluación acelerados localmente con la GPU NVIDIA. |
| **Roboflow / formato YOLO** | Organización, revisión y exportación inicial del dataset etiquetado. |
| **ONNX Runtime Android** | Inferencia del modelo dentro del celular, sin enviar imágenes a servidores. |
| **Material Design 3** | Interfaz moderna, accesible y con tema oscuro verde institucional. |
| **Navigation Component** | Navegación entre cámara y ficha del equipo. |
| **MVVM + LiveData + Coroutines** | Separación de interfaz, estado, detección y consultas asíncronas. |
| **OpenAI Responses API** | Generación de respuestas técnicas con el manual del equipo como contexto. |
| **OpenAI Web Search** | Complemento técnico opcional cuando el manual no cubre una información general. |
| **OpenAI Audio Speech** | Voz neuronal **Marin** para leer las respuestas naturalmente. |
| **Android SpeechRecognizer** | Conversión de la pregunta hablada a texto en español de Ecuador, con vocabulario de laboratorio. |
| **Android TextToSpeech** | Voz de respaldo cuando OpenAI o internet no están disponibles. |
| **JSON local + SharedPreferences** | Catálogo, manuales, características y favoritos offline. |

## <img src="https://api.iconify.design/tabler/trophy.svg?color=%23facc15" width="20" valign="middle"/> Funcionalidades

- <img src="https://api.iconify.design/tabler/video.svg?color=%2335d05b" width="16" valign="middle"/> Detección de equipos en tiempo real con cuadros delimitadores.
- <img src="https://api.iconify.design/tabler/device-mobile.svg?color=%2379bc35" width="16" valign="middle"/> Inferencia YOLO completamente local mediante ONNX Runtime.
- <img src="https://api.iconify.design/tabler/zoom-in.svg?color=%2335d05b" width="16" valign="middle"/> Segunda pasada automática sobre el centro de la imagen para mejorar detecciones lejanas.
- <img src="https://api.iconify.design/tabler/percentage.svg?color=%23f472b6" width="16" valign="middle"/> Porcentaje de confianza y detección simultánea de varios equipos.
- <img src="https://api.iconify.design/tabler/microphone.svg?color=%23ef4444" width="16" valign="middle"/> Un toque para comenzar a escuchar y otro para enviar la pregunta.
- <img src="https://api.iconify.design/tabler/message-chatbot.svg?color=%23fb923c" width="16" valign="middle"/> Preguntas por voz o texto con contexto documental del equipo detectado.
- <img src="https://api.iconify.design/tabler/volume.svg?color=%2379bc35" width="16" valign="middle"/> Lectura natural con OpenAI Marin y respaldo con Android TTS.
- <img src="https://api.iconify.design/tabler/file-description.svg?color=%23a78bfa" width="16" valign="middle"/> Fichas, características y manuales disponibles offline.
- <img src="https://api.iconify.design/tabler/star.svg?color=%23facc15" width="16" valign="middle"/> Equipos favoritos guardados localmente.
- <img src="https://api.iconify.design/tabler/shield-check.svg?color=%23ef4444" width="16" valign="middle"/> El asistente rechaza preguntas ajenas al equipo enfocado y evita inventar procedimientos peligrosos.

## <img src="https://api.iconify.design/tabler/arrows-exchange.svg?color=%2335d05b" width="20" valign="middle"/> Flujo de funcionamiento

```mermaid
flowchart TD
    A[CameraX captura un fotograma] --> B[Preprocesamiento 640x640 RGB]
    B --> C[YOLO26n + ONNX Runtime en el celular]
    C --> D[Clase, confianza y cuadro de detección]
    D --> E[Se fija el equipo al iniciar la pregunta]
    E --> F{Entrada del usuario}
    F -->|Voz| G[Android SpeechRecognizer es-EC]
    F -->|Texto| H[Pregunta escrita]
    G --> I[Transcripción con vocabulario técnico]
    H --> I
    I --> J{¿Hay internet y API configurada?}
    J -->|Sí| K[Manual local + reglas + pregunta]
    K --> L[OpenAI Responses API]
    L --> M[Respuesta breve y restringida al equipo]
    M --> N[OpenAI TTS, voz Marin]
    J -->|No| O[Respuesta desde el manual local]
    O --> P[Android TextToSpeech]
```

### ¿Cómo se conecta la IA?

1. **CameraX** entrega una imagen de la cámara a la aplicación.
2. **YOLO26n** identifica el equipo en el propio celular; la fotografía no se envía a OpenAI.
3. Con el primer toque al micrófono, la app congela lógicamente el último equipo detectado y pausa el análisis YOLO; con el segundo toque termina el dictado y procesa la pregunta.
4. **Android SpeechRecognizer** convierte la voz a texto, prioriza español de Ecuador, nombres de equipos y términos del laboratorio.
5. La aplicación recupera de `manual_text.json` el contenido correspondiente al equipo.
6. Se construye un prompt con tres partes: reglas del asistente, pregunta transcrita y contenido del manual.
7. La **Responses API** recibe ese contexto. Debe responder únicamente sobre el equipo, en lenguaje natural, entre dos y cuatro oraciones; si la pregunta no corresponde, la rechaza.
8. El texto se envía a **OpenAI Audio Speech** y se reproduce con la voz **Marin**. Si falla la conexión, se utiliza el contenido y la voz offline de Android.

> Este enfoque es un **RAG documental ligero**: la recuperación del documento ocurre localmente y el fragmento completo del manual correspondiente se inserta como contexto. No depende de un vector store ni de File Search.

## <img src="https://api.iconify.design/tabler/chart-dots.svg?color=%2379bc35" width="20" valign="middle"/> Modelo de detección

| Propiedad | Valor |
|---|---:|
| Modelo | Ultralytics YOLO26n |
| Formato móvil | ONNX |
| Entrada | `1 × 3 × 640 × 640`, RGB normalizado entre 0 y 1 |
| Clases | 25 |
| Imágenes de prueba independientes | 278 |
| Instancias de prueba | 287 |
| Precisión | 92.22 % |
| Recall | 90.68 % |
| mAP50 | 93.46 % |
| mAP50–95 | 89.41 % |
| Mejor época | 35 |
| Ultralytics | 8.4.138 |

El detector aplica *letterbox*, normalización NCHW, umbral de confianza del 25 %, NMS embebido y una segunda inferencia sobre un recorte central cuando la primera detección no supera el 60 %.

## <img src="https://api.iconify.design/tabler/wifi-off.svg?color=%23facc15" width="20" valign="middle"/> Funcionamiento offline y online

| Función | Sin internet | Con internet |
|---|:---:|:---:|
| Cámara y detección YOLO | ✅ | ✅ |
| Cuadros y porcentaje de confianza | ✅ | ✅ |
| Ficha y características | ✅ | ✅ |
| Manual local | ✅ | ✅ |
| Favoritos | ✅ | ✅ |
| Preguntas básicas sobre el manual | ✅ | ✅ |
| Respuesta generativa con OpenAI | — | ✅ |
| Búsqueda web técnica controlada | — | ✅ |
| Voz Android TTS | ✅ | ✅ respaldo |
| Voz neuronal Marin | — | ✅ |

## <img src="https://api.iconify.design/tabler/folder-open.svg?color=%23fbbf24" width="20" valign="middle"/> Estructura del proyecto

```text
LabDetect/
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── equipment_catalog.json          Catálogo y variantes
│       │   ├── manual_text.json                Contenido documental offline
│       │   ├── labdetect_yolo26n.onnx          Modelo de detección
│       │   └── labdetect_yolo26n.metadata.json Clases, entrada y métricas
│       ├── java/com/example/labdetect/
│       │   ├── data/                            ONNX, catálogo, manuales y OpenAI
│       │   ├── domain/                          Entidades y contratos
│       │   ├── speech/                          OpenAI TTS y Android TTS
│       │   ├── viewmodel/                       Estado y lógica MVVM
│       │   ├── CameraFragment.kt                Cámara, voz e interacción
│       │   └── DetailFragment.kt                Ficha, manual y preguntas
│       └── res/                                 Interfaz Material 3 y navegación
├── gradle/libs.versions.toml                    Versiones de dependencias
├── app/build.gradle.kts                         Configuración Android y API
├── settings.gradle.kts
└── README.md
```

## <img src="https://api.iconify.design/tabler/list-check.svg?color=%2334d399" width="20" valign="middle"/> Requisitos

- Android Studio compatible con AGP 9.2.1.
- JDK 17.
- Android SDK 34.
- Dispositivo físico Android 8.0 o superior con cámara y micrófono.
- Internet para respuestas y voz de OpenAI.
- Crédito y una clave válida de OpenAI API para el modo online.

## <img src="https://api.iconify.design/tabler/download.svg?color=%2360a5fa" width="20" valign="middle"/> Instalación

**1. Clonar el repositorio**

```bash
git clone https://github.com/aldairHub/LabDetect.git
cd LabDetect
```

**2. Abrir el proyecto en Android Studio**

Abre la carpeta raíz `LabDetect` y espera la sincronización de Gradle.

**3. Configurar OpenAI localmente**

Agrega estas propiedades a `local.properties` sin subir ese archivo a Git:

```properties
OPENAI_API_KEY=tu_clave_api
OPENAI_MODEL=gpt-5.4-mini
OPENAI_TTS_MODEL=gpt-4o-mini-tts
```

**4. Ejecutar**

Conecta un teléfono, concede permisos de cámara y micrófono y ejecuta el módulo `app` desde Android Studio.

> **Seguridad:** en el prototipo académico la clave se incorpora a `BuildConfig` y la APK llama directamente a OpenAI. Esto permite distribuir una demostración, pero una clave dentro de una APK puede extraerse. Para producción pública se debe mover la llamada a un backend propio con autenticación, límites de consumo y rotación de claves.

## <img src="https://api.iconify.design/tabler/lock.svg?color=%23ef4444" width="20" valign="middle"/> Privacidad y alcance

- Las imágenes de la cámara se procesan localmente y no se envían a OpenAI.
- En el modo online se envían la pregunta, el nombre del equipo y el texto de su manual para generar la respuesta.
- El reconocimiento de voz depende del proveedor de reconocimiento configurado en Android y puede usar internet.
- La búsqueda web está disponible únicamente como apoyo técnico; el manual local sigue siendo la fuente principal.
- El asistente está restringido al equipo enfocado y no debe responder temas ajenos.

## <img src="https://api.iconify.design/tabler/license.svg?color=%23f87171" width="20" valign="middle"/> Licencia

Proyecto académico desarrollado para la asignatura de Aplicaciones Móviles de la Universidad Técnica Estatal de Quevedo.
