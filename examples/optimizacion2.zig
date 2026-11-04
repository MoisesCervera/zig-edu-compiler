// ==========================================================
// Ejemplo 2 de optimización de código en Zig
// Sistema sencillo de cálculo de ventas de una tienda
// ==========================================================

// Calcula el precio con IVA de un producto.
fn precioConIva(precio: f64) f64 {
    // El IVA se escribe como operación entre constantes.
    const iva = 16.0 / 100.0;
    const total = precio + precio * iva;
    return total;
    // Este mensaje nunca se ejecuta porque está después del return.
    const mensaje = total * 2.0;
    _ = mensaje;
}

// Calcula el promedio de dos calificaciones.
fn promedio(a: f64, b: f64) f64 {
    // Dividir entre 2.0 se puede cambiar por multiplicar por 0.5.
    return (a + b) / 2.0;
}

// Función antigua que ya no se utiliza en el programa.
fn descuentoAnterior(precio: i32) i32 {
    const descuento = precio * 10 / 100;
    return precio - descuento;
}

// Otra función que tampoco se llama desde main.
fn reporteViejo(x: i32, y: i32) i32 {
    return x * y + x * y;
}

// Calcula el costo total de un pedido.
fn costoPedido(cantidad: i32, precio: i32, envio: i32) i32 {
    // Variable de prueba que se modifica pero nunca se lee.
    var depuracion: i32 = cantidad * 1000;
    depuracion += 1;
    depuracion *= 2;

    // (cantidad * precio) aparece repetido varias veces.
    const subtotal = (cantidad * precio) + envio;
    const comision = (cantidad * precio) / 10;
    const puntos = (cantidad * precio) % 7;

    // Operaciones que no cambian el valor.
    const neto = subtotal * 1 + 0;
    return neto + comision + puntos;
}

// Suma las ventas de varios días usando ciclos.
fn ventasSemana(base: i32) i32 {
    var total: i32 = 0;
    for (0..7) |dia| {
        // Este valor no cambia entre vueltas del ciclo.
        const ventaFija = base * 3;
        total += ventaFija;
        _ = dia;
    }

    // Ciclo que nunca se ejecuta: el rango está vacío.
    for (5..5) |i| {
        total += 1000;
        _ = i;
    }
    return total;
}

pub fn main() void {
    // Constantes del sistema calculadas con operaciones.
    const minutosPorDia = 60 * 24;
    const diasPorSemana = 7;
    const minutosSemana = minutosPorDia * diasPorSemana;

    // Variables que en realidad nunca cambian.
    var precioBase: i32 = 120;
    var piezas: i32 = 3;
    var importe: i32 = precioBase * piezas;
    importe = importe + 0;
    importe *= 1;

    // Bandera de depuración apagada: el bloque es código muerto.
    const modoPrueba = false;
    if (modoPrueba) {
        importe = 0;
        importe += 999;
    }

    // Condición que siempre es verdadera.
    if (10 > 5) {
        importe += 5;
    } else {
        importe -= 5;
    }

    // Ciclo que nunca se ejecuta.
    while (false) {
        importe += 1;
    }

    // Acumulado de ventas del mes.
    var acumulado: i32 = 0;
    var semana: i32 = 0;
    while (semana < 4) {
        acumulado += ventasSemana(precioBase);
        semana += 1;
    }

    _ = minutosSemana;
    _ = importe;
    _ = acumulado;
    _ = precioConIva(250.0);
    _ = promedio(8.5, 9.5);
    _ = costoPedido(piezas, precioBase, 50);
}