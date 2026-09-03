# Servicio documental de LabDetect

Este servicio mantiene la clave de OpenAI fuera del APK y crea un almacén vectorial
independiente para cada variante física. Así, una pregunta sobre un equipo nunca busca
en los manuales de otro.

La carga acepta los manuales exactos declarados como `verified` en
`knowledge/manual_manifest.json` y las referencias generales entregadas por el
laboratorio en `knowledge/general_manual_manifest.json`. Comprueba la cabecera PDF
y conserva su SHA-256.
Los manuales exactos deben colocarse en `knowledge/manuals/` con los nombres definidos por
`local_file` o `local_files`. El registro generado `backend/vector_stores.json` no se
versiona porque contiene identificadores del entorno de OpenAI.

El manual general se divide por clase en `knowledge/manuals/general/`; cada PDF
conserva la portada con su advertencia y únicamente las páginas del equipo que le
corresponde. Así, `file_search` no puede recuperar contenido de otra clase. Cuando
existe un manual exacto, ambos documentos conviven en el mismo almacén de la variante.

`fetch_manuals.py` intenta descargar las fuentes que entregan un PDF real y rechaza
automáticamente páginas HTML disfrazadas de descarga. `ingest_manuals.py` vuelve a
validar cada archivo antes de subirlo; los documentos pendientes o no verificados no
entran en la base de respuestas.

Variables del servidor:

- `OPENAI_API_KEY`: obligatoria solo en el servidor.
- `OPENAI_MODEL`: opcional; por defecto `gpt-5.4-mini`.
- `LABDETECT_VECTOR_STORES`: ruta opcional al registro generado.

El archivo local `backend/.env` ya está preparado y excluido de Git. La clave se
pega únicamente allí; `app.py` e `ingest_manuals.py` la cargan automáticamente.

En Windows, `ACTIVAR_IA_DOCUMENTAL.cmd` prepara el entorno fuera del repositorio,
descarga los PDF directos disponibles y crea los índices por variante. Después,
`INICIAR_SERVICIO_IA.cmd` levanta el servicio local sin volver a indexar.

El inicio normal usa `server.py`, construido solo con Python estándar; no necesita
instalar paquetes. Los cinco manuales ya cargados en `vector_stores.json` quedan
disponibles inmediatamente al iniciar el servicio.

La app recibe únicamente la URL HTTPS del servicio mediante
`KNOWLEDGE_API_URL=https://...` en `local.properties`. No se introduce una clave de
OpenAI en Android.

El endpoint `POST /v1/equipment/ask` exige `variant_id` y `question`. Fuerza
`file_search`, comprueba que hubo fragmentos recuperados y, si la documentación no
respalda la respuesta, devuelve la frase segura definida en el servidor. La respuesta
no recita fuentes porque la interfaz presenta el manual por separado.
