"""Divide el manual general entregado por el laboratorio por clase de equipo."""

import argparse
import json
from pathlib import Path

from pypdf import PdfReader, PdfWriter


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "knowledge" / "manuals" / "general"

# Paginas del PDF (numeracion humana). La portada se conserva en cada salida
# porque contiene la advertencia de que el contenido es una referencia general.
EQUIPMENT_PAGES = {
    "agitador_calentador": [3, 11],
    "autoclave": [4],
    "balanza_analitica": [5],
    "bano_maria": [6],
    "bloque_digestor": [7],
    "bomba_vacio": [8],
    "cabina_flujo_laminar": [9],
    "sorbona_cabina_extraccion_gases": [10],
    "calorimetro": [12],
    "centrifuga": [13],
    "contador_colonias": [14],
    "desecador": [15],
    "desmineralizador_agua": [16],
    "destilador_agua": [17],
    "destilador_proteina": [18],
    "estufa": [19],
    "extractor_fibra": [20],
    "extractor_grasa": [21],
    "incubadora": [22],
    "manta_calentamiento": [23],
    "molino_pulverizador": [24],
    "mufla": [25],
    "phmetro": [26],
    "refractometro": [27],
    "viscosimetro": [28],
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Importa el manual general por equipo.")
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    reader = PdfReader(args.source)
    if len(reader.pages) < max(max(pages) for pages in EQUIPMENT_PAGES.values()):
        raise SystemExit("El manual no contiene las 28 paginas esperadas.")

    args.output.mkdir(parents=True, exist_ok=True)
    report = {}
    for equipment_id, page_numbers in EQUIPMENT_PAGES.items():
        writer = PdfWriter()
        writer.add_page(reader.pages[0])
        for page_number in page_numbers:
            writer.add_page(reader.pages[page_number - 1])
        destination = args.output / f"manual_general_{equipment_id}.pdf"
        with destination.open("wb") as stream:
            writer.write(stream)
        report[equipment_id] = {
            "file": str(destination.relative_to(ROOT)).replace("\\", "/"),
            "source_pages": [1, *page_numbers],
            "page_count": len(writer.pages),
        }

    report_path = args.output / "import_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Creadas {len(report)} referencias en {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
