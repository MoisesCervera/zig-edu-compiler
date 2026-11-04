fn calcularArea(base: f64, altura: f64) f64 {
    const area = base * altura * 0.5;
    return area;
}

fn sumarDoble(a: i32, b: i32) i32 {
    const cse1 = a + b;
    const primero = cse1 * 2;
    const segundo = cse1 * 3;
    return primero + segundo;
}

pub fn main() void {
    const horas = 24;
    const segundosDia = 86400;
    const precio: i32 = 50;
    const cantidad: i32 = 4;
    const total: i32 = 200;
    const depurar = false;
    var contador: i32 = 0;
    for (0..5) |_| {
        contador += 1;
    }
    _ = 86400;
    _ = 200;
    _ = contador;
    _ = calcularArea(10.0, 4.0);
    _ = sumarDoble(3, 5);
}
