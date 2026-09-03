# IA para LabDetect

Este directorio conserva únicamente documentación y utilidades ligeras relacionadas
con la integración. El procesamiento pesado se ejecuta fuera del repositorio en
`C:\Users\Administrator\Documents\LabDetect-YOLO`.

## Estado actual

- Objetivo confirmado: detección de objetos con cajas, clase y confianza.
- Modelo entrenado: Ultralytics YOLO26 Nano (`yolo26n.pt`).
- Entrenamiento completado localmente con la RTX 5060 Ti de 16 GB.
- Integración completada: ONNX Runtime local en Android, sin una API de detección.
- Acceso confirmado: la exportación completa de `Compartido conmigo/Entrenamiento TM`
  contiene 28 carpetas raíz y 3.569 imágenes JPG.
- Clases consolidadas: 25; se fusionaron las carpetas duplicadas de autoclave,
  extractor de fibra y placa calefactora con agitador.
- Auditoría: 3.569 imágenes válidas, 0 corruptas, 0 duplicados exactos y 210
  grupos visualmente cercanos, todos dentro de su misma clase.
- Autoetiquetado Florence-2 + SAM 2.1: 3.030 imágenes aceptadas y 539 reservadas
  para revisión selectiva en Label Studio.
- Dataset final agrupado: 2.430 train, 302 val y 298 test.
- Resultado preliminar sobre test pseudoetiquetado: precisión 0,736, recall 0,750,
  mAP50 0,788 y mAP50-95 0,578.
- El modelo ONNX real está en `app/src/main/assets`; CameraX dibuja cajas, clase y
  confianza. El APK debug y sus pruebas unitarias compilan correctamente.
- Se comparó un segundo entrenamiento YOLO26s con 102 imágenes adicionales. Se
  conservó YOLO26n porque mantuvo mejor mAP50 (0,788 frente a 0,771) y es más ligero.
- Voz gratuita Android integrada: selección automática de español latino, listado
  de voces TTS instaladas, prueba desde Configuración y lectura natural de respuestas.
- Catálogo de 25 clases conectado a variantes físicas del laboratorio. Las estufas
  Memmert digital y de perillas se mantienen separadas para no mezclar manuales.
- Identificación visual exacta confirmada: Labconco Goldfisch 3500100, J.P. SELECTA
  DOSI-FIBER 4000623 (6 plazas) y FOSS Cyclotec 1093.
- OCR de placas completado sobre las 3.569 fotos. Se corrigieron la manta EM0250/CEX1,
  mufla NEY M-525 Series II, baño María SHEL-LAB 1203 y sistema Parr 1341/6775.
- Gemini fue retirado del cliente. La app envía únicamente el ID de variante a un
  servicio OpenAI File Search protegido y no contiene claves de proveedor.
- El backend fuerza búsqueda en los PDF de una sola variante y devuelve una respuesta
  segura cuando los manuales no contienen la información.
- Pendiente externo: obtener los PDF que los fabricantes restringen y configurar la
  clave/URL del servicio antes de activar las respuestas documentales.
- Inventario inicial: `reports/drive_inventory.md` (archivo local ignorado por Git).

El formato móvil elegido es ONNX. Falta medir velocidad y comportamiento con la
cámara de un teléfono Android real y mejorar las clases débiles mediante la cola
selectiva de Label Studio.

## Estructura inicial

```text
ai/
├── README.md
└── scripts/
    └── check_environment.py
```

Los datasets, pesos, cachés, ejecuciones y el entorno Python se guardan en el
workspace externo para no contaminar ni aumentar el repositorio Android.

## Comprobación segura

El siguiente comando solo consulta el equipo y las librerías disponibles; no instala
nada y no inicia ningún entrenamiento:

```powershell
python ai/scripts/check_environment.py
```

El baseline ya fue entrenado e integrado. La revisión humana de las 539 imágenes no
es obligatoria para probar la app, pero sí es el siguiente paso de mejora de calidad.

La hoja de ruta completa está en
`C:\Users\Administrator\Documents\LabDetect-YOLO\ROADMAP.md`.
