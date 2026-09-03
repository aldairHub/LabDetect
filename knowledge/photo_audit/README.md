# Auditoría automática de las fotografías

- Imágenes procesadas: **3.569 de 3.569**.
- Clases de detección: **25**.
- Archivos corruptos: **0**.
- Resultado completo reanudable: `ocr_results.jsonl`.
- Selección de placas y textos útiles: `nameplate_candidates.json`.

## Correcciones confirmadas por placa

| Clase | Identificación física confirmada |
|---|---|
| Manta de calentamiento | Barnstead Electrothermal EM0250/CEX1, 115 V, 150 W |
| Mufla | NEY M-525 Series II |
| Baño María | Sheldon Manufacturing / SHEL-LAB 1203 |
| Calorímetro | Parr 1341 Plain Jacket con termómetro digital 6775 |

El OCR se usó como localizador de placas, no como autoridad documental. Los textos
inconsistentes se descartaron y ningún número inferido por una sola lectura débil se
promovió a modelo confirmado. Cuando las fotos no muestran la placa completa, el
estado permanece pendiente en `knowledge/manual_manifest.json`.
