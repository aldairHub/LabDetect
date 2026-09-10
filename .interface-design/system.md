# LabDetect · Cámara de reconocimiento

## Dirección

Asistente de laboratorio preciso y calmado. La cámara es el foco; la interfaz solo confirma, pregunta y responde sin competir con la imagen.

## Paleta y profundidad

- Fondo: grafito verdoso `#07130D`.
- Superficies: vidrio ahumado `#10271B` con borde blanco/transparente discreto.
- Acción y detección confirmada: esmeralda `#46EE70`.
- Candidato todavía no confirmado: ámbar tenue.
- Texto: blanco frío y gris acero para información secundaria.
- Profundidad: superficies translúcidas y bordes suaves; sin sombras pesadas ni neón.

## Patrón de escáner

- Una sola caja por equipo.
- Borde completo fino y semitransparente; esquinas verdes más marcadas al confirmar.
- Etiqueta pequeña pegada a la esquina superior izquierda: nombre y confianza.
- Amarillo mientras ajusta, verde tras confirmación.
- La caja desaparece rápidamente cuando el equipo abandona el encuadre.

## Jerarquía de cámara

- La cámara ocupa toda la superficie; los controles flotan encima, sin oscurecer su mitad inferior.
- Arriba: palabra de marca pequeña (`Bromatología · UTEQ`) y un único acceso a favoritos.
- No usar botones de galería, obturador o “Escanear”: la detección es automática.

## Conversación

- Abajo: compositor compacto, no un panel de chat grande.
- No incluir sugerencias como `Cómo calibrarla`: fueron descartadas expresamente.
- Pregunta contextual discreta; se oculta durante la consulta y cuando aparece el teclado.
- Campo: `Escribe o pregunta por voz`, micrófono integrado y envío circular esmeralda.
- Tarjeta con miniatura real, resumen de hasta tres líneas, `Ver detalle` y cierre manual. Tocar el texto abre su contenido completo; `Ver detalle` abre la ficha del equipo consultado.
- Durante consulta: estado discreto, vibración corta en acciones y sin overlays grandes.

## Medidas

- Cuadrícula de 4dp.
- Controles táctiles mínimos de 48dp.
- Radio: 10dp en caja, 18-22dp en superficies, compositor redondo.
- Animaciones de entrada/salida entre 160-220ms, solo opacidad y traslación.

## Aplicación del sistema

- Cámara: tipografía de sistema 14sp para contenido, 18sp para marca; etiqueta de detección 13sp.
- Compositor centrado, ancho máximo 600dp, micrófono a la izquierda y envío circular a la derecha.
- Ficha y paneles: ancho máximo 720dp, contenido desplazable, mismas superficies grafito y acento esmeralda.
- Favoritos y recientes comparten buscador, miniaturas y estados vacíos.
- Manual local completo en panel de lectura seleccionable. Los PDF abiertos en otra aplicación conservan la interfaz del visor externo.
- Blur limitado al fondo de las tarjetas en Android 12+, a partir de una copia reducida de cámara cada 800ms. En versiones anteriores se utiliza transparencia sin desenfoque.
- Los tamaños usan dp/sp; el teclado reduce el área disponible y oculta la tarjeta para priorizar la escritura.

## Verificación (10 de septiembre de 2026)

- Compilación debug y pruebas unitarias.
- Cuatro pruebas de interfaz Robolectric: móvil 360dp, área reducida por teclado, móvil 320dp con texto 150%, tablet 800dp y paneles de lectura/favoritos.
- Renders inspeccionados en `app/build/reports/ui/`. Son pruebas de layout sin cámara física; no demuestran velocidad de detección ni enfoque en un dispositivo real.
