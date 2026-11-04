fn precioConIva(precio: f64) f64 {
    const total = precio + precio * 0.16;
    return total;
}

fn promedio(a: f64, b: f64) f64 {
    return (a + b) * 0.5;
}

fn costoPedido(cantidad: i32, precio: i32, envio: i32) i32 {
    const cse1 = cantidad * precio;
    const subtotal = cse1 + envio;
    const comision = cse1 / 10;
    const puntos = cse1 % 7;
    return subtotal + comision + puntos;
}

fn ventasSemana(base: i32) i32 {
    var total: i32 = 0;
    const ventaFija = base * 3;
    for (0..7) |dia| {
        total += ventaFija;
        _ = dia;
    }
    return total;
}

pub fn main() void {
    const minutosPorDia = 1440;
    const diasPorSemana = 7;
    const minutosSemana = 10080;
    const precioBase: i32 = 120;
    const piezas: i32 = 3;
    var importe: i32 = 360;
    const modoPrueba = false;
    importe += 5;
    var acumulado: i32 = 0;
    var semana: i32 = 0;
    while (semana < 4) {
        acumulado += ventasSemana(120);
        semana += 1;
    }
    _ = 10080;
    _ = importe;
    _ = acumulado;
    _ = precioConIva(250.0);
    _ = promedio(8.5, 9.5);
    _ = costoPedido(3, 120, 50);
}
