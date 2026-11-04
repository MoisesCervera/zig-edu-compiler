fn calcularArea(base: f64, altura: f64) f64 {
    const area = base * altura / 2.0;
    return area;
    const sinUso = area * 10.0;
    _ = sinUso;
}

fn funcionNoUsada(x: i32) i32 {
    return x * x;
}

fn sumarDoble(a: i32, b: i32) i32 {
    var temporal: i32 = a * 100;
    temporal += 1;
    const primero = (a + b) * 2;
    const segundo = (a + b) * 3;
    return primero + segundo;
}

pub fn main() void {
    const horas = 24;
    const segundosDia = 60 * 60 * horas;

    var precio: i32 = 50;
    var cantidad: i32 = 4;
    var total: i32 = precio * cantidad;
    total = total + 0;

    const depurar = false;
    if (depurar) {
        total = 0;
    }

    while (false) {
        total += 1;
    }

    var contador: i32 = 0;
    for (0..5) |i| {
        contador += 1;
    }

    _ = segundosDia;
    _ = total;
    _ = contador;
    _ = calcularArea(10.0, 4.0);
    _ = sumarDoble(3, 5);
}