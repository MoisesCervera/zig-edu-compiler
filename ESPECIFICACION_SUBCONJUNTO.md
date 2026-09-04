# Especificación del subconjunto educativo de Zig

## Propósito de esta fase

La aplicación reconoce la forma de un programa y construye su árbol sintáctico. Una entrada aceptada significa que sus tokens y su estructura pertenecen a esta gramática; todavía no significa que el programa sea semánticamente correcto.

Por tanto, en esta fase aún no se comprueba:

- declaración previa, duplicidad ni alcance de nombres;
- compatibilidad de tipos;
- mutabilidad de `const` y `var`;
- cantidad o tipos de argumentos en llamadas;
- validez del objetivo de una asignación;
- caminos de retorno ni uso contextual de `break` y `continue`;
- división entre cero, límites de arreglos o desbordamientos.

## Elementos incluidos

### Unidades y declaraciones

- una o más funciones `fn`, opcionalmente `pub`;
- parámetros con tipo y retorno obligatorio;
- declaraciones locales `const` y `var`, con tipo opcional e inicializador obligatorio;
- tipos primitivos `i32`, `f64`, `u8`, `bool` y `void`;
- arreglos de tamaño fijo `[N]T`.

### Valores y expresiones

- enteros decimales, números de punto flotante, booleanos y caracteres;
- arreglos `[N]T{ ... }` y `[_]T{ ... }`;
- identificadores, llamadas, índices `value[index]` y longitud `value.len`;
- operadores aritméticos `+`, `-`, `*`, `/`, `%`;
- comparaciones `==`, `!=`, `<`, `<=`, `>`, `>=`;
- operadores lógicos `and`, `or`, `!`;
- agrupación mediante paréntesis;
- asignaciones `=`, `+=`, `-=`, `*=`, `/=`, `%=`.

La precedencia implementada, de mayor a menor, es: postfix, unarios, multiplicación/división/residuo, suma/resta, comparación, igualdad, `and`, `or`.

### Control de flujo

- `if`, `else if` y `else`;
- `while`;
- `for (arreglo) |elemento|`;
- `for (inicio..fin) |indice|`;
- `switch` con uno o varios valores por caso, bloques como brazos y caso `else` obligatorio;
- `return`, `break` y `continue`.

### Léxico

- identificadores ASCII;
- comentarios de línea `//`;
- escapes de carácter `\n`, `\r`, `\t`, `\\` y `\'`;
- posición inicial y final de cada token.

Un carácter Unicode no admitido que aparezca dentro de una secuencia continua de identificador invalida la secuencia completa. Así, `niño` produce una fila `ERROR_LEXICO` para `niño`, con su intervalo completo, en lugar de separar `ni`, `ñ` y `o`. Un símbolo inválido aislado se registra como su propio elemento de error.

## Elementos excluidos

- `comptime`, punteros, slices y sentinel arrays;
- literales de cadena con comillas dobles y un tipo artificial `string`;
- structs, enums, unions y métodos;
- optionals, error unions, `try`, `catch` y `defer`;
- imports, builtins como `@divTrunc`, acceso a bibliotecas y E/S;
- genéricos, reflexión, ensamblador y concurrencia;
- literales hexadecimales/binarios y separadores `_` en números.

## Simplificaciones respecto de Zig

- `switch` se modela como sentencia y cada brazo debe ser un bloque.
- Cada `for` tiene una sola captura y no admite captura de índice simultánea.
- No se admiten comas finales en parámetros, argumentos o literales de arreglo.
- Los tipos de arreglo requieren un tamaño entero literal; `[_]T` sólo se usa al construir un arreglo.
- La gramática puede aceptar operaciones que posteriormente serán inválidas por tipo. Por ejemplo, Zig 0.16 requiere `@divTrunc` o una variante equivalente para dividir `i32`; detectar eso corresponde al futuro análisis semántico. El ejemplo común utiliza `/` y `%` únicamente con tipos para los que Zig real los permite.
- La pasada léxica se recupera después de un carácter inválido y continúa para reunir varios errores en una ejecución. La pasada sintáctica se omite si hubo errores léxicos; cuando sí se ejecuta, se detiene en el primer punto donde JavaCC ya no puede continuar con seguridad.

## Texto sin strings

Un texto se escribe como arreglo de `u8`:

```zig
const word = [_]u8{ 'Z', 'i', 'g' };
```

Es un arreglo por valor, sin terminador implícito, puntero ni slice. Se puede recorrer, indexar y consultar con `.len`, pero no posee operaciones especiales de cadena.
