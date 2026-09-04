# Zig Edu Compiler

Aplicación educativa en Java para estudiar las primeras etapas de un compilador sobre un subconjunto deliberadamente pequeño de Zig. La versión actual realiza únicamente análisis léxico, análisis sintáctico y construcción de un AST; todavía no asigna tipos, valida ámbitos ni ejecuta código.

## Ejecutar

Requisitos: JDK 21 o posterior y Maven 3.9 o posterior.

```bash
mvn test
mvn package
java -jar target/zig-edu-compiler-0.1.0-SNAPSHOT.jar
```

JavaCC genera el parser durante la fase `generate-sources`; no deben editarse manualmente los archivos de `target/generated-sources/javacc`.

## Interfaz

La ventana incluye:

- editor de código con números de línea, conteo de líneas y posición del cursor;
- apertura de archivos `.zig` y `.txt`, guardado y guardado como;
- tabla del análisis léxico con lexema, tipo y posición inicial/final de cada token;
- tabla secuencial del análisis sintáctico, con construcciones del AST, detalle y ubicación;
- paneles independientes para errores léxicos y sintácticos;
- diagnósticos con lo esperado, lo encontrado, línea, columna y fragmento señalado.
- recuperación léxica para informar varios caracteres inválidos en una misma ejecución;
- indicador verde/rojo y alerta final con el resultado de cada análisis manual.

Los elementos inválidos también aparecen en la tabla con tipo `ERROR_LEXICO` y fondo rojo. Si un carácter Unicode no admitido forma parte de una secuencia de identificador —por ejemplo `niño`— se informa la secuencia completa como un solo lexema inválido.

Al iniciar, la aplicación carga una copia sin asociar a archivo del ejemplo completo de más de 350 líneas y lo analiza automáticamente. Esto permite revisar de inmediato las dos tablas; para modificar el archivo de ejemplo original debe abrirse explícitamente desde `examples/`.

Cada ejecución del análisis sobrescribe estos archivos dentro de `out/`:

- `errores_lexicos.txt`
- `errores_sintacticos.txt`
- `tokens.txt`
- `ast.txt`

## Ejemplo común

[`examples/todas_las_funciones.zig`](examples/todas_las_funciones.zig) tiene más de 350 líneas y cubre las construcciones principales. Las pruebas comprueban que lo acepta este parser. También se valida con Zig 0.16 mediante:

```bash
zig build-exe examples/todas_las_funciones.zig -fno-emit-bin
```

## Texto y cadenas

Este subconjunto no define un tipo `string` ni reconoce literales con comillas dobles. El texto se representa directamente con arreglos de bytes:

```zig
const greeting = [_]u8{ 'H', 'o', 'l', 'a' };
```

Esto no es idéntico a una cadena de Zig real. En Zig, un literal como `"Hola"` involucra un puntero a un arreglo terminado en cero; modelarlo fielmente obligaría a introducir punteros, sentinel arrays o slices. El arreglo de `u8` mantiene la primera fase coherente con el alcance acordado.

Consulta [`ESPECIFICACION_SUBCONJUNTO.md`](ESPECIFICACION_SUBCONJUNTO.md) para el alcance exacto y las desviaciones conocidas.

## Organización

- `src/main/javacc/ZigEduParser.jj`: tokens, gramática y acciones JavaCC.
- `src/main/java/.../ast`: nodos y visualización del AST.
- `src/main/java/.../analysis`: ejecución, diagnósticos y exportación.
- `src/main/java/.../ui`: interfaz Swing.
- `src/test/java`: pruebas léxicas, sintácticas, del AST y de exportación.
