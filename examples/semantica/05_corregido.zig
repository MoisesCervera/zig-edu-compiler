// Version corregida: debe aprobar las tres fases.
fn sumar(a: i32, b: i32) i32 {
    return a + b;
}

pub fn main() void {
    var edad: i32 = 20;
    var total: i32 = 10;
    const esMayor: bool = true;
    const letras = [_]u8{ 'Z', 'i', 'g' };
    total = sumar(total, edad);
    if (esMayor) {
        edad += 1;
    }
    _ = letras[1];
    _ = letras.len;
    _ = total;
    _ = edad;
}
