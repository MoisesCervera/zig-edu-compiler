// Ejercicio clásico de optimización (similar al de clase):
// constantes que se ensamblan, variables que no cambian, una rama que nunca se ejecuta
// y una expresión repetida. Compara el resultado activando y desactivando
// "Conservar variables de main" en la pestaña Optimización.

fn calcular(a: i32, b: i32, c: i32) i32 {
    const base: i32 = 3 * 4;
    var total: i32 = a + b * 2;
    total = (a + b) * (base - 2);
    const residuo = total % 7 + total - 7 + a / b;
    const potencia = 2 * 2 * 2 * 2 * 2 + c * 1;
    if (false) {
        total = 0;
    }
    const sinValor: i32 = 5;
    _ = sinValor;
    return total + residuo + potencia + (a + b) * c;
}

pub fn main() void {
    const base: i32 = 3 * 4;
    var a: i32 = 7;
    var b: i32 = 3;
    var total: i32 = a + b * 2;
    total = (a + b) * (base - 2);
    const promedio: f64 = 2.5;
    const area = promedio * promedio * 3.0 + 0.5;
    const mitad = area / 2.0;
    _ = mitad;
    _ = calcular(a, b, 4);
    _ = total;
}
