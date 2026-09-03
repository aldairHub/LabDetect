# Base documental de equipos

Esta carpeta registra la correspondencia entre el equipo físico del laboratorio,
su variante exacta y su documentación. El archivo canónico es
`manual_manifest.json`; la app conserva una copia operativa de esos datos en
`app/src/main/assets/equipment_catalog.json`.

Un documento solo se marca como `verified` cuando la marca, el modelo y la forma
del equipo coinciden con las fotografías del dataset. Los equipos con
`model_pending` no deben recibir instrucciones operativas específicas de un modelo.

## Resultado de la auditoría fotográfica

Se procesaron las 3.569 fotografías del conjunto con OCR y se conservaron los
resultados reanudables en `photo_audit/`. El catálogo diferencia 28 variantes físicas
dentro de las 25 clases de YOLO. El barrido completo corrigió cuatro identificaciones:
Barnstead Electrothermal EM0250/CEX1, NEY M-525 Series II, SHEL-LAB 1203 y el
conjunto Parr 1341 Plain Jacket + termómetro digital 6775.

En total hay 15 variantes con modelo y manual comprobados, 5 con modelo confirmado
pero PDF exacto pendiente, 6 con marca o familia pendiente de submodelo, un sistema
antiguo aún sin identificar y un equipo genérico. Las dos autoclaves, dos balanzas y
dos estufas continúan separadas para impedir cruces de documentación.

Los enlaces comprobados están disponibles desde **Abrir manual**. Los PDF completos
y el índice vectorial se mantienen fuera del APK. `backend/` implementa el servicio
RAG: recibe el identificador exacto de variante, busca únicamente en el almacén de
esa variante y no responde si los PDF no contienen evidencia. La clave de OpenAI se
usa exclusivamente en el servidor.
