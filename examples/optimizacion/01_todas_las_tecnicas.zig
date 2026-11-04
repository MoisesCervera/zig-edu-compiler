// Ejemplo para la fase de optimización: cada bloque provoca una técnica distinta.
// Abre la pestaña "Optimización" y revisa la "Bitácora de optimización".

fn areaRectangulo(base: f64, altura: f64) f64 {
    // Reducción de potencias: dividir entre 2.0 se cambia por multiplicar por 0.5.
    return base * altura / 2.0;
}

fn duplicar(valor: i32) i32 {
    // Reducción de potencias: x * 2 se cambia por x + x.
    return valor * 2;
}

fn identidades(x: i32) i32 {
    // Identidades algebraicas: x * 1, x + 0 y x - 0 no cambian el valor.
    const a = x * 1;
    const b = a + 0;
    return b - 0;
}

fn sinUso(x: i32) i32 {
    // Código muerto: esta función nunca se llama desde main.
    return x * x;
}

fn despuesDeReturn(x: i32) i32 {
    if (x > 0) {
        return x;
    }
    return 0;
    // Código muerto: nada de lo que sigue a un return se ejecuta.
    const nunca = x + 1;
    _ = nunca;
}

fn variableSinUso(x: i32) i32 {
    // Código muerto: 'temporal' se modifica pero su valor nunca se lee.
    var temporal: i32 = x * 10;
    temporal += 1;
    return x;
}

fn subexpresiones(a: i32, b: i32, c: i32) i32 {
    // Subexpresiones redundantes: (a + b) se calcula una sola vez.
    const primero = (a + b) * c;
    const segundo = (a + b) * 2 + c;
    return primero + segundo;
}

fn invariante(limite: i32) i32 {
    var total: i32 = 0;
    for (0..10) |i| {
        // Programación de variables: factor no cambia entre vueltas; se calcula antes del ciclo.
        const factor = limite * 3;
        total += factor;
        _ = i;
    }
    return total;
}

pub fn main() void {
    // Ensamblamiento: las operaciones con constantes se calculan al compilar.
    const segundosPorDia = 60 * 60 * 24;
    const factor = (2 * 3141) / 1000 + 10 - 4;

    // Programación de variables: estas var nunca cambian, se vuelven const y se propagan.
    var a: i32 = 7;
    var b: i32 = 3;
    var total: i32 = a + b * 2;
    total = (a + b) * (factor - 2);

    // Código muerto: la condición siempre es falsa.
    const depuracion = false;
    if (depuracion) {
        total = 0;
    }

    const datos = [_]i32{ 4, 8, 15, 16, 23, 42 };
    var suma: i32 = 0;
    for (0..datos.len) |i| {
        suma += datos[i] * 1;
    }

    _ = segundosPorDia;
    _ = total;
    _ = suma;
    _ = areaRectangulo(10.0, 4.0);
    _ = duplicar(21);
    _ = identidades(5);
    _ = despuesDeReturn(3);
    _ = variableSinUso(8);
    _ = subexpresiones(1, 2, 3);
    _ = invariante(4);
}
