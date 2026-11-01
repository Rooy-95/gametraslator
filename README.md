# Traductor de juegos (japonés → español)

App para Android que lee el texto en japonés de la pantalla de un juego y muestra la traducción al español encima, con un botón flotante. Pensada para novelas visuales, pero funciona con cualquier juego que no bloquee las capturas de pantalla (incluidos emuladores).

## Qué hace
- Botón flotante **訳** sobre el juego.
- Lee el japonés con OCR y lo traduce al español.
- Podés elegir una **zona** de la pantalla (por ejemplo, el cuadro de diálogo) o traducir la pantalla completa.
- Se maneja con el botón flotante o con un **botón del mando** (R2 por defecto, se puede cambiar).
- Dos modos: **FIJO** (un toque traduce una vez y la traducción queda fija, sin parpadeo) y **EN VIVO** (revisa la pantalla cada 2, 3 o 4 segundos).
- Traducción sin internet incluida (más literal) y, opcionalmente, traducción con **Gemini** para mejor calidad.

## Requisitos
- Android 11 o superior.
- Internet la primera vez, para bajar el modelo de traducción.
- No funciona con juegos que bloquean las capturas de pantalla.

## Instalación
1. Descargá el archivo **app-debug.apk** desde la sección **Releases** de este repositorio.
2. Abrilo para instalarlo. Si Android o Play Protect lo bloquea, es porque la app no viene de Play Store: permití la instalación de esta fuente o apagá Play Protect un momento (Play Store > tu foto > Play Protect > ⚙️) y volvelo a prender después.
3. Si ya tenías una versión anterior, **desinstalala antes**: cada versión tiene una firma distinta y Android no deja actualizar encima.

## Configuración (una sola vez)
1. Abrí la app y tocá **Abrir Accesibilidad**.
2. Buscá **Traductor de juegos** (en "Apps instaladas" o "Servicios descargados") y activalo.
3. En Android 13 o superior, si dice "ajuste restringido": **Ajustes > Apps > Traductor de juegos > ⋮ > Permitir ajustes restringidos**, y volvé a intentar.
4. Al activarlo aparece el botón **訳** y baja el modelo de traducción. Esperá el aviso "Traductor listo".

## Cómo se usa
| Acción | Qué hace |
|---|---|
| **Un toque** en 訳 (o R2) | Modo FIJO: traduce una vez y deja la traducción. Otro toque la quita. Modo EN VIVO: prende o apaga la traducción en vivo. |
| **Doble toque** en 訳 (o R2 dos veces rápido) | En vivo: congela o reanuda. Con la traducción en vivo apagada: traduce una vez y la deja fija. |
| **Mantener apretado** | Dibujar la zona a traducir. Un toque suelto en esa pantalla borra la zona. |
| **Arrastrar** | Mover el botón. |

**Para novelas visuales:** en la app elegí el modo **FIJO**, marcá la zona del cuadro de texto, y con cada página tocá el botón (o R2) para quitar y volver a traducir.

## Traducción con Gemini (opcional)
Mejora mucho los diálogos y las expresiones. Es gratis dentro de los límites que fije Google.
1. Entrá a `aistudio.google.com/app/apikey` con tu cuenta de Google y creá una clave.
2. Pegala en el campo **Clave de Gemini** de la app y tocá **Guardar clave y modelo**.
3. Tocá **Probar Gemini** para comprobar que anda. Si el modelo ya no existe, la app prueba otros sola y se queda con el que funcione.

La clave es personal: no la compartas con nadie. Si falla o se acaba el cupo diario, la app sigue con el traductor sin internet.

## Privacidad
- La app usa el **servicio de Accesibilidad** solo para sacar capturas de la pantalla, dibujar la traducción encima y detectar el botón del mando que elegís.
- No guarda las capturas ni recopila datos.
- Sin clave de Gemini, el texto se procesa en el dispositivo. Con la clave, el **texto en japonés leído del juego** se envía a Google para traducirlo (en la capa gratuita, Google puede usarlo para mejorar sus productos). No se envían imágenes.

## Problemas comunes
- **No aparece el botón 訳:** revisá que el servicio esté activado en Accesibilidad.
- **No traduce nada:** el juego puede bloquear las capturas, o el texto no se lee bien. Probá marcando solo la zona del cuadro de texto.
- **R2 no responde:** algunos mandos mandan R2 como gatillo analógico. Usá **Cambiar botón del mando** y elegí otro botón.
- **La traducción es literal o rara:** configurá la clave de Gemini.
- **Gemini falla:** tocá **Probar Gemini** y fijate en el mensaje de error (clave mal copiada, sin internet o cupo agotado).

## Limitaciones
- En modo EN VIVO la traducción desaparece un instante en cada revisión (por eso existe el modo FIJO).
- Puede leer mal el texto muy decorado, muy chico o en vertical.
- Probada en una tablet Samsung con Android 14; en otros dispositivos puede variar.

## Aviso
Proyecto personal sin relación con ningún juego ni empresa. Usala solo con juegos que tengas legalmente.
